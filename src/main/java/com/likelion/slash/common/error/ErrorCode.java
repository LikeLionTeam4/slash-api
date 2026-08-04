package com.likelion.slash.common.error;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드. 개발문서 3.3.6 의 15개와 3.1.4 의 HTTP 상태 코드를 짝지은 것이다.
 *
 * <p>기본 메시지는 사용자에게 그대로 노출되므로 원인과 해결 방법을 담는다.
 * Token·API Key·전체 경로 같은 민감값을 넣지 않는다. (문서 OB-02)
 */
public enum ErrorCode {

    /** 사용자 또는 기기 인증이 필요함 */
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),

    /** 권한 또는 소유권 부족 */
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    /** 입력값 형식·범위 오류 */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요."),

    /**
     * 대상 자원을 찾을 수 없음.
     * 다른 사용자가 소유한 자원도 식별자 추측을 막기 위해 404 로 응답한다. (문서 3.2.3)
     */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),

    /** If-Match 의 ETag 와 현재 자원 버전이 다름 */
    RESOURCE_VERSION_MISMATCH(HttpStatus.PRECONDITION_FAILED,
            "다른 곳에서 먼저 수정되었습니다. 새로고침 후 다시 시도해 주세요."),

    /** 같은 멱등키에 다른 요청 본문 사용 */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 요청 키로 다른 내용이 전송되었습니다."),

    /** 선택 PC 가 READY 가 아님 */
    DEVICE_NOT_READY(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 PC가 작업을 받을 수 없습니다."),

    /** 선택 PC 가 다른 작업 실행 중 */
    DEVICE_BUSY(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 PC가 다른 작업을 실행 중입니다."),

    /** 등록 코드가 틀렸거나 만료됨 */
    PAIRING_CODE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "등록 코드가 올바르지 않거나 만료되었습니다."),

    /** 기기 Token 또는 도전값 서명 검증 실패 */
    AGENT_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "기기 인증에 실패했습니다."),

    /** GPU Worker 가 시험 준비 상태가 아님 */
    MODEL_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "AI 모델이 아직 준비되지 않았습니다."),

    /** NLU 시간 제한·연결 실패·응답 계약 위반으로 분석할 수 없음 */
    NLU_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "요청을 분석하지 못했습니다. 잠시 후 다시 시도해 주세요."),

    /** 날씨 API 등 외부 서비스 이용 불가 */
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "외부 서비스를 이용할 수 없습니다."),

    /** 작업 실행 기한 만료 */
    TASK_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "작업 실행 기한이 지났습니다."),

    /** 분류되지 않은 내부 오류 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "처리 중 문제가 발생했습니다.");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
