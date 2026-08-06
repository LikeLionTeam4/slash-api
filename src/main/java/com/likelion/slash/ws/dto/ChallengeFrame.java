package com.likelion.slash.ws.dto;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.ws.AgentProtocol;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 서버 → Agent 도전값. (메시지 스펙 §3)
 *
 * <p>Agent 는 {@code challengeId}·{@code nonce}·자기 {@code deviceId} 를 이어 붙인 문자열에
 * 개인키로 서명해 AUTH 로 돌려준다.
 *
 * @param challengeId 이 도전값의 식별자. AUTH 가 같은 값을 들고 와야 한다.
 * @param nonce       Base64 임의값. 서명 대상 문자열에는 <b>이 문자열 그대로</b> 들어간다.
 * @param expiresAt   이 시각이 지나면 받아 주지 않는다.
 */
public record ChallengeFrame(
        String schemaVersion,
        String type,
        UUID eventId,
        OffsetDateTime sentAt,
        UUID challengeId,
        String nonce,
        OffsetDateTime expiresAt) {

    public static ChallengeFrame of(UUID challengeId, String nonce, OffsetDateTime expiresAt) {
        return new ChallengeFrame(
                AgentProtocol.SCHEMA_VERSION,
                AgentProtocol.TYPE_CHALLENGE,
                UUID.randomUUID(),
                SlashTime.now(),
                challengeId,
                nonce,
                expiresAt);
    }
}
