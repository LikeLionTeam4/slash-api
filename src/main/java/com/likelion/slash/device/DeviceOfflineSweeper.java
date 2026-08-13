package com.likelion.slash.device;

import com.likelion.slash.common.SlashTime;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Heartbeat 가 끊긴 기기를 {@code OFFLINE} 으로 내린다. (문서 DV-05 · WBS W1-03)
 *
 * <p><b>연결이 끊긴 것을 아무도 알려주지 않는다.</b> 사용자가 PC 를 그냥 끄면 소켓이 닫히는
 * 신호조차 오지 않을 수 있고, 서버 Pod 이 갑자기 죽으면 그 Pod 이 들고 있던 기기의 상태를
 * 되돌릴 주체가 사라진다. 어느 쪽이든 {@code devices.status} 는 마지막 값 그대로 남는다.
 *
 * <p>그래서 <b>마지막 Heartbeat 시각</b>을 기준으로 판정한다. 끊긴 이유가 무엇이든 하트비트는
 * 멈추므로, {@code slash.device.offline-threshold} 만큼 소식이 없으면 연결이 없는 것으로 본다.
 *
 * <p>이것이 없으면 두 가지가 함께 틀린다.
 * <ul>
 *   <li>꺼진 PC 가 {@code READY} 로 남아 작업이 그리로 전달된다. Agent 가 없으니 아무 응답도
 *       오지 않고 작업은 기한이 다할 때까지 매달려 있다</li>
 *   <li>기기 목록이 꺼진 PC 를 켜져 있다고 보여준다</li>
 * </ul>
 *
 * <p><b>여러 Pod 이 동시에 돌아도 안전하다.</b> 조건에 맞는 행을 같은 값으로 갱신하는
 * 한 문장이라 누가 먼저 하든 결과가 같다. 잠금을 두지 않는 이유다.
 * (재발행 스윕은 발행이라는 부수 효과가 있어 잠금이 필요했다 — {@code PendingDispatchSweeper})
 */
@Component
public class DeviceOfflineSweeper {

    private static final Logger log = LoggerFactory.getLogger(DeviceOfflineSweeper.class);

    private final DeviceRepository deviceRepository;

    /** 이 시간 동안 Heartbeat 가 없으면 연결이 끊긴 것으로 본다. (30초 × 3회 누락) */
    private final Duration offlineThreshold;

    public DeviceOfflineSweeper(DeviceRepository deviceRepository,
                                @Value("${slash.device.offline-threshold}") Duration offlineThreshold) {
        this.deviceRepository = deviceRepository;
        this.offlineThreshold = offlineThreshold;
    }

    /**
     * 밀리초 단위로 받는 이유 — {@code @Scheduled} 의 문자열 값은 설정 파일의 {@code 30s} 표기를
     * 그대로 해석하지 못한다. {@link com.likelion.slash.dispatch.PendingDispatchSweeper} 와 같다.
     */
    @Scheduled(
            fixedDelayString = "${slash.device.offline-sweep.interval-ms}",
            initialDelayString = "${slash.device.offline-sweep.interval-ms}")
    public void markStaleDevicesOffline() {
        try {
            int marked = deviceRepository.markOfflineWhenHeartbeatStale(
                    SlashTime.now().minus(offlineThreshold));

            if (marked > 0) {
                log.info("Heartbeat 가 끊긴 기기 {}대를 OFFLINE 으로 내렸다", marked);
            }

        } catch (Exception e) {
            // 한 회차의 실패로 스케줄이 멈추지 않게 한다. 다음 회차가 같은 일을 다시 시도한다.
            log.error("기기 오프라인 스윕 실패: {}", e.getMessage());
        }
    }
}
