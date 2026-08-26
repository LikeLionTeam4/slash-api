package com.likelion.slash.task;

import com.likelion.slash.common.SlashTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보존 기간이 지난 멱등 기록을 지운다.
 *
 * <p><b>{@link IdempotencyRecordRepository#deleteExpired} 를 부르는 곳이 시험뿐이었다.</b>
 * 그래서 {@code expires_at} 이 하는 일이 아무것도 없었다 — 조회({@code find})도 재생
 * ({@code TaskService.replay})도 그 열을 보지 않으므로, <b>지우는 것이 곧 보존 기간의
 * 유일한 집행 수단</b>이다. 배치가 없는 동안에는 24시간이라는 값이 열에 적히기만 하고
 * 24시간 뒤에 달라지는 것이 없었다. ({@code PairingRequestSweeper} 와 같은 결)
 *
 * <p><b>지금 고장 나 있던 것은 아니다.</b> 멱등 키는 계약이 UUID v4 로 정하고 있어
 * (프론트엔드 연동 규약 §PC 등록·작업 접수) 같은 키가 다시 오는 일이 없다. 실제로 걸리던
 * 것은 <b>표와 인덱스가 무한히 자라는 것</b> 하나였다. {@code idx_idempotency_expires} 도
 * 이 배치를 위해 만들어 둔 것이라 쓰이는 곳이 없었다.
 *
 * <p><b>보존 기간을 여기서 더 늘리지 않는다.</b> {@code expires_at} 자체가 이미
 * "선점 시각 + 24시간"이라({@code TaskService.IDEMPOTENCY_RETENTION}) 그 시각이 지나면
 * 곧바로 지운다. {@code PairingRequestSweeper} 처럼 여유를 더 두면 계약이 말하는 24시간보다
 * 오래 재생이 살아 있게 된다 — 그쪽은 조회가 {@code expires_at} 을 직접 보기 때문에 행을
 * 늦게 지워도 동작이 달라지지 않지만, 여기는 다르다.
 *
 * <p><b>여러 Pod 이 동시에 돌아도 안전하다.</b> 조건에 맞는 행을 지우는 한 문장이라 누가
 * 먼저 하든 결과가 같고, 발행 같은 부수 효과가 없다.
 */
@Component
public class IdempotencyRecordSweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRecordSweeper.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public IdempotencyRecordSweeper(IdempotencyRecordRepository idempotencyRecordRepository) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }

    /**
     * 밀리초 단위로 받는 이유는 다른 주기 작업과 같다 — {@code @Scheduled} 의 문자열 값은
     * 설정 파일의 {@code 10m} 표기를 그대로 해석하지 못한다.
     */
    @Scheduled(
            fixedDelayString = "${slash.task.idempotency-sweep.interval-ms}",
            initialDelayString = "${slash.task.idempotency-sweep.interval-ms}")
    public void sweep() {
        try {
            int deleted = idempotencyRecordRepository.deleteExpired(SlashTime.now());

            if (deleted > 0) {
                log.info("멱등 기록 정리 삭제={}건", deleted);
            }

        } catch (Exception e) {
            // 한 회차의 실패로 스케줄이 멈추지 않게 한다. 다음 회차가 같은 일을 다시 시도한다.
            log.error("멱등 기록 정리 실패: {}", e.getMessage());
        }
    }
}
