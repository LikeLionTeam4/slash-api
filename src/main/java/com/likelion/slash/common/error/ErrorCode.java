package com.likelion.slash.common.error;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

/**
 * 오류 코드. 메시지 프로토콜 정의 4.7 · 6.2 · 7.2 · 8.5 · 8.9
 *
 * <p>기본 메시지는 사용자에게 그대로 노출되므로 원인과 해결 방법을 담는다.
 * Token·API Key·개인키·절대 경로 같은 민감값은 넣지 않는다.
 *
 * <p>Agent·LLM 이 보낸 오류 코드도 이 열거형으로 정규화해 {@code tasks.error_code} 에 저장한다.
 * 그 경우의 HTTP 상태는 사용자가 작업을 조회할 때 참고할 값이다.
 */
public enum ErrorCode {

    // ---------------------------------------------------------------------
    // 인증·권한
    // ---------------------------------------------------------------------

    /** 사용자·기기·서비스 인증이 필요함 */
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),

    /** 권한 또는 소유권 부족 */
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),

    /** 기기 토큰 또는 도전값 서명 검증 실패 */
    AGENT_AUTH_FAILED(HttpStatus.UNAUTHORIZED, "기기 인증에 실패했습니다."),

    /**
     * 등록이 해제된 기기.
     *
     * <p>{@link #FORBIDDEN} 과 나눠 두는 이유는 <b>Agent 가 이 둘에 다르게 반응해야 하기
     * 때문</b>이다. 권한 문제라면 다시 시도할 여지가 있지만 해제는 그렇지 않다 — 재시도나
     * 재등록을 반복할 것이 아니라 멈추고 사용자에게 알려야 한다. 같은 코드로 묶어 두면
     * Agent 가 그것을 가릴 방법이 없다. (이슈 #26)
     */
    DEVICE_REVOKED(HttpStatus.FORBIDDEN, "PC 등록이 해제되었습니다. 다시 등록해 주세요."),

    /** 등록 코드가 틀렸거나 만료됨 */
    PAIRING_CODE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "등록 코드가 올바르지 않거나 만료되었습니다."),

    // ---------------------------------------------------------------------
    // 입력·자원
    // ---------------------------------------------------------------------

    /** 입력값 형식·범위 오류 */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "요청 값을 확인해 주세요."),

    /** 작업 입력값의 자료형·길이·허용값 위반 */
    INVALID_PARAMETERS(HttpStatus.UNPROCESSABLE_ENTITY, "작업에 필요한 입력값이 올바르지 않습니다."),

    /**
     * 대상 자원을 찾을 수 없음.
     * 다른 사용자가 소유한 자원도 식별자 추측을 막기 위해 404 로 응답한다.
     */
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "대상을 찾을 수 없습니다."),

    /** If-Match 의 ETag 와 현재 자원 버전이 다름 */
    RESOURCE_VERSION_MISMATCH(HttpStatus.PRECONDITION_FAILED,
            "다른 곳에서 먼저 수정되었습니다. 새로고침 후 다시 시도해 주세요."),

    /** 같은 멱등키에 다른 요청 본문 사용 */
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "같은 요청 키로 다른 내용이 전송되었습니다."),

    // ---------------------------------------------------------------------
    // 기기·작업 유형
    // ---------------------------------------------------------------------

    /** 선택 PC 가 READY 가 아님 */
    DEVICE_NOT_READY(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 PC가 작업을 받을 수 없습니다."),

    /** 선택 PC 가 다른 작업 실행 중 (P0 는 기기당 동시 1건) */
    DEVICE_BUSY(HttpStatus.CONFLICT, "선택한 PC가 다른 작업을 실행 중입니다."),

    /** 설치된 Agent 가 해당 작업 유형을 지원하지 않음 */
    TASK_TYPE_NOT_SUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "이 PC에서 지원하지 않는 기능입니다."),

    /** 작업 실행 기한 만료 */
    TASK_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "작업 실행 기한이 지났습니다."),

    /**
     * 무슨 요청인지 알아내지 못함. (NLU {@code decision=UNSUPPORTED})
     *
     * <p>{@link #TASK_TYPE_NOT_SUPPORTED} 와 다르다 — 저쪽은 요청은 알아들었는데 그 PC 가
     * 못 하는 경우이고, 이쪽은 요청 자체를 알아듣지 못한 경우다.
     *
     * <p>이름은 참조 구현({@code mock-api/src/taskOrchestrator.ts})이 쓰는 값을 그대로 따른다.
     */
    UNRECOGNIZED_COMMAND(HttpStatus.UNPROCESSABLE_ENTITY, "무엇을 도와드릴지 알아내지 못했습니다."),

    // ---------------------------------------------------------------------
    // Agent 가 보고하는 실행 실패 (RESULT.error.code / ACK.reasonCode)
    // ---------------------------------------------------------------------

    /** 검색 폴더가 없거나 사용할 수 없음 */
    SEARCH_FOLDER_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 검색 폴더를 사용할 수 없습니다."),

    /**
     * 열려던 파일을 찾지 못함. ({@code FILE_OPEN})
     *
     * <p>검색 결과를 받은 뒤 파일이 옮겨지거나 지워졌을 때다. PC 실행기는 실행 직전에 다시
     * 확인하므로, 목록에 보이던 파일도 누를 때는 없을 수 있다.
     */
    FILE_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "그 파일을 찾지 못했습니다. 목록을 새로 고쳐 주세요."),

    /** 프로젝트 작업 폴더가 없거나 사용할 수 없음 (P1) */
    WORKSPACE_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "선택한 프로젝트 폴더를 사용할 수 없습니다."),

    /** Claude·Codex 연동 설정이 없음 (P1) */
    CODE_AGENT_NOT_CONFIGURED(HttpStatus.UNPROCESSABLE_ENTITY, "이 PC에 AI 도구 연동이 설정되어 있지 않습니다."),

    /** 경로·권한·보안 정책 위반 */
    POLICY_DENIED(HttpStatus.FORBIDDEN, "허용되지 않은 경로 또는 작업입니다."),

    /**
     * 사용자가 실행을 거절했다. (P0-C · 계획 문서 §1.5)
     *
     * <p>실패이지만 잘못된 것은 없다. 화면이 오류처럼 붉게 보여 주면 사용자는 자기가 한
     * 선택을 문제로 읽는다.
     */
    APPROVAL_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "실행하지 않았습니다."),

    /**
     * Agent 가 작업 자체를 받지 않음. ({@code ACK.accepted=false} 인데 사유가 없거나 모르는 값)
     *
     * <p>이름은 참조 구현({@code mock-api/src/taskOrchestrator.ts})의 fallback 을 그대로 따른다.
     */
    AGENT_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY, "PC가 작업을 받지 않았습니다."),

    /** Agent 가 실행에 실패했는데 사유가 없거나 모르는 값. (참조 구현의 fallback) */
    AGENT_TASK_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "PC에서 작업을 끝내지 못했습니다."),

    // ---------------------------------------------------------------------
    // 외부·내부 서비스
    // ---------------------------------------------------------------------

    /** NLU 연결 실패·통신 안전 Timeout·응답 계약 위반 */
    NLU_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "요청을 분석하지 못했습니다. 잠시 후 다시 시도해 주세요."),

    /** GPU Worker 가 작업을 받을 준비가 되지 않음 */
    LLM_NOT_READY(HttpStatus.SERVICE_UNAVAILABLE, "AI 모델이 아직 준비되지 않았습니다."),

    /** 날씨 API 등 외부 서비스 이용 불가 */
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "외부 서비스를 이용할 수 없습니다."),

    /**
     * 날씨를 조회할 지역을 찾지 못함.
     *
     * <p>{@code UPSTREAM_UNAVAILABLE} 과 나누는 이유 — 사용자가 할 수 있는 일이 다르다.
     * 외부 서비스가 멈춘 것은 기다리는 수밖에 없지만, 지역을 못 찾은 것은 다르게 말하면 된다.
     */
    LOCATION_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "그 지역의 날씨를 찾지 못했습니다."),

    // ---------------------------------------------------------------------
    // 프로토콜
    // ---------------------------------------------------------------------

    /** 지원하지 않는 메시지 contract 버전 (WSS PROTOCOL_ERROR) */
    UNSUPPORTED_SCHEMA_VERSION(HttpStatus.UNPROCESSABLE_ENTITY, "지원하지 않는 메시지 버전입니다."),

    /** 분류되지 않은 내부 오류 */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "처리 중 문제가 발생했습니다.");

    /** Agent 가 보고할 수 있는 사유. 계약({@code contracts/src/enums.ts})의 {@code REASON_CODES} 와 같다. */
    private static final Set<ErrorCode> AGENT_REPORTABLE = EnumSet.of(
            DEVICE_BUSY,
            TASK_TYPE_NOT_SUPPORTED,
            INVALID_PARAMETERS,
            SEARCH_FOLDER_NOT_FOUND,
            FILE_NOT_FOUND,
            WORKSPACE_NOT_FOUND,
            CODE_AGENT_NOT_CONFIGURED,
            TASK_EXPIRED,
            POLICY_DENIED);

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

    /**
     * Agent 가 보고한 사유 코드를 우리 코드로 옮긴다. ({@code ACK.reasonCode} · {@code RESULT.error.code})
     *
     * <p>계약의 {@code REASON_CODES} 여덟 개는 이름이 그대로 겹치므로 그대로 받는다.
     * (원본은 slash-agent 의 {@code contracts/src/enums.ts})
     *
     * <p><b>목록 밖의 값은 받지 않는다.</b> 이름만 맞으면 무엇이든 통과시키면 Agent 가
     * {@code AUTH_REQUIRED} 같은 값을 보내 작업을 엉뚱한 사유로 마감시킬 수 있다.
     * 모르는 값과 빈 값은 모두 {@code fallback} 으로 접는다 — Agent 가 새 사유 코드를 먼저
     * 배포하더라도 마감 자체가 실패하면 안 된다.
     */
    public static ErrorCode fromAgentReason(String reason, ErrorCode fallback) {
        if (reason == null || reason.isBlank()) {
            return fallback;
        }
        try {
            ErrorCode parsed = valueOf(reason.trim());
            return AGENT_REPORTABLE.contains(parsed) ? parsed : fallback;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
