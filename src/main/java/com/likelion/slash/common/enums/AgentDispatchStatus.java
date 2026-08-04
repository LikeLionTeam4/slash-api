package com.likelion.slash.common.enums;

/**
 * 로컬 에이전트 작업 전달(agent_dispatches)의 상태. 개발문서 2.4.4
 *
 * <p>Task 당 활성 전달은 한 건만 존재하며, 연결 복구 뒤 미완료 전달을 다시 보낸다. (문서 3.6.2)
 */
public enum AgentDispatchStatus {

    /** 대상 기기와 전달 정보 저장 */
    PENDING,

    /** WSS 로 전송 */
    DISPATCHED,

    /** Agent 수락 */
    ACKNOWLEDGED,

    /** 결과 반영 */
    COMPLETED,

    /** Agent 거부·실행 실패 */
    FAILED,

    /** 전달 기한 만료 */
    EXPIRED
}
