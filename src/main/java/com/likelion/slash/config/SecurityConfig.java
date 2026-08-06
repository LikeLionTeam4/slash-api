package com.likelion.slash.config;

import com.likelion.slash.common.error.ApiAccessDeniedHandler;
import com.likelion.slash.common.error.ApiAuthenticationEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API 보안 설정.
 *
 * <p>사용자 인증은 Cognito Access Token(JWT)으로 처리한다.
 * 세션과 Basic 인증을 쓰지 않으며, Spring 이 자체 로그인 API 를 제공하지 않는다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** 인증 없이 접근할 수 있는 경로. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            // 의존 서비스 연결 점검. 배포 확인과 장애 조사에 사용한다.
            "/api/v1/health/**",
            // Agent 등록은 기기 토큰을 받기 전 단계라 사용자 인증을 요구하지 않는다.
            // 등록 코드와 Ed25519 서명으로 자체 검증한다.
            "/api/v1/agent/pair",
            "/api/v1/agent/pair/verify",
            // 기기 Token 재발급도 사용자 인증이 아니라 기기 서명으로 증명한다. (메시지 스펙 §8.1 3단계)
            "/api/v1/agent/sessions/refresh",
            // WSS 는 접속 시점에 아직 누구인지 모른다. 인증을 프로토콜 안에서 처리한다.
            // Agent 는 도전값 서명(3.4.2), 사용자는 30초·1회용 Ticket 으로 검증한다.
            // 검증 전에는 소켓이 보관소에 등록되지 않아 어떤 프레임도 나가지 않는다.
            "/ws/**",
    };

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler,
            ObjectProvider<JwtDecoder> jwtDecoderProvider) throws Exception {

        http
                // 웹 클라이언트가 다른 도메인에서 서비스되므로 교차 출처 요청을 허용한다.
                // 허용 범위는 CorsConfig 가 정한다.
                .cors(Customizer.withDefaults())
                // 토큰 기반이라 CSRF 토큰이 필요 없다.
                .csrf(csrf -> csrf.disable())
                // 세션을 만들지 않는다. 확장성과 보안을 위해 Stateless 로 운영한다.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 기본 로그인 수단을 모두 끈다.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                // 인증·권한 실패도 공통 오류 형식으로 응답한다.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        // 토큰 검증기가 있는 환경에서만 JWT 검증을 붙인다.
        // 발급자 정보가 없는 환경에서도 애플리케이션이 기동되도록 한다.
        jwtDecoderProvider.ifAvailable(decoder ->
                applyJwtResourceServer(http, decoder, authenticationEntryPoint));

        return http.build();
    }

    private void applyJwtResourceServer(HttpSecurity http,
                                        JwtDecoder decoder,
                                        ApiAuthenticationEntryPoint authenticationEntryPoint) {
        try {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    // 토큰이 만료·위조된 경우는 exceptionHandling 이 아니라 이쪽이 처리한다.
                    // 지정하지 않으면 Spring 기본 EntryPoint 가 본문 없는 401 을 내보내
                    // 프론트가 error.code 로 분기할 수 없다. 만료는 가장 흔한 경우라 반드시 맞춰야 한다.
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .jwt(jwt -> jwt.decoder(decoder)));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 검증 설정에 실패했습니다.", e);
        }
    }
}
