package com.likelion.slash.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.nlu.dto.NluSummaryRequest;
import com.likelion.slash.nlu.dto.NluSummaryResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * slash-nlu 의 CPU 추출 요약을 부른다. (slash-docs#3 권장 순서 2·3번)
 *
 * <p><b>GPU 모델을 쓰지 않는다.</b> 원문에서 중요한 문장을 골라 오는 것이라 몇십 밀리초에
 * 끝난다. 그래서 요약을 맡기고 기다리는 원장({@code async_jobs})을 두지 않고 날씨처럼
 * 곧바로 부른다 — 이어받을 것이 없다.
 *
 * <p>{@link NluClient} 와 갈라 둔 이유는 시간 제한이다. 분석은 계약이 <b>합계 2초</b>로
 * 못박은 값이고, 요약은 원문 길이에 따라 더 걸릴 수 있어 같은 값을 쓸 수 없다.
 */
@Component
public class NluSummaryClient {

    private static final Logger log = LoggerFactory.getLogger(NluSummaryClient.class);

    private static final String SUMMARY_PATH = "/internal/v1/nlu/summaries/extractive";

    /** 연결에 쓰는 몫. 같은 Cluster 안이라 빠르게 되거나 아예 안 된다. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    /**
     * NLU 오류 코드를 우리 코드로 옮긴다.
     * (slash-nlu {@code docs/EXTRACTIVE_SUMMARY_CONTRACT.md} "입력 오류")
     *
     * <p>셋 다 {@code INVALID_PARAMETERS} 로 접는 것은 <b>계약을 늘리지 않기 위해서</b>다.
     * 사용자가 할 일은 안내 문구가 나누어 알려 준다.
     */
    private static final Map<String, String> MESSAGE_BY_NLU_CODE = Map.of(
            "INPUT_TOO_SHORT", "요약할 내용이 너무 짧습니다. 조금 더 길게 적어 주세요.",
            "INPUT_TOO_LONG", "요약할 내용이 너무 깁니다. 짧게 나누어 다시 시도해 주세요.",
            "INPUT_NOT_SUMMARIZABLE", "요약할 만한 문장을 찾지 못했습니다. 다른 글로 시도해 주세요.");

    private static final String FAILED = "요약하지 못했습니다. 잠시 뒤 다시 시도해 주세요.";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public NluSummaryClient(RestClient.Builder builder,
                            ObjectMapper objectMapper,
                            @Value("${slash.nlu.base-url}") String baseUrl,
                            @Value("${slash.nlu.summary-timeout}") Duration timeout) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) timeout.minus(CONNECT_TIMEOUT).toMillis());

        this.objectMapper = objectMapper;
        this.restClient = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * 원문을 요약한다.
     *
     * <p>길이 제한을 여기서 미리 확인하지 않는다. NLU 가 판정하고 이유를 코드로 돌려주므로,
     * 두 곳이 같은 규칙을 들고 있다가 한쪽만 바뀌는 일을 만들지 않는다.
     */
    public SummaryOutcome summarize(UUID requestId, UUID taskId, String text) {
        NluSummaryRequest request =
                new NluSummaryRequest(String.valueOf(requestId), String.valueOf(taskId), text);

        try {
            NluSummaryResponse response = restClient.post()
                    .uri(SUMMARY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsBytes(request))
                    .retrieve()
                    .body(NluSummaryResponse.class);

            if (response == null || response.summary() == null || response.summary().isBlank()) {
                log.warn("요약 응답이 비어 있다 taskId={}", taskId);
                return new SummaryOutcome.Failure(ErrorCode.UPSTREAM_UNAVAILABLE, FAILED);
            }
            return new SummaryOutcome.Success(response);

        } catch (RestClientResponseException e) {
            return fromErrorBody(taskId, e);

        } catch (Exception e) {
            // 시간 초과·연결 실패. 사용자에게는 모두 "요약하지 못했다" 이고 원인은 로그로 남긴다.
            log.warn("요약 호출 실패 taskId={}: {}", taskId, e.toString());
            return new SummaryOutcome.Failure(ErrorCode.UPSTREAM_UNAVAILABLE, FAILED);
        }
    }

    /**
     * 오류 봉투에서 이유를 꺼낸다. (slash-llm 과 같은 모양이다)
     *
     * <p>본문을 읽지 못해도 실패로 마감할 수 있어야 하므로 파싱 오류를 삼킨다 — 그 자리에서
     * 예외를 다시 던지면 원인이 "요약 실패" 가 아니라 "JSON 오류" 로 뒤바뀐다.
     */
    private SummaryOutcome.Failure fromErrorBody(UUID taskId, RestClientResponseException e) {
        String nluCode = null;
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsByteArray()).path("error");
            if (error.hasNonNull("code")) {
                nluCode = error.get("code").asText();
            }
        } catch (Exception ignored) {
            // 아래에서 알 수 없는 실패로 접는다.
        }

        // Map.of(...) 는 불변 Map 이라 get(null) 에서 NPE 를 던진다. 본문을 읽지 못하면
        // nluCode 가 비는데, 그 예외가 여기서 새어 나가면 "요약 실패" 로 마감하려던 것이
        // 500 으로 뒤바뀐다. (같은 결함을 SearchFolder·ProjectWorkspace 에서도 겪었다 · #54)
        String message = nluCode == null ? null : MESSAGE_BY_NLU_CODE.get(nluCode);
        if (message != null) {
            log.info("요약할 수 없는 입력 taskId={} code={}", taskId, nluCode);
            return new SummaryOutcome.Failure(ErrorCode.INVALID_PARAMETERS, message);
        }

        log.warn("요약이 알 수 없는 이유로 거부됐다 taskId={} status={} code={}",
                taskId, e.getStatusCode().value(), nluCode);
        return new SummaryOutcome.Failure(ErrorCode.UPSTREAM_UNAVAILABLE, FAILED);
    }
}
