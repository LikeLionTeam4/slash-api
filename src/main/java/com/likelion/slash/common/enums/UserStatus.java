package com.likelion.slash.common.enums;

/**
 * 사용자 계정 상태. V001 의 {@code ck_users_status} 와 같은 목록이다.
 *
 * <p>로그인 자체는 Cognito 가 담당하므로 이 값은 서비스 이용 가능 여부만 나타낸다.
 */
public enum UserStatus {

    /** 정상 이용 */
    ACTIVE,

    /** 관리자가 이용을 정지 */
    SUSPENDED,

    /** 탈퇴. 감사 기록 보존을 위해 행은 남긴다. */
    DELETED
}
