package com.likelion.slash.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.llm.dto.LlmErrorResponse;
import com.likelion.slash.llm.dto.LlmSummaryRequest;
import com.likelion.slash.llm.dto.LlmSummaryResponse;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * slash-llm 내부 API 호출. (문서 3.7 · slash-llm {@code docs/BACKEND_CONTRACT.md})
 *
 * <p>계약 원본은 slash-llm 의 {@code main.py} 이고 연동 경계는 그 저장소의
 * {@code docs/BACKEND_CONTRACT.md} 다.
 *
 * <p><b>NLU 호출과 성격이 다르다.</b> 분석은 사용자가 응답을 기다리는 동안 끝나야 해서 2초로
 * 자르지만, 요약은 이미 {@code QUEUED} 로 응답한 뒤 뒤에서 도는 일이라 모델이 생각할 시간을
 * 넉넉히 준다. 사용자는 그동안 화면에서 진행 상태를 본다.
 *
 * <p><b>slash-llm 보다 먼저 끊지 않는다.</b> 먼저 끊으면 그쪽이 만들어 준 {@code MODEL_TIMEOUT}
 * 을 받지 못해 원장에 {@code UPSTREAM_ERROR} 만 남고, 오래 걸린 것인지 닿지 못한 것인지
 * 구분되지 않는다. 짧게 끊는다고 GPU 가 쉬지도 않는다 — slash-llm 은 Ollama 를
 * {@code stream:false} 로 부르므로 우리가 기다리길 그만두어도 생성은 끝까지 돈다.
 * ({@code slash.llm.timeout} 이 그쪽 {@code LLM_TIMEOUT} 보다 커야 하는 이유다 · PR #42 리뷰)
 *
 * <p><b>자동 재시도를 하지 않는다.</b> 되돌려주는 {@link LlmFailure#retryable()} 을 원장에 남겨
 * 두면 다시 시도할지는 스윕과 나중의 SQS 정책이 정한다. 여기서 즉시 다시 부르면 모델이
 * 밀려 있을 때 부하만 늘린다.
 *
 * <p><b>Namespace 를 코드에 고정하지 않는다.</b> 주소는 {@code LLM_BASE_URL} 로 주입받는다.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final String SUMMARY_PATH = "/internal/v1/llm/summary";

    /** 연결에 쓰는 몫. 같은 Cluster 안이라 빠르게 되거나 아예 안 된다. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public LlmClient(RestClient.Builder builder,
                     ObjectMapper objectMapper,
                     @Value("${slash.llm.base-url}") String baseUrl,
                     @Value("${slash.llm.timeout}") Duration timeout) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) timeout.minus(CONNECT_TIMEOUT).toMillis());

        this.objectMapper = objectMapper;
        this.restClient = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .messageConverters(converters -> {
                    converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
                    converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                })
                .build();
    }

    /**
     * 긴 글을 요약한다.
     *
     * @param correlationId Task 의 추적 식별자. 계약상 {@code requestId} 로 나간다.
     * @param taskId        Task 의 공개 식별자
     * @return 성공이면 요약문과 모델 이름, 실패면 원인. <b>예외를 던지지 않는다.</b>
     */
    public LlmSummaryOutcome summarize(UUID correlationId, UUID taskId, String text) {
        LlmSummaryRequest request =
                new LlmSummaryRequest(text, correlationId.toString(), taskId.toString());

        long startedAt = System.nanoTime();
        try {
            // 객체가 아니라 바이트로 넘긴다. 이유는 NluClient 와 같다 — 길이를 명시해
            // 앞단에 무엇이 끼어도 chunked 로 나가지 않게 한다.
            byte[] body = objectMapper.writeValueAsBytes(request);

            LlmSummaryResponse response = restClient.post()
                    .uri(SUMMARY_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(LlmSummaryResponse.class);

            int elapsed = elapsedMilliseconds(startedAt);

            if (response == null || response.summary() == null || response.summary().isBlank()) {
                log.warn("요약 응답이 비어 있다 taskId={}", taskId);
                return new LlmSummaryOutcome.Failure(LlmFailure.of("INVALID_MODEL_RESPONSE", false));
            }
            return new LlmSummaryOutcome.Success(response, elapsed);

        } catch (RestClientResponseException e) {
            // slash-llm 이 이유를 담아 4xx·5xx 로 답한 경우다. 그 코드를 살려 원장에 남긴다.
            return new LlmSummaryOutcome.Failure(toFailure(taskId, e));

        } catch (Exception e) {
            // 시간 초과·연결 실패. 응답 자체가 없어 코드를 알 수 없다.
            log.warn("요약 호출 실패 taskId={}: {}", taskId, e.toString());
            return new LlmSummaryOutcome.Failure(LlmFailure.unreachable());
        }
    }

    /**
     * 오류 응답의 본문에서 코드를 꺼낸다.
     *
     * <p>본문을 읽지 못하면 알 수 없는 실패로 접는다. 상태 코드만으로 짐작하지 않는 이유는
     * 같은 503 이 {@code MODEL_BUSY} 와 {@code MODEL_UNAVAILABLE} 둘 다이기 때문이다.
     */
    private LlmFailure toFailure(UUID taskId, RestClientResponseException e) {
        try {
            LlmErrorResponse body = objectMapper.readValue(e.getResponseBodyAsByteArray(), LlmErrorResponse.class);
            if (body.error() != null && body.error().code() != null) {
                log.info("요약 실패 taskId={} code={} retryable={}",
                        taskId, body.error().code(), body.error().retryable());
                return LlmFailure.of(body.error().code(), body.error().retryable());
            }
        } catch (Exception ignored) {
            // 계약과 다른 본문이다. 아래에서 알 수 없는 실패로 다룬다.
        }

        log.warn("요약 오류 응답을 해석하지 못했다 taskId={} status={}", taskId, e.getStatusCode());
        return LlmFailure.unreachable();
    }

    private int elapsedMilliseconds(long startedAt) {
        return (int) ((System.nanoTime() - startedAt) / 1_000_000);
    }
}
