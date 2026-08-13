package com.likelion.slash.device.dto;

import com.likelion.slash.jooq.tables.records.DevicesRecord;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 등록된 PC 한 대. ({@code GET /api/v1/devices} · WBS W1-03)
 *
 * <p><b>공개 식별자만 내보낸다.</b> {@code devices.id} 는 서버 내부에서만 쓰고, 공개키·기기
 * Token 해시는 어떤 경로로도 나가지 않는다.
 *
 * @param deviceId     작업을 보낼 때 {@code selectedDeviceId} 로 쓰는 값
 * @param name         사용자가 붙인 이름. 등록 시점에 Agent 가 보고한 값이 기본이다
 * @param status       {@code READY}·{@code ONLINE}·{@code BUSY}·{@code OFFLINE}.
 *                     해제한 기기({@code REVOKED})는 목록에 오지 않는다
 * @param os           {@code WINDOWS} 또는 {@code MACOS}
 * @param osVersion    Agent 가 보고한 운영체제 버전. 없을 수 있다
 * @param agentVersion 그 PC 에 설치된 Agent 판. 없을 수 있다
 * @param lastSeenAt   마지막 Heartbeat 시각. 한 번도 연결된 적 없으면 null
 * @param registeredAt 등록 시각
 */
public record DeviceResponse(
        UUID deviceId,
        String name,
        String status,
        String os,
        String osVersion,
        String agentVersion,
        OffsetDateTime lastSeenAt,
        OffsetDateTime registeredAt) {

    public static DeviceResponse from(DevicesRecord device) {
        return new DeviceResponse(
                device.getPublicId(),
                device.getName(),
                device.getStatus(),
                device.getOs(),
                device.getOsVersion(),
                device.getAgentVersion(),
                device.getLastSeenAt(),
                device.getCreatedAt());
    }
}
