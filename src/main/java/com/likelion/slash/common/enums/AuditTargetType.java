package com.likelion.slash.common.enums;

/**
 * 감사 기록의 대상 자원 종류. V007 의 {@code ck_audit_events_target_type} 과 같은 목록이다.
 *
 * <p>대상은 내부 PK 가 아니라 {@code public_id} 로 가리킨다.
 * 원본 행이 지워져도 기록이 남아야 하기 때문에 FK 를 두지 않는다.
 */
public enum AuditTargetType {

    /** {@code users.public_id} */
    USER,

    /** {@code devices.public_id} */
    DEVICE,

    /** {@code tasks.public_id} */
    TASK,

    /** {@code device_pairing_requests.public_id} */
    PAIRING_REQUEST
}
