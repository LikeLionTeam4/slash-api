package com.likelion.slash.device;

import com.likelion.slash.device.dto.DeviceListResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 화면의 기기 관리. (WBS W1-03 · slash-api #17)
 *
 * <p>등록은 {@link com.likelion.slash.pairing.PairingService} 가, 연결 상태 갱신은
 * {@link com.likelion.slash.ws.AgentWebSocketHandler} 가 한다. 여기는 <b>사용자가 화면에서
 * 자기 PC 를 들여다보는 경로</b>다.
 *
 * <p>관련 문서: 3.4.4
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;

    public DeviceService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    /**
     * 내 PC 목록.
     *
     * <p><b>{@code status} 는 저장된 값을 그대로 내보낸다.</b> 조회 시점에 다시 계산하지 않는다.
     * 연결이 끊긴 기기를 내리는 것은 {@link DeviceOfflineSweeper} 의 몫이라, 여기서 또 판정하면
     * 같은 규칙이 두 곳에 생기고 목록과 작업 전달이 서로 다른 답을 내게 된다.
     *
     * <p>대신 판정이 스윕 주기만큼 늦을 수 있다. 최대 지연은
     * {@code offline-threshold + offline-sweep.interval} 이다. 정확한 시각이 필요하면
     * {@code lastSeenAt} 을 함께 보면 된다.
     */
    @Transactional(readOnly = true)
    public DeviceListResponse findMyDevices(long userId) {
        return DeviceListResponse.from(deviceRepository.findActiveByUserId(userId));
    }
}
