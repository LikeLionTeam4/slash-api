package com.likelion.slash.llm;

import static com.likelion.slash.jooq.Tables.ASYNC_JOBS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.willCallRealMethod;

import com.likelion.slash.common.enums.AsyncJobStatus;
import com.likelion.slash.task.TaskStateWriter;
import java.util.UUID;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.likelion.slash.llm.dto.LlmSummaryResponse;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.job.AsyncJobRepository;
import java.time.Duration;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 원장과 Task 가 <b>항상 함께</b> 움직이는지 확인. 접수와 마감 둘 다 본다.
 *
 * <p><b>둘이 갈라지면 아무도 복구할 수 없는 상태가 된다.</b> 원장 없이 {@code QUEUED} 인
 * Task 는 스윕이 찾지 못하고(스윕은 원장을 보고 돈다), 화면에는 끝나지 않는 진행 표시만 남는다.
 *
 * <p><b>이 시험은 {@code @Transactional} 이 아니다.</b> 시험 자체가 트랜잭션이면 안쪽 롤백이
 * 바깥에 묻혀 무엇이 남았는지 볼 수 없다. 대신 만든 자료를 뒤에서 지운다.
 */
@SpringBootTest
class LlmSummaryAtomicityTest {

    @Autowired
    private LlmSummaryEnqueuer enqueuer;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private LlmSummaryRunner runner;

    /** 원장 생성만 실패시키기 위해 감싼다. 나머지 동작은 실제 그대로 둔다. */
    @MockitoSpyBean
    private AsyncJobRepository asyncJobRepository;

    /** 작업 마감만 실패시키기 위해 감싼다. */
    @MockitoSpyBean
    private TaskStateWriter stateWriter;

    /** 실제 모델을 부르지 않는다. */
    @MockitoBean
    private LlmClient llmClient;

    private long userId;
    private long taskId;

    @BeforeEach
    void setUp() {
        userId = 사용자(dsl);
        taskId = 작업(dsl, userId, null, TaskStatus.ANALYZING.name());
    }

    @AfterEach
    void tearDown() {
        dsl.deleteFrom(ASYNC_JOBS).where(ASYNC_JOBS.TASK_ID.eq(taskId)).execute();
        dsl.deleteFrom(TASKS).where(TASKS.ID.eq(taskId)).execute();
    }

    @Test
    @DisplayName("맡기면 작업과 원장이 함께 남는다")
    void 함께_남긴다() {
        var job = enqueuer.enqueue(taskId, TaskType.TEXT_SUMMARY, 입력(),
                "요약해줘", SlashTime.now().plus(Duration.ofMinutes(5)));

        assertThat(job).isPresent();
        assertThat(작업상태()).isEqualTo(TaskStatus.QUEUED.name());
        assertThat(원장수()).isEqualTo(1);
    }

    @Test
    @DisplayName("원장을 남기지 못하면 작업 상태도 되돌린다")
    void 원장이_없으면_되돌린다() {
        // spy 라 given(spy.method()) 형태를 쓰면 실제 호출이 먼저 일어난다.
        willThrow(new IllegalStateException("원장 생성 실패"))
                .given(asyncJobRepository).create(anyLong(), any(), any(), any());

        assertThatThrownBy(() -> enqueuer.enqueue(taskId, TaskType.TEXT_SUMMARY, 입력(),
                "요약해줘", SlashTime.now().plus(Duration.ofMinutes(5))))
                .isInstanceOf(IllegalStateException.class);

        // QUEUED 로 남으면 스윕이 찾지 못하는 유령 작업이 된다.
        assertThat(작업상태()).isEqualTo(TaskStatus.ANALYZING.name());
        assertThat(원장수()).isZero();

        willCallRealMethod().given(asyncJobRepository).create(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("작업을 마감하지 못하면 원장도 되돌린다")
    void 마감도_함께_되돌린다() {
        var job = enqueuer.enqueue(taskId, TaskType.TEXT_SUMMARY, 입력(),
                "요약해줘", SlashTime.now().plus(Duration.ofMinutes(5))).orElseThrow();

        given(llmClient.summarize(any(), any(), any()))
                .willReturn(new LlmSummaryOutcome.Success(
                        new LlmSummaryResponse("세 줄 요약", "gemma3:4b", null, null), 10));
        willThrow(new IllegalStateException("작업 마감 실패"))
                .given(stateWriter).succeed(anyLong(), any(), any());

        assertThatThrownBy(() -> runner.run(job.getId(), taskId,
                UUID.randomUUID(), 작업공개id(), "요약할 긴 글"))
                .isInstanceOf(IllegalStateException.class);

        // 원장만 SUCCEEDED 로 닫히면 Task 는 RUNNING 에 남는데, 스윕은 활성 Job 만 보므로
        // 그 Task 를 다시 집지 못한다.
        assertThat(원장상태(job.getId())).isNotEqualTo(AsyncJobStatus.SUCCEEDED.name());

        willCallRealMethod().given(stateWriter).succeed(anyLong(), any(), any());
    }

    private JSONB 입력() {
        return JSONB.valueOf("{\"text\":\"요약할 긴 글\"}");
    }

    private String 작업상태() {
        return dsl.select(TASKS.STATUS).from(TASKS).where(TASKS.ID.eq(taskId)).fetchOne(TASKS.STATUS);
    }

    private int 원장수() {
        return dsl.fetchCount(ASYNC_JOBS, ASYNC_JOBS.TASK_ID.eq(taskId));
    }

    private String 원장상태(long jobId) {
        return dsl.select(ASYNC_JOBS.STATUS).from(ASYNC_JOBS)
                .where(ASYNC_JOBS.ID.eq(jobId)).fetchOne(ASYNC_JOBS.STATUS);
    }

    private java.util.UUID 작업공개id() {
        return dsl.select(TASKS.PUBLIC_ID).from(TASKS).where(TASKS.ID.eq(taskId)).fetchOne(TASKS.PUBLIC_ID);
    }
}
