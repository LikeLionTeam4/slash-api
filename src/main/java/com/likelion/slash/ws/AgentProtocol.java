package com.likelion.slash.ws;

import java.util.UUID;

/**
 * Agent WSS 메시지 계약의 고정값. (메시지 스펙 §3 · 3.4.2 · WBS W1-06)
 *
 * <p>계약 원본은 slash-runner 의 {@code slash-python-pc-runner/src/slash_pc_runner/protocol.py} 다.
 * 값을 바꿀 일이 생기면 그쪽과 함께 고쳐야 한다. 여기에만 고치면 조용히 어긋난다.
 *
 * <p>과거에는 원본이 {@code contracts/src/agentMessages.ts}(zod 스키마)였고 참조 구현이
 * {@code slash-api/mock-api/src/agentWss.ts} 였다. 실행기가 Electron/TypeScript 에서
 * Python 으로 재작성되면서 둘 다 사라졌다 — 지금 그 경로를 찾으면 없다.
 *
 * <p><b>공통 필드</b> — 모든 메시지는 {@code schemaVersion}·{@code eventId}·{@code sentAt} 을
 * 반드시 포함한다. Agent 는 zod 로 검증하므로 하나라도 빠지면 메시지 전체가 거부된다.
 */
public final class AgentProtocol {

    /** 이 값이 다르면 Agent 도 서버도 메시지를 거부한다. */
    public static final String SCHEMA_VERSION = "1.0";

    // 메시지 종류
    public static final String TYPE_HELLO = "HELLO";
    public static final String TYPE_CHALLENGE = "CHALLENGE";
    public static final String TYPE_AUTH = "AUTH";
    public static final String TYPE_READY = "READY";
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    public static final String TYPE_TASK = "TASK";
    public static final String TYPE_ACK = "ACK";
    public static final String TYPE_PROGRESS = "PROGRESS";
    public static final String TYPE_RESULT = "RESULT";
    public static final String TYPE_RESULT_ACK = "RESULT_ACK";
    public static final String TYPE_PROTOCOL_ERROR = "PROTOCOL_ERROR";

    // RESULT.status
    public static final String RESULT_SUCCEEDED = "SUCCEEDED";

    // PROTOCOL_ERROR.code — 계약이 정한 목록 밖의 값을 보내면 Agent 가 거부한다.
    public static final String ERROR_UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION";
    public static final String ERROR_INVALID_MESSAGE = "INVALID_MESSAGE";
    public static final String ERROR_AUTHENTICATION_FAILED = "AUTHENTICATION_FAILED";
    public static final String ERROR_CHALLENGE_EXPIRED = "CHALLENGE_EXPIRED";
    public static final String ERROR_INVALID_CONNECTION_STATE = "INVALID_CONNECTION_STATE";
    public static final String ERROR_DEVICE_REVOKED = "DEVICE_REVOKED";

    /**
     * PROTOCOL_ERROR 로 끊을 때 쓰는 WebSocket 종료 코드.
     *
     * <p>4000~4999 는 응용이 정할 수 있는 구간이다. 종료 사유는 {@code CloseStatus} 의 이유
     * 문자열에 {@code code} 를 그대로 실어 보낸다.
     */
    public static final int CLOSE_CODE_PROTOCOL_ERROR = 4400;

    /** 도전값 유효 시간. 참조 구현과 같은 30초. */
    public static final long CHALLENGE_TTL_SECONDS = 30;

    private AgentProtocol() {
    }

    /**
     * 도전값 서명 대상 문자열. (메시지 스펙 §3)
     *
     * <p>{@code challengeId + ":" + nonce + ":" + deviceId} 의 UTF-8 바이트에 서명한다.
     * <b>nonce 는 Base64 문자열 그대로</b> 쓴다. 디코딩한 원본 바이트가 아니다.
     * 이 둘을 헷갈리면 서명이 항상 어긋나고, 원인은 로그에 드러나지 않는다.
     */
    public static String challengeSigningPayload(UUID challengeId, String nonce, UUID deviceId) {
        return challengeId + ":" + nonce + ":" + deviceId;
    }
}
