package com.likelion.slash.nlu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.nlu.dto.NluAnalyzeRequest;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * slash-nlu 내부 API 호출. (WBS W1-04 · 문서 3.7.1)
 *
 * <p>계약 원본은 slash-nlu 의 {@code main.py}·{@code models.py} 이고, 연동 경계는
 * {@code docs/BACKEND_CONTRACT.md} 다.
 *
 * <p><b>자동 재시도를 하지 않는다.</b> 되묻기 없는 P0 요청은 사용자가 다시 누르는 것이
 * 가장 싸고, 재시도는 NLU 가 느려졌을 때 부하를 두 배로 만든다.
 *
 * <p><b>Namespace 를 코드에 고정하지 않는다.</b> 주소는 {@code NLU_BASE_URL} 로 주입받는다.
 */
@Component
public class NluClient {

    private static final Logger log = LoggerFactory.getLogger(NluClient.class);

    private static final String ANALYZE_PATH = "/internal/v1/nlu/analyze";

    /**
     * 연결에 쓰는 몫. 나머지는 응답을 기다리는 데 쓴다.
     *
     * <p>계약이 정한 것은 <b>합계 2초</b>다. 같은 Cluster 안이라 연결은 빠르게 되거나
     * 아예 안 되므로 짧게 자르고, 남은 시간을 Kiwi 분석에 준다.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper 애플리케이션 Mapper 를 <b>명시적으로</b> 넣는다. 기본 변환기에 맡기면
     *                     {@code now} 가 epoch 숫자로 나가 NLU 의 {@code utcoffset()} 검사에
     *                     걸린다. 계약이 요구하는 모양을 우연에 기대지 않는다.
     */
    public NluClient(RestClient.Builder builder,
                     ObjectMapper objectMapper,
                     @Value("${slash.nlu.base-url}") String baseUrl,
                     @Value("${slash.nlu.timeout}") Duration timeout) {

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
     * 사용자 입력 한 줄을 분석한다.
     *
     * <p>슬래시로 시작하면 {@code command} 로, 아니면 {@code text} 로 보낸다.
     * NLU 는 둘 중 <b>정확히 하나</b>만 받는다.
     *
     * @param requestId 작업 추적 식별자. Task 의 {@code correlationId} 를 그대로 쓴다.
     * @throws SlashException {@link ErrorCode#NLU_UNAVAILABLE} — 호출부가 Task 를 실패로 마감한다
     */
    public NluAnalyzeResponse analyze(UUID requestId, String inputText, OffsetDateTime now) {
        NluAnalyzeRequest request = toRequest(requestId, inputText, now);

        try {
            // 객체가 아니라 바이트로 넘긴다. 객체를 그대로 주면 길이를 모른 채 스트리밍으로
            // 나가 Transfer-Encoding: chunked 가 된다. FastAPI 는 받아 주지만, 앞단에 무엇이
            // 끼어도 안전하도록 길이를 명시한다.
            byte[] body = objectMapper.writeValueAsBytes(request);

            NluAnalyzeResponse response = restClient.post()
                    .uri(ANALYZE_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(NluAnalyzeResponse.class);

            if (response == null || response.decision() == null) {
                log.warn("NLU 응답에 decision 이 없다 requestId={}", requestId);
                throw new SlashException(ErrorCode.NLU_UNAVAILABLE);
            }
            return response;

        } catch (SlashException e) {
            throw e;
        } catch (Exception e) {
            // 시간 초과·연결 실패·4xx·5xx 를 구분하지 않는다. 사용자에게는 모두 "분석하지
            // 못했다" 이고, 원인은 로그로 남긴다.
            log.warn("NLU 호출 실패 requestId={}: {}", requestId, e.toString());
            throw new SlashException(ErrorCode.NLU_UNAVAILABLE);
        }
    }

    /**
     * 입력을 NLU 요청 형태로 가른다.
     *
     * <p>{@code /file 보고서 지난주} → {@code path=["file"]}, {@code operands=["보고서","지난주"]}
     *
     * <p>슬래시만 있고 이름이 없는 입력({@code "/"}, {@code "/ "})은 {@code path} 가 비어
     * NLU 가 거부하므로 자연어로 넘긴다. 그러면 {@code UNSUPPORTED} 로 정상 처리된다.
     */
    private NluAnalyzeRequest toRequest(UUID requestId, String inputText, OffsetDateTime now) {
        String trimmed = inputText.trim();

        if (!trimmed.startsWith("/")) {
            return NluAnalyzeRequest.ofText(requestId.toString(), trimmed, now);
        }

        List<String> tokens = new ArrayList<>(Arrays.asList(trimmed.substring(1).split("\\s+")));
        tokens.removeIf(String::isBlank);

        if (tokens.isEmpty()) {
            return NluAnalyzeRequest.ofText(requestId.toString(), trimmed, now);
        }

        String name = tokens.get(0);
        List<String> operands = tokens.subList(1, tokens.size());
        return NluAnalyzeRequest.ofCommand(requestId.toString(), List.of(name), List.copyOf(operands), now);
    }
}
