package com.likelion.slash.llm;

import static com.likelion.slash.jooq.Tables.ASYNC_JOBS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AsyncJobStatus;
import com.likelion.slash.common.enums.AsyncJobType;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.job.AsyncJobRepository;
import com.likelion.slash.jooq.tables.records.AsyncJobsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.llm.dto.LlmSummaryResponse;
import java.time.Duration;
import java.util.UUID;
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
 * 요약 실행이 원장과 Task 양쪽에 남는지 확인. (문서 3.7)
 *
 * <p>실제 모델은 부르지 않는다. 여기서 볼 것은 <b>결과를 어디에 어떻게 남기는가</b>이고,
 * slash-llm 과의 계약은 {@link LlmClientTest} 가 본다.
 *
 * <p>원장({@code async_jobs})과 Task 를 함께 보는 이유 — 한쪽만 닫히면 화면에는 끝나지 않는
 * 진행 표시가 남거나, 끝난 것처럼 보이는데 원장에는 이유가 없다.
 */
@SpringBootTest
@Transactional
class LlmSummaryRunnerTest {

    @Autowired
    private LlmSummaryRunner runner;

    @Autowired
    private AsyncJobRepository asyncJobRepository;

    @Autowired
    private DSLContext dsl;

    @MockitoBean
    private LlmClient llmClient;

    private long taskId;
    private UUID taskPublicId;
    private long jobId;

    @BeforeEach
    void setUp() {
        long userId = 사용자(dsl);
        taskId = 작업(dsl, userId, null, TaskStatus.QUEUED.name());
        taskPublicId = dsl.select(TASKS.PUBLIC_ID).from(TASKS).where(TASKS.ID.eq(taskId))
                .fetchOne(TASKS.PUBLIC_ID);

        AsyncJobsRecord job = asyncJobRepository.create(
                taskId, AsyncJobType.TEXT_SUMMARY,
                JSONB.valueOf("{\"text\":\"요약할 긴 글\"}"),
                SlashTime.now().plus(Duration.ofMinutes(5)));
        jobId = job.getId();
        asyncJobRepository.markQueued(jobId);
    }

    @Test
    @DisplayName("요약을 마치면 원장과 작업에 결과가 남는다")
    void 성공을_남긴다() {
        모델이(new LlmSummaryResponse("세 줄 요약", "gemma3:4b", null, null), 1234);

        runner.run(jobId, taskId, UUID.randomUUID(), taskPublicId, "요약할 긴 글");

        AsyncJobsRecord 원장 = 원장조회();
        assertThat(원장.getStatus()).isEqualTo(AsyncJobStatus.SUCCEEDED.name());
        assertThat(원장.getModel()).isEqualTo("gemma3:4b");
        assertThat(원장.getDurationMilliseconds()).isEqualTo(1234);
        assertThat(원장.getResult().data()).contains("세 줄 요약");

        TasksRecord 작업 = 작업조회();
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.SUCCEEDED.name());
        assertThat(작업.getResult().data()).contains("세 줄 요약").contains("gemma3:4b");
    }

    @Test
    @DisplayName("모델이 거절하면 원장에는 그 코드가, 사용자에게는 우리 말이 남는다")
    void 실패를_남긴다() {
        given(llmClient.summarize(any(), any(), any()))
                .willReturn(new LlmSummaryOutcome.Failure(LlmFailure.of("INPUT_TOO_SHORT", false)));

        runner.run(jobId, taskId, UUID.randomUUID(), taskPublicId, "짧다");

        AsyncJobsRecord 원장 = 원장조회();
        assertThat(원장.getStatus()).isEqualTo(AsyncJobStatus.FAILED.name());
        assertThat(원장.getErrorCode()).isEqualTo("INPUT_TOO_SHORT");
        assertThat(원장.getRetryable()).isFalse();
        assertThat(원장.getResult()).isNull();

        TasksRecord 작업 = 작업조회();
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.FAILED.name());

        // 사용자에게는 slash-llm 의 코드가 아니라 우리 쪽 코드가 보인다.
        assertThat(작업.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETERS.name());
    }

    @Test
    @DisplayName("이미 마감된 작업은 다시 돌리지 않는다")
    void 두_번_돌리지_않는다() {
        모델이(new LlmSummaryResponse("세 줄 요약", "gemma3:4b", null, null), 10);
        runner.run(jobId, taskId, UUID.randomUUID(), taskPublicId, "요약할 긴 글");

        // 스윕이 같은 Job 을 다시 집은 상황이다.
        runner.run(jobId, taskId, UUID.randomUUID(), taskPublicId, "요약할 긴 글");

        assertThat(원장조회().getAttemptCount()).isEqualTo(1);
        assertThat(작업조회().getStatus()).isEqualTo(TaskStatus.SUCCEEDED.name());
    }

    private void 모델이(LlmSummaryResponse 응답, int 걸린시간) {
        given(llmClient.summarize(any(), any(), any()))
                .willReturn(new LlmSummaryOutcome.Success(응답, 걸린시간));
    }

    private AsyncJobsRecord 원장조회() {
        return dsl.selectFrom(ASYNC_JOBS).where(ASYNC_JOBS.ID.eq(jobId)).fetchOne();
    }

    private TasksRecord 작업조회() {
        return dsl.selectFrom(TASKS).where(TASKS.ID.eq(taskId)).fetchOne();
    }
}
