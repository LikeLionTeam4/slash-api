package com.likelion.slash.common.enums;

/**
 * PC 등록 코드(device_pairing_requests)의 상태. 개발문서 DV-01
 *
 * <p>시간이 지나도 상태가 저절로 바뀌지는 않는다. 만료된 {@link #PENDING} 행이 남아 있으면
 * 사용자별 활성 코드 부분 UNIQUE 제약 때문에 새 코드를 발급할 수 없으므로,
 * 발급 서비스가 같은 트랜잭션에서 기존 활성 코드를 먼저 {@link #EXPIRED} 로 바꿔야 한다.
 */
public enum PairingStatus {

    /** 발급 후 아직 사용되지 않음 */
    PENDING,

    /** Agent 가 코드를 제출해 기기 등록이 끝남 */
    COMPLETED,

    /** 5분 경과 또는 새 코드 발급으로 무효화됨 */
    EXPIRED
}
