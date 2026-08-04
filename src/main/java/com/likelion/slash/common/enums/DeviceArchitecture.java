package com.likelion.slash.common.enums;

/**
 * Agent 가 동작하는 CPU 아키텍처. 메시지 프로토콜 정의 8.1
 *
 * <p>등록 시 Agent 가 필수로 보고하며, 실행 패키지와 플랫폼 호환성 판정에 사용한다.
 */
public enum DeviceArchitecture {
    X86_64,
    ARM64
}
