package com.likelion.slash.device;

import com.likelion.slash.auth.AuthenticatedUserService;
import com.likelion.slash.common.response.ApiResponse;
import com.likelion.slash.device.dto.DeviceListResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 등록된 PC 조회. (WBS W1-03 · slash-api #17)
 *
 * <p>지정 PC 관리 화면이 이것을 부른다. 이 경로가 없으면 화면은 방금 등록한 기기만 브라우저
 * 메모리에 들고 있게 되어, 새로고침하거나 다른 탭에서 열면 목록이 비어 버린다.
 *
 * <p>PC 등록은 {@link com.likelion.slash.pairing.PairingController} 다.
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final AuthenticatedUserService authenticatedUserService;

    public DeviceController(DeviceService deviceService,
                            AuthenticatedUserService authenticatedUserService) {
        this.deviceService = deviceService;
        this.authenticatedUserService = authenticatedUserService;
    }

    /** 내 PC 목록. 해제한 기기는 오지 않는다. */
    @GetMapping
    public ApiResponse<DeviceListResponse> devices() {
        long userId = authenticatedUserService.current().id();

        return ApiResponse.of(deviceService.findMyDevices(userId));
    }
}
