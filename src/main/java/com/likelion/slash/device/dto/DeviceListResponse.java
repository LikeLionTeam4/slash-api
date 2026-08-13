package com.likelion.slash.device.dto;

import com.likelion.slash.jooq.tables.records.DevicesRecord;
import java.util.List;

/**
 * 등록된 PC 목록 응답. ({@code GET /api/v1/devices} · WBS W1-03)
 *
 * <p>배열을 그대로 내보내지 않고 객체로 감싼다. 나중에 등록 한도 같은 값을 나란히 붙일 때
 * 응답 형태를 바꾸지 않아도 된다. {@code TaskTypeCatalogResponse} 와 같은 이유다.
 *
 * @param devices 최근 등록한 것이 앞에 온다. 해제한 기기는 들어 있지 않다
 */
public record DeviceListResponse(List<DeviceResponse> devices) {

    public static DeviceListResponse from(List<DevicesRecord> devices) {
        return new DeviceListResponse(devices.stream().map(DeviceResponse::from).toList());
    }
}
