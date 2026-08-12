package com.likelion.slash.ws.dto;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.ws.UserProtocol;
import java.time.OffsetDateTime;

/**
 * 서버 → 브라우저 PING 응답. (계약 {@code pongEventSchema})
 *
 * <p>브라우저가 연결이 살아 있는지 확인하는 용도다. 중간 프록시가 조용한 연결을 끊는 것을
 * 막는 역할도 한다.
 */
public record PongEvent(String type, OffsetDateTime sentAt) {

    public static PongEvent create() {
        return new PongEvent(UserProtocol.TYPE_PONG, SlashTime.now());
    }
}
