package com.likelion.slash.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.util.StringUtils;

/**
 * 로컬 개발용 임시 인증. <b>실제 서명을 검증하지 않는다.</b>
 *
 * <p>Cognito 값이 나오기 전까지 프론트가 로그인 이후 화면을 개발할 수 있도록 두는 임시 장치다.
 * 아무 문자열이나 Bearer 로 보내면 그 문자열을 {@code sub} 로 하는 사용자로 인증된다.
 *
 * <pre>
 * curl -H "Authorization: Bearer alice" http://localhost:8080/api/v1/me
 *   → sub = alice, email = alice@local.test 인 사용자로 처리된다.
 * </pre>
 *
 * <p>같은 문자열을 보내면 항상 같은 사용자가 되므로, 사람을 바꿔가며 시험할 때는
 * {@code alice} · {@code bob} 처럼 다른 문자열을 쓰면 된다.
 *
 * <p><b>운영에 새어 나가지 않도록 두 겹으로 막는다.</b>
 * <ol>
 *   <li>{@code local} 프로필에서만 만들어진다 — {@code dev}·{@code demo} 에서는 빈 자체가 없다</li>
 *   <li>{@code slash.auth.local-dev-token.enabled=true} 를 명시해야 한다 —
 *       이 값은 {@code application-local.yml} 에만 있다</li>
 * </ol>
 *
 * <p>Cognito 준비가 끝나면 {@code application-local.yml} 에서 이 설정을 끄고
 * {@code issuer-uri} 를 넣으면 {@link CognitoJwtConfig} 가 대신 동작한다.
 * 그때 이 클래스는 지워도 된다.
 */
@Configuration
@Profile("local")
@ConditionalOnProperty(name = "slash.auth.local-dev-token.enabled", havingValue = "true")
public class LocalDevAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalDevAuthConfig.class);

    /** 토큰으로 받아들일 문자열. 경로·헤더에 그대로 들어가므로 안전한 문자만 허용한다. */
    private static final Pattern ALLOWED_SUBJECT = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    private static final String ISSUER = "slash-local-dev";
    private static final String EMAIL_DOMAIN = "@local.test";

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri) {

        // 진짜 발급자가 설정된 채로 이 빈이 만들어지면 CognitoJwtConfig 와 빈이 충돌한다.
        // 어느 쪽이 쓰이는지 모르는 상태로 뜨는 것보다, 이유를 알려주고 멈추는 편이 낫다.
        if (StringUtils.hasText(issuerUri)) {
            throw new IllegalStateException("""
                    Cognito issuer-uri 와 로컬 임시 인증이 함께 설정되어 있습니다.
                    application-local.yml 에서 slash.auth.local-dev-token.enabled 를 false 로 바꾸세요.
                    """);
        }

        log.warn("""

                ============================================================
                 로컬 임시 인증이 켜져 있습니다. 토큰 서명을 검증하지 않습니다.
                 Authorization: Bearer <아무 문자열> 로 그 문자열의 사용자가 됩니다.
                 local 프로필 전용이며 dev·demo 에서는 동작하지 않습니다.
                ============================================================
                """);

        return this::decodeAsUser;
    }

    /**
     * 토큰 문자열을 그대로 사용자 식별자로 삼아 Cognito Access Token 과 같은 모양의 {@link Jwt} 를 만든다.
     *
     * <p>클레임 이름을 Cognito 와 맞춰 두었기 때문에, 나중에 진짜 토큰으로 바뀌어도
     * 이 뒤의 코드는 고치지 않아도 된다.
     */
    private Jwt decodeAsUser(String token) {
        if (!ALLOWED_SUBJECT.matcher(token).matches()) {
            throw new BadJwtException(
                    "로컬 임시 토큰은 영문·숫자·. _ - 만 쓸 수 있습니다. (최대 64자)");
        }

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(1, ChronoUnit.HOURS);

        return Jwt.withTokenValue(token)
                .header("alg", "none")
                .issuer(ISSUER)
                .subject(token)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                // Cognito Access Token 과 같은 클레임 이름을 쓴다.
                .claim("token_use", "access")
                .claim("client_id", ISSUER)
                // 실제 Cognito Access Token 에는 email 이 없지만,
                // 로컬에는 userInfo 를 호출할 곳이 없어 여기서 채워 준다.
                .claim("email", token + EMAIL_DOMAIN)
                .claim("name", token)
                .build();
    }

    /** 시험이 같은 규칙으로 사용자를 만들 수 있도록 노출한다. */
    public static Map<String, Object> claimsFor(String subject) {
        return Map.of(
                "token_use", "access",
                "client_id", ISSUER,
                "email", subject + EMAIL_DOMAIN,
                "name", subject);
    }
}
