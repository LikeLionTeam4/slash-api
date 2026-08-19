package com.likelion.slash.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code POST /internal/v1/llm/summary} 성공 응답. (slash-llm 의 {@code SummaryResponse})
 *
 * <p>평탄 JSON 이다. 공개 응답 봉투를 씌우고 Task 결과로 저장하는 것은 slash-api 몫이다.
 * (slash-llm {@code docs/BACKEND_CONTRACT.md})
 *
 * <p>slash-llm 이 필드를 먼저 늘려도 이쪽이 통째로 실패하지 않도록 모르는 필드는 무시한다.
 *
 * @param summary 요약문
 * @param model   추론에 쓴 모델 이름. {@code async_jobs.model} 에 남겨 성능을 추적한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmSummaryResponse(String summary, String model, String requestId, String taskId) {
}
