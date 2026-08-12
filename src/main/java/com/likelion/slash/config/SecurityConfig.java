package com.likelion.slash.config;

import com.likelion.slash.common.error.ApiAccessDeniedHandler;
import com.likelion.slash.common.error.ApiAuthenticationEntryPoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
    };

    /**
     * WSS 전용 체인. {@code /ws/**}는 별도 체인으로 완전히 분리한다 — 같은 체인에 두고
     * {@code authorizeHttpRequests}만 permitAll 해서는 부족하다. {@code oauth2ResourceServer}의
     * Bearer 토큰 필터는 경로 인가 규칙과 무관하게 {@code Authorization: Bearer} 헤더가 있으면
     * 무조건 JWT로 파싱을 시도하고, 실패하면 그 필터 안에서 바로 401을 내버린다 — permitAll이
     * 적용되는 authorizeHttpRequests 단계까지 가지도 못한다. (이슈 #15)
     *
     * <p>Agent는 기기 Token을 정확히 그 헤더(Authorization: Bearer)로 보낸다({@code /ws/agent}
     * 핸드셰이크, {@link com.likelion.slash.ws.AgentWebSocketHandler}) — Cognito가 발급한 JWT가
     * 아니므로 실제 Cognito 검증을 켜는 순간(로컬 임시 인증이 꺼지는 순간) 핸드셰이크 자체가
     * 401로 끊긴다. 인증은 이미 프로토콜 안(도전값 서명·접속표)에서 하기로 설계되어 있으므로,
     * 이 체인에는 애초에 oauth2ResourceServer를 붙이지 않는다.
     */
    @Bean
    @Order(1)
    SecurityFilterChain webSocketSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/ws/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
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
