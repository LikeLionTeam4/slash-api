package com.likelion.slash.nlu.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * CPU 추출 요약 결과. (slash-nlu {@code docs/EXTRACTIVE_SUMMARY_CONTRACT.md})
 *
 * <p>선택된 문장은 점수 순이 아니라 <b>원문 순서</b>로 온다.
 *
 * <p>NLU 는 {@code executionTarget} 이나 {@code processingRoute} 를 돌려주지 않는다.
 * 실행 위치는 slash-api 가 정한다. (slash-docs#3)
 *
 * @param engine           언제나 {@code EXTRACTIVE}. 같은 {@code BACKEND} 라도 Gemma 와
 *                         구분되도록 결과에 남긴다
 * @param algorithm        고른 방식. 지금은 {@code TFIDF_CENTROID}
 * @param algorithmVersion 같은 방식이라도 결과가 달라질 수 있어 함께 남긴다
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NluSummaryResponse(
        String requestId,
        String taskId,
        String summary,
        String engine,
        String algorithm,
        String algorithmVersion,
        Integer inputSentenceCount,
        Integer outputSentenceCount,
        Integer durationMs) {
}
