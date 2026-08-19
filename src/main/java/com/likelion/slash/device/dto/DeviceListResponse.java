package com.likelion.slash.device.dto;

import com.likelion.slash.jooq.tables.records.DeviceSearchFoldersRecord;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import java.util.List;
import java.util.Map;

/**
 * 내 PC 목록. ({@code GET /api/v1/devices} · WBS W1-03)
 *
 * <p>해제한 기기는 오지 않는다. {@link com.likelion.slash.device.DeviceRepository#findActiveByUserId}
 * 를 참고한다.
 */
public record DeviceListResponse(List<DeviceResponse> devices) {

    /**
     * @param foldersByDeviceId 기기 PK 별 검색 폴더. 한 번도 연결된 적 없는 기기는 열쇠가 없다
     */
    public static DeviceListResponse from(List<DevicesRecord> devices,
                                          Map<Long, List<DeviceSearchFoldersRecord>> foldersByDeviceId) {
        return new DeviceListResponse(devices.stream()
                .map(device -> DeviceResponse.from(
                        device, foldersByDeviceId.getOrDefault(device.getId(), List.of())))
                .toList());
    }
}
