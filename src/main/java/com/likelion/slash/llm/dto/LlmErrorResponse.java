package com.likelion.slash.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * slash-llm 오류 응답. (slash-llm 의 {@code ErrorResponse})
 *
 * <p>{@code retryable} 은 같은 작업을 다시 처리할 수 있는지를 뜻하고
 * {@code async_jobs.retryable} 에 그대로 남는다. 나중에 SQS 로 옮길 때 DLQ 정책의 근거가 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmErrorResponse(Detail error, String requestId, String taskId) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Detail(String code, String message, boolean retryable) {
    }
}
