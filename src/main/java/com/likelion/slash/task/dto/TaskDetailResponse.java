package com.likelion.slash.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code GET /api/v1/tasks/{taskId}} 응답. (WBS W1-04)
 *
 * <p>프론트는 접수 응답의 {@code statusUrl} 을 이 형태로 폴링해 결과를 받는다.
 *
 * <p>내부 PK 는 넣지 않는다. 기기도 {@code public_id} 로만 노출한다.
 *
 * @param processingRoute 작업 유형에서 파생된 상수. 유형이 같으면 언제나 같은 값이다
 * @param executionTarget 실제로 실행한 주체. V013 이전에 접수된 작업은 비어 있다
 * @param question 되물어야 할 때 보여줄 말. {@code NEEDS_CLARIFICATION} 이 아니면 비어 있다.
 *                 별도 열을 두지 않고 상태 전이 기록의 설명을 그대로 쓴다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskDetailResponse(
        UUID taskId,
        String status,
        String taskType,
        String processingRoute,
        String executionTarget,
        UUID deviceId,
        String inputText,
        JsonNode parameters,
        JsonNode result,
        String errorCode,
        String question,
        UUID correlationId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt) {
}
