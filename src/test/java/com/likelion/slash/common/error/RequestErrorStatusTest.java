package com.likelion.slash.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.response.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.ErrorResponseException;
import org.springframework.transaction.annotation.Transactional;

/**
 * 클라이언트가 잘못 보낸 요청이 4xx 로 나가는지 확인한다. (#74)
 *
 * <p><b>전에는 넷이 500 `INTERNAL_ERROR` 였다.</b> `GlobalExceptionHandler` 가 Spring MVC 의
 * 요청 오류를 다루지 않아 catch-all 로 떨어졌기 때문이다. 5xx 는 "서버가 잘못했으니 다시
 * 보내면 될 수도 있다" 는 뜻인데, 이것들은 <b>보내는 쪽을 고치지 않으면 몇 번을 보내도 같다.</b>
 *
 * <p>오타 URL 하나가 500 으로 집계되어 5xx 알림이 오탐을 내고, catch-all 이 스택트레이스를
 * 남겨 로그가 쌓이는 문제도 함께 있었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RequestErrorStatusTest {

    private static final String 사용자 = "Bearer request-error-tester";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GlobalExceptionHandler handler;

    @Test
    @DisplayName("없는 주소는 404 로 답한다")
    void 없는_주소는_404() throws Exception {
        mockMvc.perform(get("/api/v1/nope").header("Authorization", 사용자))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("받지 않는 메서드는 405 로 답한다")
    void 받지_않는_메서드는_405() throws Exception {
        mockMvc.perform(delete("/api/v1/requests").header("Authorization", 사용자))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("다룰 수 없는 Content-Type 은 415 로 답한다")
    void 다룰_수_없는_형식은_415() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .header("Authorization", 사용자)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{\"text\":\"/status\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("JSON 을 받지 않겠다는 Accept 는 406 으로 답한다")
    void 맞출_수_없는_형식은_406() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", 사용자)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable());
    }

    @Test
    @DisplayName("본문이 깨진 요청은 그대로 400 이다")
    void 깨진_본문은_400() throws Exception {
        // 이미 핸들러가 있던 경로다. 새 분기가 가로채지 않는지 함께 본다.
        mockMvc.perform(post("/api/v1/requests")
                        .header("Authorization", 사용자)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("진짜 서버 오류는 그대로 500 이다")
    void 서버_오류는_500() {
        ResponseEntity<ErrorResponse> 응답 = handler.handleUnexpected(new IllegalStateException("무언가 터졌다"));

        assertThat(응답.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(응답.getBody().error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
    }

    @Test
    @DisplayName("5xx 를 담고 온 Spring 오류도 500 으로 남는다")
    void 스프링이_준_5xx_는_가로채지_않는다() {
        // 4xx 만 갈라내야 한다. 5xx 까지 가져가면 진짜 서버 오류의 스택트레이스가 사라져
        // 장애를 찾을 수 없게 된다.
        ResponseEntity<ErrorResponse> 응답 =
                handler.handleUnexpected(new ErrorResponseException(HttpStatus.BAD_GATEWAY));

        assertThat(응답.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(응답.getBody().error().code()).isEqualTo(ErrorCode.INTERNAL_ERROR.name());
    }
}
