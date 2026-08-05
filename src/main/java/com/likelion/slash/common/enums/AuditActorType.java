package com.likelion.slash.common.enums;

/**
 * 감사 기록에서 사건을 일으킨 주체. V007 의 {@code ck_audit_events_actor_type} 과 같은 목록이다.
 */
public enum AuditActorType {

    /** 사용자의 REST 요청 */
    USER,

    /** 로컬 에이전트의 WSS 프레임 */
    AGENT,

    /** slash-nlu·slash-llm 등 내부 서비스 호출 */
    SERVICE,

    /** 만료 처리 배치 같은 자동 실행 */
    SYSTEM
}
