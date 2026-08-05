package com.likelion.slash.common.enums;

/**
 * Outbox 사건이 발생한 원장의 종류. V006 의 {@code ck_outbox_aggregate_type} 과 같은 목록이다.
 *
 * <p>{@code outbox_events} 는 여러 표를 가리키므로 FK 를 두지 않는다.
 * 어떤 표의 어떤 행인지는 이 값과 {@code aggregate_id} 조합으로 판별한다.
 */
public enum OutboxAggregateType {

    /** {@code tasks} */
    TASK,

    /** {@code async_jobs} */
    ASYNC_JOB
}
