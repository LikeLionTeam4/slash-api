package com.likelion.slash.common.enums;

/**
 * 사용자가 내리는 결정. {@code POST /api/v1/tasks/{taskId}/approval} 이 받는 값이다.
 *
 * <p>{@link ApprovalStatus} 와 갈라 둔 이유 — 그쪽에는 사용자가 고를 수 없는 값
 * ({@code PENDING}·{@code EXPIRED})이 있다. 요청 본문에 그 값이 오는 것을 막으려면
 * 받는 목록이 따로 있어야 한다.
 */
public enum ApprovalDecision {

    APPROVE,
    REJECT;

    public ApprovalStatus toStatus() {
        return this == APPROVE ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
    }
}
