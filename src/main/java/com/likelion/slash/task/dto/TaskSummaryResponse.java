package com.likelion.slash.task.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.RequestSummary;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 이력 목록의 한 줄. ({@code GET /api/v1/tasks})
 *
 * <p><b>{@code result} 와 {@code parameters} 는 싣지 않는다.</b> 결과는 한 건에 64KB 까지
 * 허용되어 스무 줄이면 응답이 1MB 를 넘길 수 있는데, 목록 화면은 그 내용을 그리지 않는다.
 * 본문이 필요하면 {@code GET /api/v1/tasks/{taskId}} 로 한 건만 받는다.
 *
 * <p>내부 PK 는 나가지 않는다. 기기도 공개 식별자로만 싣는다.
 *
 * @param requestSummary  접수할 때 적어 둔 요약. 분석에 이르지 못하고 실패한 요청은 그 열이 비어
 *                        있어 원문에서 다시 만든다 — 목록에 빈 줄이 생기지 않게 하기 위해서다
 * @param taskType        분석 전이거나 분석에 실패했으면 없다
 * @param processingRoute 작업 유형에서 파생된 상수. 유형이 같으면 언제나 같은 값이다
 * @param executionTarget 실제로 실행한 주체. V013 이전에 접수된 작업은 비어 있다
 * @param deviceId        PC 를 거치지 않는 작업({@code /weather}·{@code /summary})은 없다
 * @param errorCode       실패·만료일 때만 있다
 * @param completedAt     끝난 작업만 있다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskSummaryResponse(
        UUID taskId,
        String status,
        String taskType,
        String processingRoute,
        String executionTarget,
        UUID deviceId,
        String requestSummary,
        String errorCode,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {

    /** @param devicePublicId 기기가 없는 작업이면 {@code null} */
    public static TaskSummaryResponse from(TasksRecord task, UUID devicePublicId) {
        String summary = task.getRequestSummary() != null
                ? task.getRequestSummary()
                : RequestSummary.of(task.getInputText());

        return new TaskSummaryResponse(
                task.getPublicId(),
                task.getStatus(),
                task.getTaskType(),
                task.getProcessingRoute(),
                task.getExecutionTarget(),
                devicePublicId,
                summary,
                task.getErrorCode(),
                task.getCreatedAt(),
                task.getCompletedAt());
    }
}
