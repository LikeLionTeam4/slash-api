package com.likelion.slash.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Cognito Access Token 추가 검증기 확인.
 *
 * <p>서명 검증만으로는 걸러지지 않는 두 가지를 본다.
 * 같은 User Pool 이 서명한 ID Token 과, 같은 Pool 에 붙은 다른 App Client 의 토큰이다.
 */
class CognitoJwtConfigTest {

    private static final String OUR_CLIENT_ID = "our-app-client";

    private static Jwt tokenWith(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256");
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Nested
    @DisplayName("token_use 검증")
    class TokenUse {

        private final OAuth2TokenValidator<Jwt> validator = CognitoJwtConfig.accessTokenOnly();

        @Test
        @DisplayName("Access Token 은 통과한다")
        void access_는_통과한다() {
            var result = validator.validate(tokenWith(Map.of("token_use", "access")));

            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("ID Token 은 거부한다")
        void id_토큰은_거부한다() {
            // 같은 User Pool 이 서명하므로 서명 검증만으로는 구분되지 않는다.
            var result = validator.validate(tokenWith(Map.of("token_use", "id")));

            assertThat(result.hasErrors()).isTrue();
        }

        @Test
        @DisplayName("token_use 가 없으면 거부한다")
        void 클레임이_없으면_거부한다() {
            var result = validator.validate(tokenWith(Map.of("sub", "user-1")));

            assertThat(result.hasErrors()).isTrue();
        }
    }

    @Nested
    @DisplayName("client_id 검증")
    class ClientId {

        private final OAuth2TokenValidator<Jwt> validator =
                CognitoJwtConfig.issuedToOurClient(OUR_CLIENT_ID);

        @Test
        @DisplayName("우리 App Client 가 발급받은 토큰은 통과한다")
        void 우리_클라이언트는_통과한다() {
            var result = validator.validate(tokenWith(Map.of("client_id", OUR_CLIENT_ID)));

            assertThat(result.hasErrors()).isFalse();
        }

        @Test
        @DisplayName("같은 User Pool 의 다른 App Client 토큰은 거부한다")
        void 다른_클라이언트는_거부한다() {
            // 관리자 콘솔 등 다른 Client 의 토큰이 그대로 통과하면 안 된다.
            var result = validator.validate(tokenWith(Map.of("client_id", "another-app-client")));

            assertThat(result.hasErrors()).isTrue();
        }

        @Test
        @DisplayName("client_id 가 없으면 거부한다")
        void 클레임이_없으면_거부한다() {
            var result = validator.validate(tokenWith(Map.of("sub", "user-1")));

            assertThat(result.hasErrors()).isTrue();
        }
    }
}
