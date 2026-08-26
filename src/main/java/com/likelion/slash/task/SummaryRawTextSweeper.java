package com.likelion.slash.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 성공했는데 원문이 남아 있는 요약을 치운다. (slash-docs#3 · 원문 기본 미저장)
 *
 * <p><b>배포 롤링 창을 메우는 것이 목적이다.</b> 신규 건은 성공 마감과 같은 트랜잭션에서
 * 지우고 과거 행은 {@code V015} 가 한 번 맞췄지만, <b>마이그레이션이 돈 뒤에도 옛 Pod 이
 * 잠시 트래픽을 받는다.</b> 그 사이에 접수된 요약은 옛 코드로 처리되어 원문이 남고, 그
 * 회차의 마이그레이션은 이미 지나갔으므로 아무도 치우지 않는다.
 *
 * <p>2026-08-26 배포에서 실제로 한 건이 그렇게 남았다. <b>마이그레이션을 또 만드는 것으로는
 * 막지 못한다</b> — 배포할 때마다 다시 생기는 종류다.
 *
 * <p><b>평소에는 할 일이 없다.</b> 배포 직후 한두 건을 치우고 나면 조건에 걸리는 행이
 * 없으므로, 주기를 촘촘하게 둘 이유가 없다.
 *
 * <p><b>여러 Pod 이 동시에 돌아도 안전하다.</b> 조건에 맞는 행을 같은 값으로 바꾸는 한
 * 문장이라 누가 먼저 하든 결과가 같고, 발행 같은 부수 효과가 없다.
 */
@Component
public class SummaryRawTextSweeper {

    private static final Logger log = LoggerFactory.getLogger(SummaryRawTextSweeper.class);

    private final TaskRepository taskRepository;

    /** 한 회차에서 다룰 최대 건수. */
    private final int batchSize;

    public SummaryRawTextSweeper(TaskRepository taskRepository,
                                 @Value("${slash.task.summary-raw-text-sweep.batch-size}") int batchSize) {
        this.taskRepository = taskRepository;
        this.batchSize = batchSize;
    }

    /**
     * 밀리초 단위로 받는 이유는 다른 주기 작업과 같다 — {@code @Scheduled} 의 문자열 값은
     * 설정 파일의 {@code 10m} 표기를 그대로 해석하지 못한다.
     */
    @Scheduled(
            fixedDelayString = "${slash.task.summary-raw-text-sweep.interval-ms}",
            initialDelayString = "${slash.task.summary-raw-text-sweep.interval-ms}")
    public void sweep() {
        try {
            int cleaned = taskRepository.dropRawTextFromSucceededSummaries(batchSize);

            if (cleaned > 0) {
                // 평소에는 0이다. 찍힌다면 직전 배포의 롤링 창에서 접수된 건이 있었다는 뜻이다.
                log.info("요약 원문 정리 {}건", cleaned);
            }

        } catch (Exception e) {
            // 한 회차의 실패로 스케줄이 멈추지 않게 한다. 다음 회차가 같은 일을 다시 시도한다.
            log.error("요약 원문 정리 실패: {}", e.getMessage());
        }
    }
}
