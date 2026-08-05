package com.likelion.slash.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Cognito Access Token 검증기.
 *
 * <p>발급자 정보가 있는 환경에서만 만들어진다. {@link SecurityConfig} 가 이 빈의 존재 여부로
 * JWT 검증을 붙일지 결정하므로, 로컬처럼 Cognito 가 없는 환경에서도 애플리케이션은 기동된다.
 *
 * <p><b>기본 검증만으로는 부족하다.</b> {@code issuer-uri} 로 만든 기본 검증기는 서명·발급자·만료만 본다.
 * Cognito Access Token 에는 {@code aud} 클레임이 없어서 대상 검증이 자동으로 걸리지 않으므로,
 * 아래 두 가지를 직접 확인한다.
 *
 * <ul>
 *   <li>{@code token_use = "access"} — ID Token 을 Access Token 자리에 넣어 보내는 것을 막는다.
 *       두 토큰은 같은 User Pool 이 서명하므로 서명 검증만으로는 구분되지 않는다.</li>
 *   <li>{@code client_id} — 같은 User Pool 에 붙은 다른 App Client(예: 관리자용, 다른 서비스)의
 *       토큰이 그대로 통과하는 것을 막는다.</li>
 * </ul>
 *
 * <p>관련 문서: 3.2.1 · WBS W1-01
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.security.oauth2.resourceserver.jwt", name = "issuer-uri")
public class CognitoJwtConfig {

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${slash.auth.cognito.client-id}") String clientId) {

        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
        decoder.setJwtValidator(cognitoAccessTokenValidator(issuerUri, clientId));
        return decoder;
    }

    /** 기본 검증(서명·발급자·만료)에 Cognito 고유 검증을 더한다. */
    static OAuth2TokenValidator<Jwt> cognitoAccessTokenValidator(String issuerUri, String clientId) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                accessTokenOnly(),
                issuedToOurClient(clientId));
    }

    /** ID Token 은 {@code token_use = "id"} 라 여기서 걸러진다. */
    static OAuth2TokenValidator<Jwt> accessTokenOnly() {
        return new JwtClaimValidator<String>("token_use", "access"::equals);
    }

    /** 우리 App Client 가 발급받은 토큰인지 확인한다. */
    static OAuth2TokenValidator<Jwt> issuedToOurClient(String clientId) {
        return new JwtClaimValidator<String>("client_id", clientId::equals);
    }
}
