package com.likelion.slash.device.dto;

import com.likelion.slash.jooq.tables.records.DeviceSearchFoldersRecord;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import java.time.OffsetDateTime;
import java.util.List;
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
 * @param acceptingTasks 사용자가 새 작업 수신을 켜 두었는지. 거짓이면 연결돼 있어도 작업을
 *                       보내지 않는다. 연결 해제와 달리 되돌릴 수 있다 (#24)
 * @param lastSeenAt   마지막 Heartbeat 시각. 한 번도 연결된 적 없으면 null
 * @param registeredAt 등록 시각
 * @param version      수정 요청의 {@code If-Match} 에 넣을 값. 그 사이 다른 탭에서 먼저 바뀌었으면
 *                     412 로 거절된다. Heartbeat 로 인한 상태 변화는 이 값을 올리지 않는다
 * @param searchFolders 그 PC 가 검색 대상으로 등록해 둔 폴더. Agent 가 READY 로 보고한 값이며,
 *                      한 번도 연결된 적이 없으면 비어 있다. 파일 검색 결과의
 *                      {@code searchFolderId} 를 사람이 읽을 이름으로 바꾸는 데 쓴다 (이슈 #25)
 */
public record DeviceResponse(
        UUID deviceId,
        String name,
        String status,
        String os,
        String osVersion,
        String agentVersion,
        boolean acceptingTasks,
        OffsetDateTime lastSeenAt,
        OffsetDateTime registeredAt,
        int version,
        List<SearchFolderResponse> searchFolders) {

    public static DeviceResponse from(DevicesRecord device, List<DeviceSearchFoldersRecord> folders) {
        return new DeviceResponse(
                device.getPublicId(),
                device.getName(),
                device.getStatus(),
                device.getOs(),
                device.getOsVersion(),
                device.getAgentVersion(),
                Boolean.TRUE.equals(device.getAcceptingTasks()),
                device.getLastSeenAt(),
                device.getCreatedAt(),
                device.getVersion(),
                folders.stream().map(SearchFolderResponse::from).toList());
    }
}
