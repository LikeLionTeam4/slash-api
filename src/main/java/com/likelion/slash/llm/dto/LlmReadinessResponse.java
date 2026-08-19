package com.likelion.slash.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET /ready} 응답. (slash-llm 의 {@code ReadinessResponse})
 *
 * <p>준비되지 않았으면 503 과 함께 온다. 본문의 {@code reason} 은 Ollama 에 닿지 못한 것인지
 * 모델이 없는 것인지를 가른다 — 둘 다 사용자에게는 같은 말이지만 로그에는 남길 값이다.
 *
 * @param status {@code ready} 또는 {@code not_ready}
 * @param reason {@code OLLAMA_UNAVAILABLE} 또는 {@code MODEL_NOT_FOUND}. 준비됐으면 비어 있다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmReadinessResponse(String status, String model, String reason) {

    private static final String READY = "ready";

    public boolean isReady() {
        return READY.equals(status);
    }
}
