package com.likelion.slash.pairing;

import com.likelion.slash.common.SlashTime;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 기한이 지난 등록 요청을 마감하고 오래된 것을 치운다. (이슈 #33)
 *
 * <p><b>기능이 고장 나 있던 것은 아니다.</b> 조회가 {@code expires_at} 을 직접 보므로 지난
 * 코드로 페어링이 되는 일은 없었다. 다만 {@link PairingRequestRepository#expireOverdue} 를
 * 부르는 곳이 시험뿐이라 <b>상태가 {@code PENDING} 인 채로 남고 행도 쌓였다.</b>
 * 등록 코드는 5분·1회용이라 시도할 때마다 한 줄씩 늘어난다.
 *
 * <p>두 가지를 한다.
 * <ul>
 *   <li>기한이 지난 {@code PENDING} 을 {@code EXPIRED} 로 바꾼다 — 표가 사실과 맞게 유지된다</li>
 *   <li>만료된 지 오래된 행을 지운다 — 표가 무한히 자라지 않는다</li>
 * </ul>
 *
 * <p><b>여러 Pod 이 동시에 돌아도 안전하다.</b> 조건에 맞는 행을 같은 값으로 바꾸거나 지우는
 * 한 문장이라 누가 먼저 하든 결과가 같다. 잠금을 두지 않는 이유는 {@code DeviceOfflineSweeper}
 * 와 같다. 발행 같은 부수 효과가 없어 두 번 실행되어도 달라지는 것이 없다.
 *
 * <p>주기가 촘촘할 이유가 없다. 늦게 치워도 잘못된 동작으로 이어지지 않는다.
 */
@Component
public class PairingRequestSweeper {

    private static final Logger log = LoggerFactory.getLogger(PairingRequestSweeper.class);

    private final PairingRequestRepository pairingRequestRepository;

    /** 만료된 뒤 이 시간이 지나면 행을 지운다. */
    private final Duration retention;

    public PairingRequestSweeper(PairingRequestRepository pairingRequestRepository,
                                 @Value("${slash.pairing.expiry-sweep.retention}") Duration retention) {
        this.pairingRequestRepository = pairingRequestRepository;
        this.retention = retention;
    }

    /**
     * 밀리초 단위로 받는 이유는 다른 주기 작업과 같다 — {@code @Scheduled} 의 문자열 값은
     * 설정 파일의 {@code 10m} 표기를 그대로 해석하지 못한다.
     */
    @Scheduled(
            fixedDelayString = "${slash.pairing.expiry-sweep.interval-ms}",
            initialDelayString = "${slash.pairing.expiry-sweep.interval-ms}")
    public void sweep() {
        try {
            int expired = pairingRequestRepository.expireOverdue(SlashTime.now());
            int deleted = pairingRequestRepository.deleteExpiredBefore(SlashTime.now().minus(retention));

            if (expired > 0 || deleted > 0) {
                log.info("등록 요청 정리 만료={}건 삭제={}건", expired, deleted);
            }

        } catch (Exception e) {
            // 한 회차의 실패로 스케줄이 멈추지 않게 한다. 다음 회차가 같은 일을 다시 시도한다.
            log.error("등록 요청 정리 실패: {}", e.getMessage());
        }
    }
}
