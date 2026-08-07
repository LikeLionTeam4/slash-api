package com.likelion.slash.pairing.dto;

import java.util.UUID;

/**
 * {@code GET /api/v1/pairing-requests/{id}} 응답.
 *
 * <p>화면이 이 값을 주기적으로 조회해 "PC 를 찾는 중" 을 "등록 완료" 로 바꾼다.
 *
 * @param status   {@code PENDING} 또는 {@code CLAIMED}
 * @param deviceId 등록이 끝났으면 그 기기. 아직이면 null
 */
public record PairingStatusResponse(String status, UUID deviceId) {

    public static PairingStatusResponse pending() {
        return new PairingStatusResponse("PENDING", null);
    }

    public static PairingStatusResponse claimed(UUID deviceId) {
        return new PairingStatusResponse("CLAIMED", deviceId);
    }
}
