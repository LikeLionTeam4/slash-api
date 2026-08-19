package com.likelion.slash.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.job.AsyncJobRepository;
import com.likelion.slash.llm.dto.LlmSummaryResponse;
import com.likelion.slash.task.TaskStateWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 요약 작업을 실제로 돌린다. (문서 3.7)
 *
 * <p><b>요청 스레드에서 부르지 않는다.</b> {@code POST /api/v1/requests} 는 {@code taskId} 와
 * {@code QUEUED} 를 즉시 돌려주고 화면은 WSS·폴링으로 진행을 따라오는 계약이다
 * ({@code docs/frontend-api-contract.md} §7). 모델이 생각하는 동안 요청을 붙들면 그 계약이 깨진다.
 *
 * <p><b>{@code agent_dispatches} 와 같은 짜임이다.</b> 원장({@code async_jobs})을 먼저 남기고
 * 곧바로 실행을 시작하되, 놓친 것은 스윕({@link LlmJobSweeper})이 줍는다. Pod 이 호출 도중에
 * 죽어도 작업은 원장에 남아 있다.
 */
@Component
public class LlmSummaryRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmSummaryRunner.class);

    private final LlmClient llmClient;
    private final AsyncJobRepository asyncJobRepository;
    private final TaskStateWriter stateWriter;
    private final ObjectMapper objectMapper;

    public LlmSummaryRunner(LlmClient llmClient,
                            AsyncJobRepository asyncJobRepository,
                            TaskStateWriter stateWriter,
                            ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.asyncJobRepository = asyncJobRepository;
        this.stateWriter = stateWriter;
        this.objectMapper = objectMapper;
    }

    /**
     * 요약을 시작한다. 호출한 쪽은 기다리지 않는다.
     *
     * @param jobId         {@code async_jobs.id}
     * @param taskId        {@code tasks.id}
     * @param correlationId Task 의 추적 식별자. slash-llm 에 그대로 전달한다.
     * @param taskPublicId  Task 의 공개 식별자
     */
    @Async
    public void runAsync(long jobId, long taskId, UUID correlationId, UUID taskPublicId, String text) {
        run(jobId, taskId, correlationId, taskPublicId, text);
    }

    /**
     * 요약을 돌리고 결과를 원장과 Task 양쪽에 남긴다.
     *
     * <p>스윕이 같은 Job 을 다시 집어도 안전하다 — {@code markRunning} 은 아직 끝나지 않은
     * Job 에만 걸리고, 마감은 상태를 확인한 뒤에만 반영된다.
     */
    void run(long jobId, long taskId, UUID correlationId, UUID taskPublicId, String text) {
        if (!asyncJobRepository.markRunning(jobId)) {
            log.debug("이미 처리된 요약 작업이다 jobId={}", jobId);
            return;
        }

        // 사용자에게 "시작했다"를 먼저 보여 준다. 모델이 오래 생각해도 화면이 멈춰 있지 않다.
        stateWriter.move(taskId, TaskStatus.QUEUED, TaskStatus.RUNNING, null, "요약을 시작했습니다.");

        LlmSummaryOutcome outcome = llmClient.summarize(correlationId, taskPublicId, text);
        UUID resultEventId = UUID.randomUUID();

        switch (outcome) {
            case LlmSummaryOutcome.Success success -> succeed(jobId, taskId, resultEventId, success);
            case LlmSummaryOutcome.Failure failure -> fail(jobId, taskId, resultEventId, failure.failure());
        }
    }

    private void succeed(long jobId, long taskId, UUID resultEventId, LlmSummaryOutcome.Success success) {
        LlmSummaryResponse response = success.response();
        JSONB result = toResult(response);

        asyncJobRepository.succeed(jobId, resultEventId, result, response.model(), success.durationMilliseconds());
        stateWriter.succeed(taskId, result, "요약을 마쳤습니다.");

        log.info("요약 완료 taskId={} model={} {}ms", taskId, response.model(), success.durationMilliseconds());
    }

    private void fail(long jobId, long taskId, UUID resultEventId, LlmFailure failure) {
        asyncJobRepository.fail(jobId, resultEventId, failure.code(), failure.retryable());

        // 원장에는 slash-llm 의 코드가, 사용자에게는 우리 말이 남는다.
        //
        // RUNNING 으로 옮기지 못했을 수 있다 — 다른 Pod 이 먼저 집었거나 이미 마감된 경우다.
        // 어느 상태에서든 닫히도록 맡긴다.
        stateWriter.failFromWorker(taskId, failure.errorCode(), failure.message());

        log.info("요약 실패 taskId={} code={} retryable={}", taskId, failure.code(), failure.retryable());
    }

    /**
     * Task 결과로 저장할 모양.
     *
     * <p>slash-llm 이 준 것을 그대로 담는다. {@code model} 을 함께 두는 것은 같은 글을 다시
     * 요약했을 때 결과가 달라진 이유를 나중에 짚을 수 있게 하기 위해서다.
     */
    private JSONB toResult(LlmSummaryResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", response.summary());
        result.put("model", response.model());

        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            // 문자열 두 개를 직렬화하지 못할 이유가 없다. 그래도 결과를 통째로 잃지는 않게 한다.
            log.error("요약 결과를 직렬화하지 못했다: {}", e.getMessage());
            return JSONB.valueOf("{}");
        }
    }
}
