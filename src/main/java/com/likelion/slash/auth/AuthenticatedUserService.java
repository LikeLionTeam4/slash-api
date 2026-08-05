package com.likelion.slash.auth;

import com.likelion.slash.common.enums.UserStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.jooq.tables.records.UsersRecord;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 검증된 Access Token 을 우리 {@code users} 레코드로 연결한다.
 *
 * <p>가입 절차가 따로 없다. Cognito 로그인이 처음 성공한 순간 사용자 레코드를 만든다.
 * 기준은 {@code sub} 다 — 이메일은 사용자가 바꿀 수 있어 식별자로 쓸 수 없다.
 *
 * <p>관련 문서: 3.2 · WBS W1-01
 */
@Service
public class AuthenticatedUserService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedUserService.class);

    private final UserRepository userRepository;
    private final CognitoUserInfoClient userInfoClient;

    public AuthenticatedUserService(UserRepository userRepository,
                                    CognitoUserInfoClient userInfoClient) {
        this.userRepository = userRepository;
        this.userInfoClient = userInfoClient;
    }

    /**
     * 현재 요청의 사용자를 반환한다. 없으면 이번 로그인에서 만든다.
     *
     * @throws SlashException 인증 정보가 없거나({@code AUTH_REQUIRED}),
     *                        이용이 정지된 계정일 때({@code FORBIDDEN})
     */
    public AuthenticatedUser current() {
        Jwt jwt = currentJwt();

        UsersRecord user = userRepository.findByCognitoSub(jwt.getSubject())
                .orElseGet(() -> createFrom(jwt));

        requireActive(user);
        return AuthenticatedUser.from(user);
    }

    /** 현재 요청의 Access Token. 필터를 통과했다면 이미 서명·발급자·만료 검증이 끝난 것이다. */
    private Jwt currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            // 공개 경로에서 이 서비스를 부르면 여기에 걸린다.
            throw new SlashException(ErrorCode.AUTH_REQUIRED);
        }

        return jwtAuthentication.getToken();
    }

    /**
     * 첫 로그인. 이메일을 구해 사용자를 만든다.
     *
     * <p>{@code users.email} 이 필수라 이메일 없이는 레코드를 만들 수 없다.
     * Access Token 에 없으면 Cognito {@code userInfo} 로 받아 온다.
     */
    private UsersRecord createFrom(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        String displayName = displayNameFrom(jwt);

        if (!StringUtils.hasText(email)) {
            Optional<CognitoUserProfile> profile = userInfoClient.fetch(jwt.getTokenValue());

            email = profile.map(CognitoUserProfile::email).orElse(null);
            if (!StringUtils.hasText(displayName)) {
                displayName = profile.map(CognitoUserProfile::displayName).orElse(null);
            }
        }

        if (!StringUtils.hasText(email)) {
            // 설정 문제이지 사용자 잘못이 아니므로 원인을 로그에 남긴다.
            log.error("""
                    이메일을 구하지 못해 사용자를 만들 수 없습니다. 아래를 확인하세요.
                      1. Cognito App Client 스코프에 email 이 포함됐는지
                      2. slash.auth.cognito.user-info-uri 가 설정됐는지""");
            throw new SlashException(ErrorCode.INTERNAL_ERROR);
        }

        return userRepository.syncFromToken(jwt.getSubject(), email, displayName);
    }

    private static String displayNameFrom(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        return StringUtils.hasText(name) ? name : jwt.getClaimAsString("given_name");
    }

    /**
     * 정지·탈퇴 계정을 막는다.
     *
     * <p>Cognito 에서 토큰이 살아 있어도 우리 쪽에서 이용을 막을 수 있어야 한다.
     * 토큰 자체는 유효하므로 401 이 아니라 403 으로 응답한다.
     */
    private static void requireActive(UsersRecord user) {
        if (!UserStatus.ACTIVE.name().equals(user.getStatus())) {
            throw new SlashException(ErrorCode.FORBIDDEN);
        }
    }
}
