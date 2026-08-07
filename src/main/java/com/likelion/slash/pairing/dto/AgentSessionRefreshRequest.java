package com.likelion.slash.pairing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code POST /api/v1/agent/sessions/refresh} 요청. (메시지 스펙 §8.1 3단계)
 *
 * <p><b>기존 Token 을 제시하는 방식이 아니다.</b> 매번 새 {@code refreshNonce} 에 대한 서명으로
 * 개인키 보유를 다시 증명한다. Token 만으로 재발급하면 훔친 Token 이 무한히 연장된다.
 *
 * @param requestedAt 재전송 공격을 막기 위한 시각. 허용 범위를 벗어나면 거부한다
 * @param signature   {@code deviceId:refreshNonce:requestedAt} 에 대한 Ed25519 서명(Base64)
 */
public record AgentSessionRefreshRequest(
        @NotNull UUID deviceId,
        @NotNull UUID refreshNonce,
        @NotNull OffsetDateTime requestedAt,
        @NotBlank String signature) {
}
