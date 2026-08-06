package com.likelion.slash.ws.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.likelion.slash.ws.WsTarget;
import java.time.OffsetDateTime;

/**
 * Pod 사이를 오가는 봉투. Valkey Pub/Sub 채널에 실리는 값이다.
 *
 * <p>slash-api 는 최소 2 Pod 로 운영되는데 WSS 연결은 특정 Pod 에만 있다.
 * 이벤트를 만든 Pod 이 연결을 갖고 있지 않을 수 있으므로 전체 Pod 에 발행하고,
 * 연결을 보유한 Pod 만 {@code frame} 을 소켓으로 내보낸다. (docs/w1-06-wss-routing.md)
 *
 * <p><b>이 봉투는 소켓으로 나가지 않는다.</b> Agent·브라우저가 보는 것은 {@code frame} 뿐이다.
 * 라우팅 정보를 클라이언트에 노출하지 않기 위해 봉투를 벗겨서 보낸다.
 *
 * @param target   대상 종류
 * @param targetId 대상 내부 PK ({@code devices.id} 또는 {@code users.id}).
 *                 외부 노출용 {@code public_id} 가 아니다. 이 값은 Pod 사이에서만 돈다.
 * @param frame    소켓으로 그대로 내보낼 프레임
 * @param issuedAt 발행 시각 (한국 시각)
 * @param originPod 발행한 Pod. 추적용이며 라우팅 판단에는 쓰지 않는다.
 */
public record WsEnvelope(
        WsTarget target,
        long targetId,
        JsonNode frame,
        OffsetDateTime issuedAt,
        String originPod) {
}
