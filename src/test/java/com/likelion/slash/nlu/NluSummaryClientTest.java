package com.likelion.slash.nlu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link NluSummaryClient} 확인.
 *
 * <p>여기서 지키는 것은 <b>거부 이유를 사용자가 할 수 있는 일로 옮기는가</b>이다.
 * 세 가지 입력 오류는 사용자가 고칠 수 있고(더 쓰기·줄이기·다른 글), 그 밖의 실패는
 * 기다리는 수밖에 없다. 둘을 같은 말로 안내하면 사용자가 할 수 있는 일을 가린다.
 *
 * <p>응답 본문은 slash-nlu 에서 실제로 받은 것을 그대로 쓴다.
 * ({@code docs/EXTRACTIVE_SUMMARY_CONTRACT.md})
 *
 * <p><b>{@code MockRestServiceServer} 를 쓰지 않는다.</b> 이 클라이언트는 시간 제한을 걸려고
 * {@code requestFactory} 를 직접 지정하는데, 그 목은 자기 factory 를 꽂는 방식이라 서로
 * 덮어쓴다. 실제 HTTP 를 태우면 그 문제가 없고 시간 초과까지 함께 확인할 수 있다.
 */
class NluSummaryClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 지정한 응답만 돌려주는 서버를 띄우고, 그것을 보는 클라이언트를 만든다. */
    private NluSummaryClient 서버가(int status, String body) throws IOException {
        return 서버가(status, body, Duration.ZERO, Duration.ofSeconds(5));
    }

    private NluSummaryClient 서버가(int status, String body, Duration 지연, Duration 제한) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/nlu/summaries/extractive", exchange -> {
            try {
                if (!지연.isZero()) {
                    Thread.sleep(지연.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        return new NluSummaryClient(RestClient.builder(), new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(), 제한);
    }

    private SummaryOutcome 요약한다(NluSummaryClient client) {
        return client.summarize(UUID.randomUUID(), UUID.randomUUID(), "요약할 글");
    }

    @Test
    @DisplayName("고른 문장과 무엇으로 요약했는지를 함께 받는다")
    void 성공을_옮긴다() throws IOException {
        SummaryOutcome outcome = 요약한다(서버가(200, """
                {"requestId":"r","taskId":"t","summary":"고른 문장.","engine":"EXTRACTIVE",
                 "algorithm":"TFIDF_CENTROID","algorithmVersion":"1",
                 "inputSentenceCount":8,"outputSentenceCount":3,"durationMs":18}
                """));

        assertThat(outcome).isInstanceOf(SummaryOutcome.Success.class);
        var response = ((SummaryOutcome.Success) outcome).response();
        assertThat(response.summary()).isEqualTo("고른 문장.");
        assertThat(response.engine()).isEqualTo("EXTRACTIVE");
        assertThat(response.algorithm()).isEqualTo("TFIDF_CENTROID");
    }

    @Test
    @DisplayName("너무 짧은 입력은 사용자가 고칠 수 있는 오류로 옮긴다")
    void 짧은_입력을_옮긴다() throws IOException {
        // slash-nlu 에서 실제로 받은 응답이다.
        var failure = (SummaryOutcome.Failure) 요약한다(서버가(400, """
                {"error":{"code":"INPUT_TOO_SHORT","message":"요약할 내용은 공백 제외 150자 이상이어야 합니다.",
                 "retryable":false},"requestId":"r","taskId":"t"}
                """));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETERS);

        // NLU 의 문구를 그대로 노출하지 않는다. 사용자가 할 일을 우리 말로 옮긴다.
        assertThat(failure.message()).contains("길게");
        assertThat(failure.message()).doesNotContain("150");
    }

    @Test
    @DisplayName("너무 긴 입력도 사용자가 고칠 수 있는 오류다")
    void 긴_입력을_옮긴다() throws IOException {
        var failure = (SummaryOutcome.Failure) 요약한다(서버가(400, """
                {"error":{"code":"INPUT_TOO_LONG","message":"요약할 내용은 8000자를 넘을 수 없습니다.",
                 "retryable":false},"requestId":"r","taskId":"t"}
                """));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETERS);
        assertThat(failure.message()).contains("깁니다");
    }

    @Test
    @DisplayName("요약할 문장이 없는 입력도 사용자가 고칠 수 있는 오류다")
    void 요약할_수_없는_입력을_옮긴다() throws IOException {
        var failure = (SummaryOutcome.Failure) 요약한다(서버가(400, """
                {"error":{"code":"INPUT_NOT_SUMMARIZABLE","message":"...","retryable":false}}
                """));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.INVALID_PARAMETERS);
        assertThat(failure.message()).contains("문장을 찾지 못했습니다");
    }

    @Test
    @DisplayName("서비스 쪽 실패는 기다리라고 안내한다")
    void 서비스_실패는_기다리게_한다() throws IOException {
        var failure = (SummaryOutcome.Failure) 요약한다(서버가(500, "{}"));

        // 사용자가 입력을 고쳐도 달라지지 않는 실패다. 입력 오류와 같은 말로 안내하면
        // 사용자가 원문을 고치며 헤매게 된다.
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(failure.message()).contains("잠시 뒤");
    }

    @Test
    @DisplayName("오류 본문을 읽지 못해도 실패로 마감할 수 있다")
    void 본문이_깨져도_마감한다() throws IOException {
        // 본문 파싱에서 예외가 새어 나가면 원인이 "요약 실패" 가 아니라 "JSON 오류" 로 뒤바뀐다.
        var failure = (SummaryOutcome.Failure) 요약한다(서버가(400, "{깨진 본문"));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("모르는 오류 코드는 알 수 없는 실패로 접는다")
    void 모르는_코드는_접는다() throws IOException {
        // NLU 가 코드를 먼저 늘려도 사용자가 빈 화면을 보지 않아야 한다.
        var failure = (SummaryOutcome.Failure) 요약한다(서버가(400, """
                {"error":{"code":"SOMETHING_NEW","message":"...","retryable":false}}
                """));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("요약이 비어 있으면 성공으로 보지 않는다")
    void 빈_요약은_실패다() throws IOException {
        // 화면에 빈 결과를 그리느니 실패로 마감하는 편이 낫다.
        assertThat(요약한다(서버가(200, """
                {"requestId":"r","taskId":"t","summary":"   ","engine":"EXTRACTIVE"}
                """))).isInstanceOf(SummaryOutcome.Failure.class);
    }

    @Test
    @DisplayName("시간이 지나면 기다리지 않고 마감한다")
    void 시간_초과를_마감한다() throws IOException {
        // 사용자를 무한정 기다리게 두지 않는다. 접수 응답이 그만큼 늦어진다.
        var failure = (SummaryOutcome.Failure) 요약한다(
                서버가(200, "{}", Duration.ofSeconds(3), Duration.ofMillis(700)));

        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        assertThat(failure.message()).contains("잠시 뒤");
    }
}
