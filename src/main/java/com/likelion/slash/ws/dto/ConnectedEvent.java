package com.likelion.slash.ws.dto;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.ws.UserProtocol;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 서버 → 브라우저 접속 확인. (계약 {@code connectedEventSchema})
 *
 * <p>접속 직후 서버가 먼저 보낸다. 프론트는 이것을 받아야 연결이 선 것으로 본다 —
 * 소켓이 열린 것만으로는 접속표 검증을 통과했는지 알 수 없기 때문이다.
 *
 * @param connectionId 이 연결의 식별자. 장애 조사에서 로그를 잇는 데 쓴다.
 */
public record ConnectedEvent(
        String type,
        String connectionId,
        OffsetDateTime serverTime) {

    public static ConnectedEvent of(String connectionId) {
        return new ConnectedEvent(UserProtocol.TYPE_CONNECTED, connectionId, SlashTime.now());
    }

    public static ConnectedEvent create() {
        return of(UUID.randomUUID().toString());
    }
}
