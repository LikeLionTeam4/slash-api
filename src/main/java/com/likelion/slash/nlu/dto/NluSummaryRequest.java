package com.likelion.slash.nlu.dto;

/**
 * CPU 추출 요약 요청. (slash-nlu {@code docs/EXTRACTIVE_SUMMARY_CONTRACT.md})
 *
 * <p>세 값 모두 비어 있으면 NLU 가 422 로 거부한다. 길이 제한은 NLU 가 판정한다 —
 * 공백 제외 150자 이상, 전체 8000자 이하. <b>여기서 미리 자르지 않는다.</b> 두 곳이
 * 같은 규칙을 들고 있으면 한쪽만 바뀌었을 때 조용히 어긋난다.
 *
 * @param requestId Task 의 {@code correlationId}
 * @param taskId    Task 의 공개 식별자
 */
public record NluSummaryRequest(String requestId, String taskId, String text) {
}
