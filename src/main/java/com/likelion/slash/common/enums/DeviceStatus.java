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

    /**
     * 새 작업을 전송할 수 있는지 확인한다. (문서 DV-05)
     *
     * <p><b>두 가지를 함께 본다.</b> 연결 상태만으로는 부족하다. 사용자가 PC 를 등록해 둔 채로
     * 작업 수신을 꺼 둘 수 있고({@code devices.accepting_tasks}), 그때는 붙어 있어도 보내지 않는다.
     * 둘을 따로 확인하면 한쪽을 빠뜨린 곳이 생긴다.
     *
     * @param acceptingTasks {@code devices.accepting_tasks} — 사용자가 켜 둔 수신 여부
     */
    public boolean canAcceptTask(boolean acceptingTasks) {
        return this == READY && acceptingTasks;
    }
}
