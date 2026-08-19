package com.likelion.slash.llm;

import com.likelion.slash.common.error.ErrorCode;
import java.util.Map;

/**
 * slash-llm 이 돌려준 실패. 호출부가 원장과 Task 양쪽에 남길 수 있는 모양으로 옮겨 담는다.
 *
 * <p>예외로 던지지 않고 값으로 돌려주는 이유 — 요약 실패는 <b>정상적인 결말 중 하나</b>다.
 * 사용자에게는 "요약하지 못했다"가 보이고 {@code async_jobs} 에는 원인 코드가 남아야 한다.
 * 예외로 만들면 그 두 가지를 호출부에서 다시 풀어내야 한다.
 *
 * @param code      slash-llm 의 오류 코드. {@code async_jobs.error_code} 에 그대로 남는다.
 * @param retryable 같은 작업을 다시 처리할 수 있는지. SQS 로 옮길 때 DLQ 정책의 근거가 된다.
 * @param errorCode 사용자에게 보일 우리 쪽 코드
 * @param message   사용자에게 보일 말
 */
public record LlmFailure(String code, boolean retryable, ErrorCode errorCode, String message) {

    /**
     * 계약이 권한 변환표. (slash-llm {@code docs/BACKEND_CONTRACT.md} "오류 변환 권장안")
     *
     * <p>여기 없는 코드는 알 수 없는 실패로 보고 {@code UPSTREAM_UNAVAILABLE} 로 접는다.
     * slash-llm 이 코드를 먼저 늘려도 사용자가 빈 화면을 보지 않게 한다.
     */
    private static final Map<String, ErrorCode> BY_LLM_CODE = Map.of(
            "INPUT_TOO_SHORT", ErrorCode.INVALID_PARAMETERS,
            "MODEL_BUSY", ErrorCode.LLM_NOT_READY,
            "MODEL_UNAVAILABLE", ErrorCode.LLM_NOT_READY,
            "MODEL_TIMEOUT", ErrorCode.UPSTREAM_UNAVAILABLE,
            "UPSTREAM_ERROR", ErrorCode.UPSTREAM_UNAVAILABLE,
            "INVALID_MODEL_RESPONSE", ErrorCode.UPSTREAM_UNAVAILABLE);

    /** 사용자에게 보일 말. slash-llm 의 문구를 그대로 노출하지 않는다. */
    private static final String TOO_SHORT = "요약할 내용이 너무 짧습니다.";
    private static final String NOT_READY = "요약 모델이 아직 준비되지 않았습니다. 잠시 뒤 다시 시도해 주세요.";
    private static final String FAILED = "요약하지 못했습니다. 잠시 뒤 다시 시도해 주세요.";

    public static LlmFailure of(String llmCode, boolean retryable) {
        ErrorCode mapped = BY_LLM_CODE.getOrDefault(llmCode, ErrorCode.UPSTREAM_UNAVAILABLE);
        return new LlmFailure(llmCode, retryable, mapped, messageFor(mapped));
    }

    /**
     * 응답을 받지 못한 경우. 시간 초과·연결 실패가 여기 해당한다.
     *
     * <p>다시 부르면 될 수 있으므로 {@code retryable} 이다.
     */
    public static LlmFailure unreachable() {
        return new LlmFailure("UPSTREAM_ERROR", true, ErrorCode.UPSTREAM_UNAVAILABLE, FAILED);
    }

    private static String messageFor(ErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_PARAMETERS -> TOO_SHORT;
            case LLM_NOT_READY -> NOT_READY;
            default -> FAILED;
        };
    }
}
