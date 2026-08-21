package com.likelion.slash.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AsyncJobType;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.job.AsyncJobRepository;
import com.likelion.slash.jooq.tables.records.AsyncJobsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.TaskRepository;
import com.likelion.slash.task.TaskStateWriter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 시작되지 못한 요약 작업을 다시 돌리고, 기한이 지난 것은 마감한다. (이슈 #36 과 같은 결)
 *
 * <p><b>왜 필요한가.</b> 요약은 원장({@code async_jobs})을 남긴 뒤 {@link LlmSummaryRunner} 가
 * 별도 스레드에서 실행한다. 그 스레드는 Pod 과 함께 사라지므로, 호출 도중에 Pod 이 내려가면
 * 작업은 원장에만 남고 아무도 손대지 않는다. 화면에는 끝나지 않는 진행 표시로 보인다.
 * {@code PendingDispatchSweeper} 가 전달에 대해 하는 일을 요약에 대해 한다.
 *
 * <p><b>여러 Pod 이 동시에 돌아도 안전하다.</b> 같은 Job 을 둘이 집어도
 * {@code markRunning} 이 하나만 통과하고, 결과 반영도 상태를 확인한 뒤에만 일어난다.
 *
 * <p><b>요약을 GPU 로 할 때만 만들어진다.</b> CPU 추출 요약은 원장({@code async_jobs})을
 * 남기지 않는다 — 몇십 밀리초에 끝나 이어받을 것이 없기 때문이다. 그러면 이 스윕이 볼
 * 것도 없다. (slash-docs#3)
 *
 * <p><b>과거 원장은 그대로 남는다.</b> GPU 로 접수해 둔 작업이 아직 끝나지 않은 채로 엔진을
 * 바꾸면 그 작업을 마감할 것이 없어지므로, 되돌릴 때까지 남겨 두는 것이 맞다.
 * 권장 순서 5번("Gemma 신규 유입 중단")까지 두 경로가 함께 살아 있어야 하는 이유다.
 */
@Component
@ConditionalOnProperty(name = "slash.summary.engine", havingValue = "GEMMA")
public class LlmJobSweeper {

    private static final Logger log = LoggerFactory.getLogger(LlmJobSweeper.class);

    private final AsyncJobRepository asyncJobRepository;
    private final TaskRepository taskRepository;
    private final TaskStateWriter stateWriter;
    private final LlmSummaryRunner runner;
    private final LlmReadiness readiness;
    private final ObjectMapper objectMapper;

    /** 만료 마감을 원장과 Task 한 묶음으로 처리한다. 이유는 {@link LlmSummaryRunner} 와 같다. */
    private final TransactionTemplate transactionTemplate;

    /** 이만큼 지나도 시작되지 않았으면 놓친 것으로 본다. */
    private final Duration staleAfter;

    /** 한 회차에서 다룰 최대 건수. */
    private final int batchSize;

    public LlmJobSweeper(AsyncJobRepository asyncJobRepository,
                         TaskRepository taskRepository,
                         TaskStateWriter stateWriter,
                         LlmSummaryRunner runner,
                         LlmReadiness readiness,
                         ObjectMapper objectMapper,
                         PlatformTransactionManager transactionManager,
                         @Value("${slash.llm.job-sweep.stale-after}") Duration staleAfter,
                         @Value("${slash.llm.job-sweep.batch-size}") int batchSize) {
        this.asyncJobRepository = asyncJobRepository;
        this.taskRepository = taskRepository;
        this.stateWriter = stateWriter;
        this.runner = runner;
        this.readiness = readiness;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.staleAfter = staleAfter;
        this.batchSize = batchSize;
    }

    /**
     * 밀리초 단위로 받는 이유는 다른 주기 작업과 같다 — {@code @Scheduled} 의 문자열 값은
     * 설정 파일의 {@code 30s} 표기를 그대로 해석하지 못한다.
     */
    @Scheduled(
            fixedDelayString = "${slash.llm.job-sweep.interval-ms}",
            initialDelayString = "${slash.llm.job-sweep.interval-ms}")
    public void sweep() {
        try {
            expireOverdue();
            restartStale();

        } catch (Exception e) {
            // 한 회차의 실패로 스케줄이 멈추지 않게 한다. 다음 회차가 같은 일을 다시 시도한다.
            log.error("요약 작업 스윕 실패: {}", e.getMessage());
        }
    }

    /**
     * 기한이 지난 작업을 마감한다.
     *
     * <p>원장만 닫으면 Task 는 끝나지 않은 채 남는다. 둘을 함께 닫아야 화면의 진행 표시가 멎는다.
     */
    private void expireOverdue() {
        List<AsyncJobsRecord> overdue = asyncJobRepository.findOverdue(
                AsyncJobType.TEXT_SUMMARY, SlashTime.now(), batchSize);
        if (overdue.isEmpty()) {
            return;
        }

        // 원장과 Task 를 한 트랜잭션에서 닫는다. 나눠 두면 그 사이에 Pod 이 내려갔을 때
        // 원장만 EXPIRED 가 되고 Task 는 진행 상태로 남는데, 그 Job 은 더 이상 활성이 아니라
        // 다음 회차 조회에도 걸리지 않는다. 성공·실패 마감과 같은 이유다. (PR #42 리뷰)
        transactionTemplate.executeWithoutResult(status -> {
            // 조회한 것만 마감한다. 조건으로 한 번에 갱신하면 배치를 넘긴 Job 까지 EXPIRED 가
            // 되는데 그 Task 는 여기서 닫지 못해 열린 채로 남는다.
            asyncJobRepository.expire(
                    overdue.stream().map(AsyncJobsRecord::getId).toList(),
                    ErrorCode.UPSTREAM_UNAVAILABLE.name());

            for (AsyncJobsRecord job : overdue) {
                // 시작조차 못 했으면 QUEUED, 호출 중이었으면 RUNNING 이다. 어느 쪽이든 닫는다.
                stateWriter.failFromWorker(job.getTaskId(), ErrorCode.UPSTREAM_UNAVAILABLE,
                        "요약이 제한 시간 안에 끝나지 않았습니다.");
            }
        });
        log.info("기한이 지난 요약 작업 {}건을 마감했다", overdue.size());
    }

    /** 시작되지 못한 작업을 다시 돌린다. */
    private void restartStale() {
        // 모델이 받을 수 없는 상태면 다시 돌려 봐야 같은 실패를 반복한다. GPU 가 꺼져 있는
        // 동안은 건드리지 않고 두었다가, 켜지면 그때 이어서 돌린다. 그 전에 기한이 차면
        // 위 만료 마감이 정리한다. (PR #44 리뷰)
        if (!readiness.canAccept()) {
            log.debug("요약 모델이 작업을 받을 수 없어 재시도를 미룬다 reason={}",
                    readiness.reason().orElse("UNKNOWN"));
            return;
        }

        List<AsyncJobsRecord> stale = asyncJobRepository.findStale(
                AsyncJobType.TEXT_SUMMARY, SlashTime.now().minus(staleAfter), batchSize);

        for (AsyncJobsRecord job : stale) {
            Optional<TasksRecord> task = taskRepository.findById(job.getTaskId());
            Optional<String> text = textOf(job);

            if (task.isEmpty() || text.isEmpty()) {
                // 원문을 잃은 작업은 다시 돌릴 수 없다. 기한이 차면 위에서 마감된다.
                log.warn("다시 돌릴 수 없는 요약 작업이다 jobId={}", job.getId());
                continue;
            }

            log.info("시작되지 못한 요약 작업을 다시 돌린다 jobId={} taskId={}", job.getId(), job.getTaskId());

            // 여기서 기다리지 않는다. 모델 호출은 최대 slash.llm.timeout 만큼 걸리는데,
            // 이 스레드는 다른 주기 작업과 함께 쓰는 것이라(spring.task.scheduling.pool)
            // 붙들면 스윕이 가장 필요한 순간에 나머지 스윕이 밀린다.
            runner.runAsync(job.getId(), job.getTaskId(),
                    task.get().getCorrelationId(), task.get().getPublicId(), text.get());
        }
    }

    /** 원장에 남긴 입력에서 원문을 꺼낸다. */
    private Optional<String> textOf(AsyncJobsRecord job) {
        if (job.getInput() == null) {
            return Optional.empty();
        }
        try {
            JsonNode text = objectMapper.readTree(job.getInput().data()).get("text");
            return text == null || text.isNull() ? Optional.empty() : Optional.of(text.asText());
        } catch (Exception e) {
            log.warn("요약 작업의 입력을 읽지 못했다 jobId={}: {}", job.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
