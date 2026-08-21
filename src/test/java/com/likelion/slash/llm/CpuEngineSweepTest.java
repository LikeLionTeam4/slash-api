package com.likelion.slash.llm;

import static com.likelion.slash.jooq.Tables.ASYNC_JOBS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AsyncJobStatus;
import com.likelion.slash.common.enums.AsyncJobType;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.job.AsyncJobRepository;
import com.likelion.slash.jooq.tables.records.AsyncJobsRecord;
import java.time.Duration;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 요약을 CPU 로 하는 동안 GPU 원장이 어떻게 되는지 확인. (#59 리뷰)
 *
 * <p><b>전환 시점에 처리 중이던 GPU 작업이 문제였다.</b> 스윕을 통째로 끄면 그 원장을 마감할
 * 것이 없어져 {@code QUEUED} 인 채로 굳는다. Task 는 {@code StaleTaskSweeper} 가 기한 뒤
 * 만료로 마감하지만 원장은 아무도 건드리지 않아, <b>나중에 GPU 로 되돌렸을 때 이미 만료된
 * 작업을 다시 돌리는 대상</b>이 된다 — {@code restartStale} 은 Task 상태를 보지 않는다.
 *
 * <p>그래서 스윕은 언제나 돌되 <b>다시 돌리는 것만</b> GPU 요약일 때 한다.
 * 이 클래스는 기본값(CPU)에서 그 둘이 갈리는지를 본다.
 */
@SpringBootTest
@Transactional
class CpuEngineSweepTest {

    @Autowired
    private LlmJobSweeper sweeper;

    @Autowired
    private AsyncJobRepository asyncJobRepository;

    @Autowired
    private DSLContext dsl;

    @MockitoBean
    private LlmSummaryRunner runner;

    private long taskId;

    @BeforeEach
    void setUp() {
        taskId = 작업(dsl, 사용자(dsl), null, TaskStatus.QUEUED.name());
    }

    private long 원장을_만든다() {
        AsyncJobsRecord job = asyncJobRepository.create(
                taskId, AsyncJobType.TEXT_SUMMARY,
                JSONB.valueOf("{\"text\":\"요약할 긴 글\"}"), SlashTime.now().plusMinutes(5));
        asyncJobRepository.markQueued(job.getId());
        return job.getId();
    }

    private AsyncJobsRecord 원장조회(long jobId) {
        return dsl.selectFrom(ASYNC_JOBS).where(ASYNC_JOBS.ID.eq(jobId)).fetchOne();
    }

    @Test
    @DisplayName("GPU 준비 확인 빈이 없어도 스윕이 만들어진다")
    void 스윕은_언제나_있다() {
        // 이 빈이 없으면 전환 시점에 남은 원장을 마감할 것이 없어진다.
        assertThat(sweeper).isNotNull();
    }

    @Test
    @DisplayName("전환 시점에 남은 GPU 원장을 기한이 지나면 마감한다")
    void 남은_원장을_마감한다() {
        long jobId = 원장을_만든다();

        // 기한이 지난 상태로 만든다. ck_async_jobs_deadline_after_created 때문에
        // 만든 시각도 함께 민다.
        dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.CREATED_AT, SlashTime.now().minus(Duration.ofHours(2)))
                .set(ASYNC_JOBS.DEADLINE_AT, SlashTime.now().minus(Duration.ofHours(1)))
                .where(ASYNC_JOBS.ID.eq(jobId))
                .execute();

        sweeper.sweep();

        // 기한이 지난 원장은 만료로 마감하고 Task 도 함께 끝낸다. 원장만 닫으면 화면에는
        // 끝나지 않는 진행 표시가 남는다.
        assertThat(원장조회(jobId).getStatus()).isEqualTo(AsyncJobStatus.EXPIRED.name());
        assertThat(dsl.selectFrom(TASKS).where(TASKS.ID.eq(taskId)).fetchOne().getStatus())
                .isEqualTo(TaskStatus.FAILED.name());
    }

    @Test
    @DisplayName("모델이 없으므로 다시 돌리지는 않는다")
    void 다시_돌리지는_않는다() {
        long jobId = 원장을_만든다();

        // 스윕이 "놓친 것" 으로 보게 만든다.
        dsl.update(ASYNC_JOBS)
                .set(ASYNC_JOBS.CREATED_AT, SlashTime.now().minus(Duration.ofHours(1)))
                .where(ASYNC_JOBS.ID.eq(jobId))
                .execute();

        sweeper.sweep();

        // 남아 있는 원장은 GPU 로 접수해 둔 것이라 모델 없이 되살릴 수 없다.
        // 돌려 봐야 같은 실패를 반복하고, 기한이 차면 위 시험처럼 마감된다.
        verify(runner, never()).runAsync(anyLong(), anyLong(), any(), any(), any());
        assertThat(원장조회(jobId).getStatus()).isEqualTo(AsyncJobStatus.QUEUED.name());
    }
}
