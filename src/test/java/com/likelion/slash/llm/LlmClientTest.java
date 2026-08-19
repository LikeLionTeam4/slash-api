package com.likelion.slash.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.error.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * slash-llm 과의 계약 확인. (slash-llm {@code docs/BACKEND_CONTRACT.md})
 *
 * <p>대역 서버를 실제로 띄워 <b>주고받는 바이트</b>를 본다. 오류 응답의 코드를 우리 코드로
 * 옮기는 표가 이 연동의 핵심인데, 그것은 본문을 실제로 읽어야 확인된다.
 *
 * <p>상태 코드만으로 짐작하지 않는 이유도 여기서 드러난다 — 같은 503 이
 * {@code MODEL_BUSY} 와 {@code MODEL_UNAVAILABLE} 둘 다이고, 우리 쪽 코드는 같지만
 * 원장에 남길 코드는 다르다.
 */
class LlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("요약문과 모델 이름을 받아 온다")
    void 요약을_받는다() throws Exception {
        응답하는_서버(200, """
                {"summary":"세 줄 요약","model":"gemma3:4b"}""");

        LlmSummaryOutcome outcome = client().summarize(UUID.randomUUID(), UUID.randomUUID(), "요약할 긴 글");

        assertThat(outcome).isInstanceOf(LlmSummaryOutcome.Success.class);
        LlmSummaryOutcome.Success success = (LlmSummaryOutcome.Success) outcome;
        assertThat(success.response().summary()).isEqualTo("세 줄 요약");
        assertThat(success.response().model()).isEqualTo("gemma3:4b");
        assertThat(success.durationMilliseconds()).isNotNegative();
    }

    @Test
    @DisplayName("추적 식별자를 계약대로 실어 보낸다")
    void 추적_식별자를_보낸다() throws Exception {
        AtomicReference<String> 받은본문 = 본문을_받아두는_서버();
        UUID correlationId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        client().summarize(correlationId, taskId, "요약할 긴 글");

        assertThat(받은본문.get())
                .contains("\"requestId\":\"" + correlationId + "\"")
                .contains("\"taskId\":\"" + taskId + "\"")
                .contains("요약할 긴 글");
    }

    @Test
    @DisplayName("짧은 입력 거절은 입력값 오류로 옮긴다")
    void 짧은_입력을_옮긴다() throws Exception {
        응답하는_서버(400, """
                {"error":{"code":"INPUT_TOO_SHORT","message":"too short","retryable":false}}""");

        LlmFailure failure = 실패(client().summarize(UUID.randomUUID(), UUID.randomUUID(), "짧다"));

        assertThat(failure.code()).isEqualTo("INPUT_TOO_SHORT");
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETERS);
        assertThat(failure.retryable()).isFalse();
    }

    @Test
    @DisplayName("모델이 밀려 있으면 다시 시도할 수 있는 실패로 남긴다")
    void 모델_혼잡을_옮긴다() throws Exception {
        응답하는_서버(503, """
                {"error":{"code":"MODEL_BUSY","message":"busy","retryable":true}}""");

        LlmFailure failure = 실패(client().summarize(UUID.randomUUID(), UUID.randomUUID(), "요약할 긴 글"));

        assertThat(failure.code()).isEqualTo("MODEL_BUSY");
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.LLM_NOT_READY);
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    @DisplayName("모르는 코드가 와도 사용자가 빈 화면을 보지 않는다")
    void 모르는_코드도_접는다() throws Exception {
        응답하는_서버(500, """
                {"error":{"code":"NEW_CODE_WE_DO_NOT_KNOW","message":"?","retryable":false}}""");

        LlmFailure failure = 실패(client().summarize(UUID.randomUUID(), UUID.randomUUID(), "요약할 긴 글"));

        // 원장에는 온 그대로 남기고, 사용자에게는 아는 말로 답한다.
        assertThat(failure.code()).isEqualTo("NEW_CODE_WE_DO_NOT_KNOW");
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("응답이 아예 없으면 다시 시도할 수 있는 실패로 본다")
    void 닿지_못하면_재시도_가능으로_본다() {
        // 아무도 듣지 않는 포트
        LlmClient client = new LlmClient(
                RestClient.builder(), objectMapper, "http://127.0.0.1:6397", Duration.ofSeconds(2));

        LlmFailure failure = 실패(client.summarize(UUID.randomUUID(), UUID.randomUUID(), "요약할 긴 글"));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(failure.retryable()).isTrue();
    }

    @Test
    @DisplayName("요약문이 비어 있으면 성공으로 보지 않는다")
    void 빈_요약을_거른다() throws Exception {
        응답하는_서버(200, """
                {"summary":"   ","model":"gemma3:4b"}""");

        LlmFailure failure = 실패(client().summarize(UUID.randomUUID(), UUID.randomUUID(), "요약할 긴 글"));

        assertThat(failure.code()).isEqualTo("INVALID_MODEL_RESPONSE");
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    private LlmClient client() {
        return new LlmClient(RestClient.builder(), objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
    }

    private LlmFailure 실패(LlmSummaryOutcome outcome) {
        assertThat(outcome).isInstanceOf(LlmSummaryOutcome.Failure.class);
        return ((LlmSummaryOutcome.Failure) outcome).failure();
    }

    private void 응답하는_서버(int status, String body) throws IOException {
        시작한다(exchange -> 보낸다(exchange, status, body));
    }

    private AtomicReference<String> 본문을_받아두는_서버() throws IOException {
        AtomicReference<String> 받은본문 = new AtomicReference<>();
        시작한다(exchange -> {
            받은본문.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            보낸다(exchange, 200, "{\"summary\":\"세 줄 요약\",\"model\":\"gemma3:4b\"}");
        });
        return 받은본문;
    }

    private void 시작한다(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/llm/summary", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private void 보낸다(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
