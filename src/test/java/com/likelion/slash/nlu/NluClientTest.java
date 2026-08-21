package com.likelion.slash.nlu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluDecision;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * {@link NluClient} 확인. (WBS W1-04)
 *
 * <p>Mock 이 아니라 <b>실제 HTTP 서버</b>를 띄워 확인한다. 여기서 검증하려는 것이 직렬화된
 * 본문의 모양 자체이기 때문이다 — 요청 객체를 그대로 들여다보면 Jackson 설정이 바뀌었을 때
 * 시험은 통과하는데 slash-nlu 는 400 을 돌려주는 상태가 된다.
 *
 * <p>가장 중요한 것은 <b>{@code text} 와 {@code command} 중 정확히 하나만 나가는 것</b>이다.
 * slash-nlu 의 {@code main.py} 는 둘 다 있거나 둘 다 없으면 400 으로 거부한다.
 */
class NluClientTest {

    private HttpServer server;
    private NluClient nluClient;

    /** 마지막으로 받은 요청 본문. 시험이 모양을 확인한다. */
    private final AtomicReference<String> 받은본문 = new AtomicReference<>();

    /** 마지막 요청의 {@code Content-Length}. 없으면 chunked 로 나갔다는 뜻이다. */
    private final AtomicReference<String> 받은길이 = new AtomicReference<>();

    private final AtomicInteger 응답코드 = new AtomicInteger(200);
    private final AtomicReference<String> 응답본문 = new AtomicReference<>();

    /**
     * 애플리케이션이 쓰는 설정을 그대로 맞춘 Mapper.
     *
     * <p>시각을 ISO 문자열로, 기준 시간대를 한국 시각으로 둔다. 이 설정이 아니면 {@code now} 가
     * epoch 숫자로 나가고 slash-nlu 가 400 으로 거부한다.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultTimeZone(TimeZone.getTimeZone(SlashTime.ZONE))
            .build();

    @BeforeEach
    void setUp() throws IOException {
        응답본문.set("""
                {
                  "requestId": "%s",
                  "decision": "TASK",
                  "taskType": "SYSTEM_STATUS",
                  "parameters": {},
                  "missingRequiredParameters": [],
                  "question": null,
                  "confidence": 1.0,
                  "analyzer": "SLASH"
                }
                """.formatted(UUID.randomUUID()));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/nlu/analyze", this::handle);
        server.start();

        nluClient = new NluClient(
                RestClient.builder(),
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        받은본문.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        받은길이.set(exchange.getRequestHeaders().getFirst("Content-Length"));

        byte[] body = 응답본문.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(응답코드.get(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @Test
    @DisplayName("슬래시 명령은 command 로 보내고 text 는 아예 넣지 않는다")
    void 슬래시_명령은_command_로_나간다() throws Exception {
        nluClient.analyze(UUID.randomUUID(), "/status", SlashTime.now());

        JsonNode sent = objectMapper.readTree(받은본문.get());
        assertThat(sent.has("text")).isFalse();
        assertThat(sent.path("command").path("path").get(0).asText()).isEqualTo("status");
        assertThat(sent.path("command").path("operands")).isEmpty();
    }

    @Test
    @DisplayName("명령 뒤는 통째로 하나의 operand 다")
    void 명령_뒤는_통째로_간다() throws Exception {
        nluClient.analyze(UUID.randomUUID(), "/file 보고서 지난주", SlashTime.now());

        JsonNode command = objectMapper.readTree(받은본문.get()).path("command");
        assertThat(command.path("path").get(0).asText()).isEqualTo("file");

        // 낱말로 쪼개면 원문의 줄바꿈과 연속 공백이 이 단계에서 사라진다. NLU 는 어차피
        // 이어 붙여 쓰므로 결과는 같고, 요약처럼 인자가 자유 텍스트인 명령만 달라진다.
        assertThat(command.path("operands")).hasSize(1);
        assertThat(command.path("operands").get(0).asText()).isEqualTo("보고서 지난주");
    }

    @Test
    @DisplayName("여러 줄 원문의 줄바꿈이 살아 있다")
    void 줄바꿈이_살아있다() throws Exception {
        // 이것이 낱말로 쪼개지 않는 이유다. 요약은 인자가 곧 내용이라, 여기서 줄바꿈을
        // 잃으면 NLU 가 다시 이어 붙여도 무엇으로 이어야 할지 알 수 없다. (slash-nlu#13)
        nluClient.analyze(UUID.randomUUID(), "/summary 첫 문단이다.\n\n둘째 문단이다.", SlashTime.now());

        JsonNode command = objectMapper.readTree(받은본문.get()).path("command");
        assertThat(command.path("operands").get(0).asText())
                .isEqualTo("첫 문단이다.\n\n둘째 문단이다.");
    }

    @Test
    @DisplayName("명령 이름은 줄바꿈으로도 끊긴다")
    void 이름은_줄바꿈으로도_끊긴다() throws Exception {
        // 원문을 줄바꿈으로 시작해 붙여넣는 경우가 있다. 이름과 내용이 붙어 버리면
        // 알 수 없는 명령이 된다.
        nluClient.analyze(UUID.randomUUID(), "/summary\n요약할 내용이다.", SlashTime.now());

        JsonNode command = objectMapper.readTree(받은본문.get()).path("command");
        assertThat(command.path("path").get(0).asText()).isEqualTo("summary");
        assertThat(command.path("operands").get(0).asText()).isEqualTo("요약할 내용이다.");
    }

    @Test
    @DisplayName("자연어는 text 로 보내고 command 는 아예 넣지 않는다")
    void 자연어는_text_로_나간다() throws Exception {
        nluClient.analyze(UUID.randomUUID(), "내 컴퓨터 상태 어때?", SlashTime.now());

        JsonNode sent = objectMapper.readTree(받은본문.get());
        assertThat(sent.has("command")).isFalse();
        assertThat(sent.path("text").asText()).isEqualTo("내 컴퓨터 상태 어때?");
    }

    @Test
    @DisplayName("이름 없는 슬래시는 자연어로 보낸다 — 빈 path 는 NLU 가 거부한다")
    void 이름_없는_슬래시는_자연어로_보낸다() throws Exception {
        nluClient.analyze(UUID.randomUUID(), "/  ", SlashTime.now());

        JsonNode sent = objectMapper.readTree(받은본문.get());
        assertThat(sent.has("command")).isFalse();
        assertThat(sent.path("text").asText()).isEqualTo("/");
    }

    @Test
    @DisplayName("기준 시각에 오프셋을 넣는다 — 없으면 NLU 가 거부한다")
    void 기준_시각에_오프셋을_넣는다() throws Exception {
        nluClient.analyze(UUID.randomUUID(), "/status", SlashTime.now());

        String now = objectMapper.readTree(받은본문.get()).path("now").asText();
        assertThat(now).matches(".*(\\+09:00|Z)$");
    }

    @Test
    @DisplayName("본문 길이를 명시해 보낸다 — chunked 로 나가지 않는다")
    void 본문_길이를_명시한다() {
        nluClient.analyze(UUID.randomUUID(), "/status", SlashTime.now());

        assertThat(받은길이.get()).isNotNull();
        assertThat(Integer.parseInt(받은길이.get()))
                .isEqualTo(받은본문.get().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("응답을 그대로 읽어 온다")
    void 응답을_읽어_온다() {
        NluAnalyzeResponse response = nluClient.analyze(UUID.randomUUID(), "/status", SlashTime.now());

        assertThat(response.decision()).isEqualTo(NluDecision.TASK);
        assertThat(response.taskType()).isEqualTo("SYSTEM_STATUS");
    }

    @Test
    @DisplayName("모르는 필드가 늘어도 실패하지 않는다")
    void 모르는_필드가_늘어도_읽는다() {
        응답본문.set("""
                {
                  "requestId": "x", "decision": "TASK", "taskType": "SYSTEM_STATUS",
                  "parameters": {}, "missingRequiredParameters": [], "question": null,
                  "confidence": 1.0, "analyzer": "SLASH",
                  "새로_생긴_필드": "값"
                }
                """);

        assertThat(nluClient.analyze(UUID.randomUUID(), "/status", SlashTime.now()).decision())
                .isEqualTo(NluDecision.TASK);
    }

    @Test
    @DisplayName("NLU 가 5xx 를 주면 NLU_UNAVAILABLE 로 바꾼다")
    void 오류_응답은_NLU_UNAVAILABLE_이다() {
        응답코드.set(500);
        응답본문.set("{\"detail\":\"NLU analysis failed\"}");

        assertThatThrownBy(() -> nluClient.analyze(UUID.randomUUID(), "/status", SlashTime.now()))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.NLU_UNAVAILABLE);
    }

    @Test
    @DisplayName("decision 이 없는 응답은 계약 위반으로 본다")
    void decision_없는_응답은_거부한다() {
        응답본문.set("{\"requestId\":\"x\",\"confidence\":1.0}");

        assertThatThrownBy(() -> nluClient.analyze(UUID.randomUUID(), "/status", SlashTime.now()))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.NLU_UNAVAILABLE);
    }

    @Test
    @DisplayName("NLU 가 없으면 재시도하지 않고 한 번만 부른다")
    void 재시도하지_않는다() {
        server.stop(0);

        assertThatThrownBy(() -> nluClient.analyze(UUID.randomUUID(), "/status", SlashTime.now()))
                .isInstanceOf(SlashException.class);

        assertThat(받은본문.get()).isNull();
    }
}
