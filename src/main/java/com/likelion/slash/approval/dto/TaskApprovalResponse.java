package com.likelion.slash.approval.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.likelion.slash.jooq.tables.records.TaskApprovalsRecord;
import java.time.OffsetDateTime;

/**
 * 승인 요청의 현재 모습. {@code GET /api/v1/tasks/{taskId}} 에 실린다.
 *
 * <p>별도 WSS 이벤트를 두지 않는다. 상태가 {@code WAITING_FOR_APPROVAL} 로 바뀌는 것은
 * 이미 {@code TASK_STATUS_CHANGED} 로 나가므로, 화면은 그 신호를 받고 이 값을 읽으면 된다.
 * 이벤트를 하나 더 만들면 계약만 늘고 순서를 맞출 일이 생긴다.
 *
 * @param version   {@code If-Match} 에 넣을 값. 두 번 눌러도 한 번만 반영된다
 * @param expiresAt 이 시각이 지나면 만료로 마감된다
 * @param decidedAt 사람이 결정했을 때만 있다. 만료에는 없다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskApprovalResponse(
        String status,
        int version,
        OffsetDateTime expiresAt,
        OffsetDateTime decidedAt) {

    public static TaskApprovalResponse from(TaskApprovalsRecord approval) {
        return new TaskApprovalResponse(
                approval.getStatus(),
                approval.getVersion(),
                approval.getExpiresAt(),
                approval.getDecidedAt());
    }
}
