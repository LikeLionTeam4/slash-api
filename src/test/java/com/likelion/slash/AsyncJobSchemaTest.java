package com.likelion.slash;

import static com.likelion.slash.jooq.Tables.ASYNC_JOBS;
import static com.likelion.slash.jooq.Tables.OUTBOX_EVENTS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * V006 의 제약이 실제로 동작하는지 확인한다.
 *
 * <p>Task 당 Job 한 건, 중복 결과 반영 차단, 미발행 Outbox 조회를 중심으로 본다.
 */
@SpringBootTest
@Transactional
class AsyncJobSchemaTest {

    @Autowired
    private DSLContext dsl;

    private long 사용자를_만든다() {
        return dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, "job-test-" + UUID.randomUUID())
                .set(USERS.EMAIL, UUID.randomUUID() + "@example.com")
                .returning(USERS.ID)
                .fetchOne()
                .getId();
    }

    private long 요약_작업을_만든다(long userId) {
        return dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.INPUT_TEXT, "이 문서 요약해줘")
                .set(TASKS.TASK_TYPE, "TEXT_SUMMARY")
                .set(TASKS.PROCESSING_ROUTE, "LLM_SERVICE")
                .returning(TASKS.ID)
                .fetchOne()
                .getId();
    }

    private int 잡을_만든다(long taskId) {
        return dsl.insertInto(ASYNC_JOBS)
                .set(ASYNC_JOBS.TASK_ID, taskId)
                .set(ASYNC_JOBS.JOB_TYPE, "TEXT_SUMMARY")
                .set(ASYNC_JOBS.DEADLINE_AT, OffsetDateTime.now().plusMinutes(2))
                .execute();
    }

    // -----------------------------------------------------------------------
    // async_jobs
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("작업을 만들면 PENDING 으로 시작한다")
    void 잡은_pending_으로_시작한다() {
        long userId = 사용자를_만든다();
        long taskId = 요약_작업을_만든다(userId);

        var job = dsl.insertInto(ASYNC_JOBS)
                .set(ASYNC_JOBS.TASK_ID, taskId)
                .set(ASYNC_JOBS.JOB_TYPE, "TEXT_SUMMARY")
                .set(ASYNC_JOBS.INPUT, JSONB.valueOf("{\"text\":\"요약할 본문\"}"))
                .set(ASYNC_JOBS.DEADLINE_AT, OffsetDateTime.now().plusMinutes(2))
                .returning()
                .fetchOne();

        assertThat(job).isNotNull();
        assertThat(job.getPublicId()).isNotNull();
        assertThat(job.getStatus()).isEqualTo("PENDING");
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("한 작업에 LLM Job 을 두 건 만들 수 없다")
    void 작업당_잡은_한_건이다() {
        long userId = 사용자를_만든다();
        long taskId = 요약_작업을_만든다(userId);

        잡을_만든다(taskId);

        assertThatThrownBy(() -> 잡을_만든다(taskId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정의되지 않은 작업 종류는 저장할 수 없다")
    void 허용되지_않은_job_type_은_거부된다() {
        long userId = 사용자를_만든다();
        long taskId = 요약_작업을_만든다(userId);

        assertThatThrownBy(() -> dsl.insertInto(ASYNC_JOBS)
                .set(ASYNC_JOBS.TASK_ID, taskId)
                .set(ASYNC_JOBS.JOB_TYPE, "IMAGE_GENERATION")
                .set(ASYNC_JOBS.DEADLINE_AT, OffsetDateTime.now().plusMinutes(2))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 결과 메시지를 두 Job 에 반영할 수 없다")
    void 결과_이벤트는_한_잡에만_반영된다() {
        long userId = 사용자를_만든다();
        long firstTask = 요약_작업을_만든다(userId);
        long secondTask = 요약_작업을_만든다(userId);
        UUID eventId = UUID.randomUUID();

        잡을_만든다(firstTask);
        잡을_만든다(secondTask);

        dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.STATUS, "SUCCEEDED")
                .set(ASYNC_JOBS.RESULT, JSONB.valueOf("{\"summary\":\"요약 결과\"}"))
                .set(ASYNC_JOBS.RESULT_EVENT_ID, eventId)
                .set(ASYNC_JOBS.COMPLETED_AT, OffsetDateTime.now())
                .where(ASYNC_JOBS.TASK_ID.eq(firstTask))
                .execute();

        // 같은 eventId 로 다른 Job 을 마감하려 하면 중복 반영이므로 막아야 한다.
        assertThatThrownBy(() -> dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.STATUS, "SUCCEEDED")
                .set(ASYNC_JOBS.RESULT, JSONB.valueOf("{\"summary\":\"다른 결과\"}"))
                .set(ASYNC_JOBS.RESULT_EVENT_ID, eventId)
                .set(ASYNC_JOBS.COMPLETED_AT, OffsetDateTime.now())
                .where(ASYNC_JOBS.TASK_ID.eq(secondTask))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("성공 결과와 오류 코드가 동시에 존재할 수 없다")
    void 결과와_오류는_공존할_수_없다() {
        long userId = 사용자를_만든다();
        long taskId = 요약_작업을_만든다(userId);
        잡을_만든다(taskId);

        assertThatThrownBy(() -> dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.STATUS, "FAILED")
                .set(ASYNC_JOBS.RESULT, JSONB.valueOf("{\"summary\":\"ok\"}"))
                .set(ASYNC_JOBS.ERROR_CODE, "INTERNAL_ERROR")
                .set(ASYNC_JOBS.COMPLETED_AT, OffsetDateTime.now())
                .where(ASYNC_JOBS.TASK_ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("완료 시각 없이 최종 상태로 바꿀 수 없다")
    void 최종_상태에는_완료_시각이_필요하다() {
        long userId = 사용자를_만든다();
        long taskId = 요약_작업을_만든다(userId);
        잡을_만든다(taskId);

        assertThatThrownBy(() -> dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.STATUS, "SUCCEEDED")
                .where(ASYNC_JOBS.TASK_ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("만료 기한은 생성 시각보다 뒤여야 한다")
    void 만료_기한은_생성_시각보다_뒤여야_한다() {
        long userId = 사용자를_만든다();
        long taskId = 요약_작업을_만든다(userId);

        assertThatThrownBy(() -> dsl.insertInto(ASYNC_JOBS)
                .set(ASYNC_JOBS.TASK_ID, taskId)
                .set(ASYNC_JOBS.JOB_TYPE, "TEXT_SUMMARY")
                .set(ASYNC_JOBS.DEADLINE_AT, OffsetDateTime.now().minusMinutes(1))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("작업을 지우면 Job 도 함께 지워진다")
    void 작업_삭제_시_잡도_삭제된다() {
        long userId = 사용자를_만든다();
        long taskId = 요약_작업을_만든다(userId);
        잡을_만든다(taskId);

        dsl.deleteFrom(TASKS).where(TASKS.ID.eq(taskId)).execute();

        assertThat(dsl.fetchCount(ASYNC_JOBS, ASYNC_JOBS.TASK_ID.eq(taskId))).isZero();
    }

    // -----------------------------------------------------------------------
    // outbox_events
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("발행 전 이벤트만 미발행 목록에 나온다")
    void 미발행_이벤트만_조회된다() {
        long unpublishedBefore = dsl.fetchCount(OUTBOX_EVENTS, OUTBOX_EVENTS.PUBLISHED_AT.isNull());

        dsl.insertInto(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.AGGREGATE_TYPE, "ASYNC_JOB")
                .set(OUTBOX_EVENTS.AGGREGATE_ID, 1L)
                .set(OUTBOX_EVENTS.EVENT_TYPE, "LLM_JOB_REQUESTED")
                .set(OUTBOX_EVENTS.PAYLOAD, JSONB.valueOf("{\"jobType\":\"TEXT_SUMMARY\"}"))
                .execute();

        assertThat(dsl.fetchCount(OUTBOX_EVENTS, OUTBOX_EVENTS.PUBLISHED_AT.isNull()))
                .isEqualTo(unpublishedBefore + 1);

        // 발행 완료로 표시하면 목록에서 빠진다.
        dsl.update(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.PUBLISHED_AT, OffsetDateTime.now())
                .where(OUTBOX_EVENTS.EVENT_TYPE.eq("LLM_JOB_REQUESTED"))
                .execute();

        assertThat(dsl.fetchCount(OUTBOX_EVENTS, OUTBOX_EVENTS.PUBLISHED_AT.isNull()))
                .isEqualTo(unpublishedBefore);
    }

    @Test
    @DisplayName("정의되지 않은 원장 종류는 저장할 수 없다")
    void 허용되지_않은_aggregate_type_은_거부된다() {
        assertThatThrownBy(() -> dsl.insertInto(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.AGGREGATE_TYPE, "UNKNOWN")
                .set(OUTBOX_EVENTS.AGGREGATE_ID, 1L)
                .set(OUTBOX_EVENTS.EVENT_TYPE, "SOMETHING")
                .set(OUTBOX_EVENTS.PAYLOAD, JSONB.valueOf("{}"))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("전달 시도 횟수는 음수가 될 수 없다")
    void 시도_횟수는_음수가_될_수_없다() {
        assertThatThrownBy(() -> dsl.insertInto(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.AGGREGATE_TYPE, "TASK")
                .set(OUTBOX_EVENTS.AGGREGATE_ID, 1L)
                .set(OUTBOX_EVENTS.EVENT_TYPE, "TASK_CREATED")
                .set(OUTBOX_EVENTS.PAYLOAD, JSONB.valueOf("{}"))
                .set(OUTBOX_EVENTS.ATTEMPT_COUNT, -1)
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("백오프로 미룬 이벤트는 아직 발행 대상이 아니다")
    void 백오프_중인_이벤트는_제외된다() {
        dsl.insertInto(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.AGGREGATE_TYPE, "ASYNC_JOB")
                .set(OUTBOX_EVENTS.AGGREGATE_ID, 99L)
                .set(OUTBOX_EVENTS.EVENT_TYPE, "RETRY_LATER")
                .set(OUTBOX_EVENTS.PAYLOAD, JSONB.valueOf("{}"))
                .set(OUTBOX_EVENTS.ATTEMPT_COUNT, 1)
                .set(OUTBOX_EVENTS.AVAILABLE_AT, OffsetDateTime.now().plusMinutes(5))
                .execute();

        // 전달기는 available_at 이 지난 건만 가져간다.
        int dueNow = dsl.fetchCount(OUTBOX_EVENTS,
                OUTBOX_EVENTS.PUBLISHED_AT.isNull()
                        .and(OUTBOX_EVENTS.AVAILABLE_AT.le(OffsetDateTime.now()))
                        .and(OUTBOX_EVENTS.EVENT_TYPE.eq("RETRY_LATER")));

        assertThat(dueNow).isZero();
    }
}
