package com.likelion.slash.device;

import com.likelion.slash.auth.AuthenticatedUserService;
import com.likelion.slash.common.EntityTag;
import com.likelion.slash.common.response.ApiResponse;
import com.likelion.slash.device.dto.DeviceListResponse;
import com.likelion.slash.device.dto.DeviceResponse;
import com.likelion.slash.device.dto.TaskIntakeRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    /**
     * PC 등록을 해제한다. 붙어 있는 연결도 그 자리에서 끊는다. (#23)
     *
     * <p>본문이 없어 {@code 204} 로 답한다. 되돌릴 수 없는 요청이라 {@code If-Match} 를 요구한다 —
     * 다른 탭에서 재등록한 PC 를 낡은 화면이 해제해 버리는 것을 막는다.
     */
    @DeleteMapping("/{deviceId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID deviceId,
                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                       String ifMatch) {

        long userId = authenticatedUserService.current().id();
        deviceService.revoke(userId, deviceId, EntityTag.parseVersion(ifMatch));

        return ResponseEntity.noContent().build();
    }

    /**
     * 새 작업을 받을지 켜고 끈다. (#24)
     *
     * <p>해제와 다르다. 연결은 유지하고 작업 전달만 멈춘다. 그동안 접수된 요청은
     * {@code WAITING_FOR_DEVICE} 로 쌓였다가 다시 켜면 나간다.
     *
     * <p>바뀐 기기를 그대로 돌려준다. 화면이 목록을 다시 부르지 않아도 되고, 다음 수정에 쓸
     * {@code version} 도 이 응답에 들어 있다.
     */
    @PatchMapping("/{deviceId}/task-intake")
    public ApiResponse<DeviceResponse> setTaskIntake(@PathVariable UUID deviceId,
                                                     @Valid @RequestBody TaskIntakeRequest request,
                                                     @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                                     String ifMatch) {

        long userId = authenticatedUserService.current().id();

        return ApiResponse.of(deviceService.setTaskIntake(
                userId, deviceId, request.accepting(), EntityTag.parseVersion(ifMatch)));
    }
}
