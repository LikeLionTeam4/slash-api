package com.likelion.slash.pairing.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code POST /api/v1/agent/pair} 응답. (메시지 스펙 §8.1 1단계)
 *
 * <p>이 시점의 기기는 아직 소유가 증명되지 않았다. Token 도 주지 않는다.
 * Agent 는 {@code challengeId:nonce:deviceId} 에 서명해 verify 로 증명한다.
 */
public record AgentPairResponse(
        UUID pairingSessionId,
        UUID deviceId,
        UUID challengeId,
        String nonce,
        OffsetDateTime expiresAt) {
}
