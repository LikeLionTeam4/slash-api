package com.likelion.slash.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

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
 * <p><b>운영에 새어 나가지 않도록 세 겹으로 막는다.</b>
 * <ol>
 *   <li>{@code local} 프로필에서만 만들어진다 — {@code dev}·{@code demo} 에서는 빈 자체가 없다</li>
 *   <li>{@code slash.auth.local-dev-token.enabled=true} 를 명시해야 한다 —
 *       이 값은 {@code application-local.yml} 에만 있다</li>
 *   <li>Cognito 발급자가 설정되어 있으면 만들어지지 않는다</li>
 * </ol>
 *
 * <p>세 번째 조건 덕분에 {@code COGNITO_ISSUER_URI} 를 넣는 것만으로 실제 Cognito 검증으로
 * 넘어간다. 설정을 두 군데 고칠 필요가 없고, {@link JwtDecoder} 빈이 둘 생기는 일도 없다.
 *
 * <p>Cognito 로 완전히 넘어가면 이 클래스는 지워도 된다.
 */
@Configuration
@Profile("local")
@ConditionalOnProperty(name = "slash.auth.local-dev-token.enabled", havingValue = "true")
@ConditionalOnExpression("!(" + CognitoJwtConfig.ISSUER_CONFIGURED + ")")
public class LocalDevAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(LocalDevAuthConfig.class);

    /** 토큰으로 받아들일 문자열. 경로·헤더에 그대로 들어가므로 안전한 문자만 허용한다. */
    private static final Pattern ALLOWED_SUBJECT = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    private static final String ISSUER = "slash-local-dev";
    private static final String EMAIL_DOMAIN = "@local.test";

    @Bean
    JwtDecoder jwtDecoder() {
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
}
