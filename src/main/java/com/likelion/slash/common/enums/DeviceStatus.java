package com.likelion.slash.common.enums;

/**
 * 등록된 PC(Device)의 상태. 개발문서 3.3.3 / 2.4.2
 */
public enum DeviceStatus {

    /** WSS 연결은 있으나 아직 작업 준비 확인 전 */
    ONLINE,

    /** 인증과 기능 보고가 끝나 작업을 받을 수 있음 */
    READY,

    /** 작업 한 건을 실행 중 (P0 는 기기당 동시 작업 1건) */
    BUSY,

    /** 연결 없음 또는 90초 동안 Heartbeat 없음 */
    OFFLINE,

    /** 사용자가 등록을 해제해 Token 이 무효 */
    REVOKED;

    /** 새 작업을 전송할 수 있는 상태인지 확인한다. (문서 DV-05) */
    public boolean canAcceptTask() {
        return this == READY;
    }
}
