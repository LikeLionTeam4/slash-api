package com.likelion.slash.llm;

import static com.likelion.slash.jooq.Tables.ASYNC_JOBS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AsyncJobStatus;
import com.likelion.slash.common.enums.AsyncJobType;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.jooq.tables.records.AsyncJobsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.job.AsyncJobRepository;
import com.likelion.slash.llm.dto.LlmSummaryResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 놓친 요약 작업을 스윕이 이어받는지 확인.
 *
 * <p>요약 실행은 별도 스레드에서 하고 그 스레드는 Pod 과 함께 사라진다. 호출 도중에 Pod 이
 * 내려가면 작업은 원장에만 남는데, <b>그것을 되살릴 주체가 이 스윕뿐이다.</b>
 * 없으면 화면에 끝나지 않는 진행 표시가 남는다.
 */
@SpringBootTest
@Transactional
// 이 스윕은 GPU 요약 전용이다. CPU 추출 요약은 원장을 남기지 않아 스윕이 볼 것이 없고,
// 기본값으로 두면 빈 자체가 만들어지지 않는다. (slash-docs#3)
@TestPropertySource(properties = "slash.summary.engine=GEMMA")
class LlmJobSweeperTest {

    @Autowired
    private LlmJobSweeper sweeper;

    @Autowired
    private AsyncJobRepository asyncJobRepository;

    @Autowired
    private DSLContext dsl;

    /**
     * 실행을 맡기는 것까지만 본다.
     *
     * <p>{@code runAsync} 는 별도 스레드에서 도는데, 이 시험은 {@code @Transactional} 이라
     * 아직 커밋되지 않은 원장을 그 스레드가 보지 못한다. 실제 실행은 LlmSummaryRunnerTest 가 본다.
     */
    @MockitoBean
    private LlmSummaryRunner runner;

    /** 실제 slash-llm 에 묻지 않는다. 판정 자체는 LlmReadinessTest 가 본다. */
    @MockitoBean
    private LlmReadiness readiness;

    private long taskId;

    @BeforeEach
    void setUp() {
        taskId = 작업(dsl, 사용자(dsl), null, TaskStatus.QUEUED.name());

        // 대역의 boolean 기본값은 거짓이라 명시하지 않으면 재시도가 모두 건너뛰어진다.
        given(readiness.canAccept()).willReturn(true);
    }

    @Test
    @DisplayName("기한이 지난 요약은 원장과 작업을 함께 마감한다")
    void 기한이_지나면_마감한다() {
        long jobId = 원장을_만든다(SlashTime.now().plusMinutes(5));
        기한이_지나게_만든다(jobId);

        sweeper.sweep();

        assertThat(원장조회(jobId).getStatus()).isEqualTo(AsyncJobStatus.EXPIRED.name());

        // 원장만 닫으면 화면에는 끝나지 않는 진행 표시가 남는다.
        assertThat(작업조회().getStatus()).isEqualTo(TaskStatus.FAILED.name());
    }

    @Test
    @DisplayName("시작되지 못한 요약을 다시 돌린다")
    void 놓친_작업을_되살린다() {
        long jobId = 원장을_만든다(SlashTime.now().plusMinutes(5));
        오래된_작업으로_만든다(jobId);

        sweeper.sweep();

        verify(runner).runAsync(eq(jobId), eq(taskId), any(), any(), eq("요약할 긴 글"));
    }

    @Test
    @DisplayName("모델이 받을 수 없으면 재시도를 미룬다")
    void 받을_수_없으면_미룬다() {
        given(readiness.canAccept()).willReturn(false);
        given(readiness.reason()).willReturn(java.util.Optional.of("OLLAMA_UNAVAILABLE"));
        long jobId = 원장을_만든다(SlashTime.now().plusMinutes(5));
        오래된_작업으로_만든다(jobId);

        sweeper.sweep();

        // 다시 돌려 봐야 같은 실패를 반복한다. 켜지면 그때 이어서 돌린다.
        verify(runner, never()).runAsync(anyLong(), anyLong(), any(), any(), any());
        assertThat(원장조회(jobId).getStatus()).isEqualTo(AsyncJobStatus.QUEUED.name());
    }

    @Test
    @DisplayName("방금 맡긴 요약은 건드리지 않는다")
    void 방금_맡긴_것은_두고_본다() {
        long jobId = 원장을_만든다(SlashTime.now().plusMinutes(5));

        sweeper.sweep();

        // 실행이 이미 돌고 있을 수 있다. 여기서 또 집으면 같은 글을 두 번 요약한다.
        verify(runner, never()).runAsync(anyLong(), anyLong(), any(), any(), any());
        assertThat(원장조회(jobId).getStatus()).isEqualTo(AsyncJobStatus.QUEUED.name());
    }

    private long 원장을_만든다(OffsetDateTime 기한) {
        AsyncJobsRecord job = asyncJobRepository.create(
                taskId, AsyncJobType.TEXT_SUMMARY,
                JSONB.valueOf("{\"text\":\"요약할 긴 글\"}"), 기한);
        asyncJobRepository.markQueued(job.getId());
        return job.getId();
    }

    /**
     * 기한이 지난 상태로 만든다.
     *
     * <p>{@code ck_async_jobs_deadline_after_created} 때문에 기한만 과거로 미룰 수 없다.
     * 만든 시각도 함께 민다 — 실제로도 기한이 지난 Job 은 그만큼 오래전에 만들어진 것이다.
     */
    private void 기한이_지나게_만든다(long jobId) {
        dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.CREATED_AT, SlashTime.now().minus(Duration.ofHours(2)))
                .set(ASYNC_JOBS.DEADLINE_AT, SlashTime.now().minus(Duration.ofHours(1)))
                .where(ASYNC_JOBS.ID.eq(jobId))
                .execute();
    }

    /** 스윕이 "놓친 것"으로 보게 만든다. 설정값(stale-after)보다 확실히 앞선 시각으로 민다. */
    private void 오래된_작업으로_만든다(long jobId) {
        dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.CREATED_AT, SlashTime.now().minus(Duration.ofHours(1)))
                .where(ASYNC_JOBS.ID.eq(jobId))
                .execute();
    }

    private AsyncJobsRecord 원장조회(long jobId) {
        return dsl.selectFrom(ASYNC_JOBS).where(ASYNC_JOBS.ID.eq(jobId)).fetchOne();
    }

    private TasksRecord 작업조회() {
        return dsl.selectFrom(TASKS).where(TASKS.ID.eq(taskId)).fetchOne();
    }
}
