package com.likelion.slash.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 웹 클라이언트가 다른 도메인에서 API 를 호출할 수 있는지 확인한다.
 *
 * <p>이 설정이 없으면 프론트는 첫 요청부터 preflight 에서 막힌다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigTest {

    /** 웹 클라이언트(Vite) 로컬 개발 서버. application.yml 의 기본값이다. */
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("허용 오리진의 preflight 를 통과시킨다")
    void preflight_를_통과시킨다() throws Exception {
        mockMvc.perform(options("/api/v1/me")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("프론트가 보내야 하는 헤더를 허용한다")
    void 필요한_요청_헤더를_허용한다() throws Exception {
        // 여기 없는 헤더를 붙이면 preflight 에서 거부된다.
        mockMvc.perform(options("/api/v1/requests")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers",
                                "Authorization, Content-Type, Idempotency-Key, If-Match"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ETag 를 브라우저가 읽을 수 있게 연다")
    void etag_를_노출한다() throws Exception {
        // 열어 주지 않으면 프론트가 값을 읽지 못해 If-Match 를 만들 수 없다.
        mockMvc.perform(get("/api/v1/health/dependencies")
                        .header("Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", "ETag"));
    }

    @Test
    @DisplayName("허용하지 않은 오리진은 막는다")
    void 모르는_오리진은_막는다() throws Exception {
        mockMvc.perform(options("/api/v1/me")
                        .header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("쿠키 기반 자격증명은 허용하지 않는다")
    void 자격증명을_허용하지_않는다() throws Exception {
        // 인증은 Authorization 헤더로 한다. 쿠키를 쓰지 않는다.
        mockMvc.perform(options("/api/v1/me")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
