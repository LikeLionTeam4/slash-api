package com.likelion.slash.health;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 의존 서비스 점검 Endpoint 와 보안 설정을 확인한다. (WBS W1-00)
 *
 * <p>공통 응답 형식·시각 표기·인증 실패 응답이 실제 HTTP 로 나가는지 함께 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    /** ISO 8601 한국 시각. 예: 2026-08-05T11:24:17.365+09:00 */
    private static final String KST_PATTERN = ".*\\+09:00$";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("인증 없이 의존 서비스 상태를 확인할 수 있다")
    void 인증_없이_점검할_수_있다() throws Exception {
        mockMvc.perform(get("/api/v1/health/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database").value("UP"))
                .andExpect(jsonPath("$.data.valkey").value("UP"));
    }

    @Test
    @DisplayName("응답이 공통 형식과 한국 시각을 따른다")
    void 공통_응답_형식을_따른다() throws Exception {
        mockMvc.perform(get("/api/v1/health/dependencies"))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andExpect(jsonPath("$.meta.serverTime").value(matchesPattern(KST_PATTERN)))
                .andExpect(jsonPath("$.data.checkedAt").value(matchesPattern(KST_PATTERN)));
    }

    @Test
    @DisplayName("인증이 필요한 경로는 공통 오류 형식으로 401 을 반환한다")
    void 인증_실패도_공통_형식으로_응답한다() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.error.message").exists())
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    @DisplayName("세션 쿠키를 만들지 않는다")
    void 세션을_만들지_않는다() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
    }

    @Test
    @DisplayName("Basic 인증 챌린지를 보내지 않는다")
    void basic_인증을_사용하지_않는다() throws Exception {
        // Cognito Access Token 을 쓰므로 브라우저 기본 로그인 창이 뜨면 안 된다.
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    @DisplayName("Kubernetes Probe 경로는 인증 없이 열려 있다")
    void actuator_health_는_공개다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
