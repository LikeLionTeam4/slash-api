package com.likelion.slash.pairing;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 페어링 도중의 도전값 상태. (메시지 스펙 §8.1 1~2단계)
 *
 * <p>{@code POST /agent/pair} 가 만들고 {@code POST /agent/pair/verify} 가 소비한다.
 * 30초만 살아 있고 한 번 쓰면 사라진다.
 *
 * <p><b>Pod 메모리에 두면 안 된다.</b> pair 와 verify 가 서로 다른 Pod 으로 갈 수 있어서,
 * 메모리에 두면 두 요청이 같은 Pod 에 닿았을 때만 우연히 성공한다.
 * Valkey 에 두는 이유가 이것이다. (문서 3.8 이 허용하는 "상태 공유" 용도)
 *
 * @param deviceId       방금 만든 기기의 내부 PK
 * @param devicePublicId 서명 대상 문자열에 들어가는 외부 식별자
 * @param publicKey      Agent 가 보낸 공개키. 아직 소유가 증명되지 않았다
 */
public record PairingSession(
        UUID pairingSessionId,
        long pairingRequestId,
        long deviceId,
        UUID devicePublicId,
        UUID challengeId,
        String nonce,
        String publicKey,
        OffsetDateTime expiresAt) {
}
