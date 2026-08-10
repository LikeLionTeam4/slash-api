package com.likelion.slash.pairing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * {@code POST /api/v1/agent/pair/verify} 요청. (메시지 스펙 §8.1 2단계)
 *
 * @param signature {@code challengeId:nonce:deviceId} 문자열에 대한 Ed25519 서명(Base64)
 */
public record AgentPairVerifyRequest(
        @NotNull UUID pairingSessionId,
        @NotNull UUID challengeId,
        @NotBlank String signature) {
}
