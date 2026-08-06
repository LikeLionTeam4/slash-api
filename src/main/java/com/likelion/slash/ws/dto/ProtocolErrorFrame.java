package com.likelion.slash.ws.dto;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.ws.AgentProtocol;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 서버 → Agent 프로토콜 오류. (메시지 스펙 §6)
 *
 * <p>계약이 정한 오류 전달 수단이다. 소켓을 그냥 끊으면 Agent 는 이유를 알 수 없어
 * 재접속만 반복한다. 끊어야 하는 경우에도 이 프레임을 먼저 보내고 닫는다.
 *
 * @param code            {@link AgentProtocol} 의 {@code ERROR_*} 중 하나
 * @param relatedEventId  문제가 된 수신 메시지의 {@code eventId}. 없으면 null
 * @param closeConnection 이 오류로 연결을 닫는지. Agent 가 재접속 여부를 판단하는 근거다
 */
public record ProtocolErrorFrame(
        String schemaVersion,
        String type,
        UUID eventId,
        OffsetDateTime sentAt,
        String code,
        String message,
        UUID relatedEventId,
        boolean closeConnection) {

    public static ProtocolErrorFrame of(String code, String message, UUID relatedEventId, boolean closeConnection) {
        return new ProtocolErrorFrame(
                AgentProtocol.SCHEMA_VERSION,
                AgentProtocol.TYPE_PROTOCOL_ERROR,
                UUID.randomUUID(),
                SlashTime.now(),
                code,
                message,
                relatedEventId,
                closeConnection);
    }
}
