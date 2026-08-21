package com.likelion.slash.common.enums;

/**
 * 승인 요청의 상태. V014 의 {@code ck_task_approvals_status} 와 같은 목록이다.
 *
 * <p>작업 상태({@link TaskStatus})와 나란히 움직이지만 같지 않다. 작업은 승인을 받은 뒤
 * 실행까지 이어지므로 상태가 계속 바뀌는데, 승인은 <b>한 번 결정되면 끝</b>이다.
 */
public enum ApprovalStatus {

    /** 사용자 답을 기다리는 중. 기한이 지나면 스윕이 만료로 마감한다. */
    PENDING,

    APPROVED,
    REJECTED,

    /** 기한 안에 답하지 않았다. */
    EXPIRED;

    public boolean isDecided() {
        return this != PENDING;
    }
}
