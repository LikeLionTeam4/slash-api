package com.likelion.slash.pairing.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code POST /api/v1/pairing-requests} 응답. (메시지 스펙 §4.1)
 *
 * <p>{@code pairingCode} 는 이 응답에서 딱 한 번 나간다. 저장하는 것은 해시뿐이라
 * 사용자가 놓치면 다시 알려줄 수 없고 새로 발급해야 한다.
 */
public record PairingCodeResponse(
        UUID pairingRequestId,
        String pairingCode,
        OffsetDateTime expiresAt) {
}
