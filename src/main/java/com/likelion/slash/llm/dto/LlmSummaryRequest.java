package com.likelion.slash.llm.dto;

/**
 * {@code POST /internal/v1/llm/summary} 요청. (slash-llm {@code main.py} 의 {@code SummaryRequest})
 *
 * @param text      요약할 원문. 길이 판정은 slash-llm 이 한다 — 짧으면 {@code INPUT_TOO_SHORT} 로 돌아온다.
 * @param requestId 추적용. Task 의 {@code correlationId} 를 그대로 쓴다. (계약 "ID 전파")
 * @param taskId    추적용. Task 의 {@code publicId}.
 */
public record LlmSummaryRequest(String text, String requestId, String taskId) {
}
