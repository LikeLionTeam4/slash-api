package com.likelion.slash.job;

import static com.likelion.slash.jooq.Tables.ASYNC_JOBS;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AsyncJobStatus;
import com.likelion.slash.common.enums.AsyncJobType;
import com.likelion.slash.jooq.tables.records.AsyncJobsRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/**
 * {@code async_jobs} 접근.
 *
 * <p>SQS 로 전달하는 LLM 작업. P0 는 Task 당 한 건이다. ({@code uk_async_jobs_task})
 *
 * <p><b>결과 중복 반영 방지</b> — Worker 가 보낸 결과 메시지의 {@code eventId} 를
 * {@code result_event_id} 에 함께 저장한다. {@code uk_async_jobs_result_event} 가 있으므로
 * 같은 결과가 두 번 와도 두 번째는 반영되지 않는다. 다른 {@code eventId} 로 결과가 또 오면
 * 이미 마감된 Job 이라 반영되지 않으므로 서비스는 {@code IDEMPOTENCY_CONFLICT} 로 응답한다. (문서 7.2)
 *
 * <p><b>만료는 저절로 일어나지 않는다.</b> {@link #expireOverdue} 를 배치가 호출하지 않으면
 * 비싼 GPU 시험 작업이 무기한 남는다.
 *
 * <p>관련 문서: 2.8.2 · 3.7.2 · 3.8 · WBS W3-02 · W3-03
 */
@Repository
public class AsyncJobRepository {

    /** 아직 결과가 확정되지 않은 상태. */
    private static final List<String> ACTIVE_STATUSES = List.of(
            AsyncJobStatus.PENDING.name(),
            AsyncJobStatus.QUEUED.name(),
            AsyncJobStatus.RUNNING.name());

    private final DSLContext dsl;

    public AsyncJobRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ------------------------------------------------------------------
    // 생성·조회
    // ------------------------------------------------------------------

    /**
     * AI 작업을 접수한다.
     *
     * <p>Task 상태 변경·{@code task_events}·{@code outbox_events} 저장과 한 트랜잭션으로 묶는다.
     * SQS 발행은 트랜잭션 밖의 전달기가 한다. (문서 2.8.2)
     *
     * @param input 모델에 넘길 구조화 입력. 비밀값·인증 토큰을 넣지 않는다.
     */
    public AsyncJobsRecord create(long taskId, AsyncJobType jobType, JSONB input, OffsetDateTime deadlineAt) {
        return dsl.insertInto(ASYNC_JOBS)
                .set(ASYNC_JOBS.TASK_ID, taskId)
                .set(ASYNC_JOBS.JOB_TYPE, jobType.name())
                .set(ASYNC_JOBS.INPUT, input)
                .set(ASYNC_JOBS.DEADLINE_AT, deadlineAt)
                .returning()
                .fetchOne();
    }

    /** Worker 결과 메시지가 들고 오는 {@code jobId} 로 찾는다. */
    public Optional<AsyncJobsRecord> findByPublicId(UUID publicId) {
        return dsl.selectFrom(ASYNC_JOBS)
                .where(ASYNC_JOBS.PUBLIC_ID.eq(publicId))
                .fetchOptional();
    }

    public Optional<AsyncJobsRecord> findByTaskId(long taskId) {
        return dsl.selectFrom(ASYNC_JOBS)
                .where(ASYNC_JOBS.TASK_ID.eq(taskId))
                .fetchOptional();
    }

    // ------------------------------------------------------------------
    // 진행
    // ------------------------------------------------------------------

    /** Outbox 전달기가 SQS 발행에 성공했다. */
    public boolean markQueued(long id) {
        return dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.STATUS, AsyncJobStatus.QUEUED.name())
                .where(ASYNC_JOBS.ID.eq(id))
                .and(ASYNC_JOBS.STATUS.eq(AsyncJobStatus.PENDING.name()))
                .execute() == 1;
    }

    /**
     * Worker 가 추론을 시작했다.
     *
     * <p>SQS 재수신으로 같은 Job 을 다시 집을 수 있으므로 {@code RUNNING} 에서도 허용하고
     * {@code attempt_count} 를 올린다. 3회를 넘으면 DLQ 로 보낸다.
     */
    public boolean markRunning(long id) {
        return dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.STATUS, AsyncJobStatus.RUNNING.name())
                .set(ASYNC_JOBS.STARTED_AT, SlashTime.now())
                .set(ASYNC_JOBS.ATTEMPT_COUNT, ASYNC_JOBS.ATTEMPT_COUNT.plus(1))
                .where(ASYNC_JOBS.ID.eq(id))
                .and(ASYNC_JOBS.STATUS.in(
                        AsyncJobStatus.QUEUED.name(),
                        AsyncJobStatus.RUNNING.name()))
                .execute() == 1;
    }

    /**
     * 추론 결과를 반영한다.
     *
     * <p>{@code resultEventId} 가 이미 다른 Job 에 쓰였으면
     * {@link org.springframework.dao.DuplicateKeyException} 이 난다. 중복 결과를 DB 가 막는 것이므로
     * 서비스는 이 경우 이미 반영된 것으로 보고 성공 응답을 돌려준다.
     *
     * @param result 64KB 상한 ({@code ck_async_jobs_result_size})
     * @param durationMilliseconds 추론 소요 시간. 성능 기록용
     */
    public boolean succeed(long id,
                           UUID resultEventId,
                           JSONB result,
                           String model,
                           Integer durationMilliseconds) {
        return dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.STATUS, AsyncJobStatus.SUCCEEDED.name())
                .set(ASYNC_JOBS.RESULT, result)
                .set(ASYNC_JOBS.RESULT_EVENT_ID, resultEventId)
                .set(ASYNC_JOBS.MODEL, model)
                .set(ASYNC_JOBS.DURATION_MILLISECONDS, durationMilliseconds)
                .set(ASYNC_JOBS.COMPLETED_AT, SlashTime.now())
                .where(ASYNC_JOBS.ID.eq(id))
                .and(ASYNC_JOBS.STATUS.in(ACTIVE_STATUSES))
                .execute() == 1;
    }

    /**
     * 추론 실패를 반영한다.
     *
     * <p>{@code ck_async_jobs_result_or_error} 때문에 성공 결과와 오류 코드는 함께 있을 수 없다.
     *
     * @param retryable 같은 Job 을 다시 처리할 수 있는지. DLQ 정책 판단에 쓴다.
     */
    public boolean fail(long id, UUID resultEventId, String errorCode, Boolean retryable) {
        return dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.STATUS, AsyncJobStatus.FAILED.name())
                .set(ASYNC_JOBS.RESULT, (JSONB) null)
                .set(ASYNC_JOBS.ERROR_CODE, errorCode)
                .set(ASYNC_JOBS.RETRYABLE, retryable)
                .set(ASYNC_JOBS.RESULT_EVENT_ID, resultEventId)
                .set(ASYNC_JOBS.COMPLETED_AT, SlashTime.now())
                .where(ASYNC_JOBS.ID.eq(id))
                .and(ASYNC_JOBS.STATUS.in(ACTIVE_STATUSES))
                .execute() == 1;
    }

    /**
     * 기한이 지난 미완료 Job 을 마감한다. ({@code idx_async_jobs_active_deadline})
     *
     * @return 마감한 건수
     */
    public int expireOverdue(OffsetDateTime now, String errorCode) {
        return dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.STATUS, AsyncJobStatus.EXPIRED.name())
                .set(ASYNC_JOBS.RESULT, (JSONB) null)
                .set(ASYNC_JOBS.ERROR_CODE, errorCode)
                .set(ASYNC_JOBS.COMPLETED_AT, SlashTime.now())
                .where(ASYNC_JOBS.STATUS.in(ACTIVE_STATUSES))
                .and(ASYNC_JOBS.DEADLINE_AT.le(now))
                .execute();
    }
}
