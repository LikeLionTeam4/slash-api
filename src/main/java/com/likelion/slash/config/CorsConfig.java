package com.likelion.slash.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 브라우저 교차 출처 요청 허용 설정.
 *
 * <p>웹 클라이언트는 별도 도메인(CloudFront)에서 서비스되므로 이 설정이 없으면
 * 모든 요청이 preflight 단계에서 막힌다.
 *
 * <p>허용 오리진은 환경마다 다르므로 {@code CORS_ALLOWED_ORIGINS} 로 주입받는다.
 * 기본값은 로컬 개발 서버다.
 */
@Configuration
public class CorsConfig {

    /**
     * 프론트가 보내야 하는 헤더.
     *
     * <p>여기 없는 헤더를 붙이면 preflight 에서 거부된다.
     */
    private static final List<String> ALLOWED_HEADERS = List.of(
            HttpHeaders.AUTHORIZATION,
            HttpHeaders.CONTENT_TYPE,
            // 작업 생성 중복 방지 (문서 3.4.6)
            "Idempotency-Key",
            // 낙관적 잠금 (문서 3.4.4)
            HttpHeaders.IF_MATCH);

    /**
     * 브라우저가 읽을 수 있게 열어 주는 응답 헤더.
     *
     * <p>교차 출처에서는 기본적으로 몇 개의 안전한 헤더만 보인다.
     * {@code ETag} 를 열지 않으면 프론트가 값을 읽지 못해 {@code If-Match} 를 만들 수 없다.
     */
    private static final List<String> EXPOSED_HEADERS = List.of(HttpHeaders.ETAG);

    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${slash.cors.allowed-origins}") List<String> allowedOrigins) {

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setExposedHeaders(EXPOSED_HEADERS);

        // 인증은 Authorization 헤더로 한다. 쿠키를 쓰지 않으므로 자격증명을 허용하지 않는다.
        // 허용하면 오리진에 와일드카드를 쓸 수 없게 되고, CSRF 표면도 넓어진다.
        configuration.setAllowCredentials(false);

        // preflight 응답을 1시간 캐시해 요청 수를 줄인다.
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
