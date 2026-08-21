package com.likelion.slash.approval;

import static com.likelion.slash.jooq.Tables.ASYNC_JOBS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ApprovalDecision;
import com.likelion.slash.common.enums.ExecutionTarget;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.llm.LlmReadiness;
import com.likelion.slash.llm.LlmSummaryRunner;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.NluSummaryClient;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluDecision;
import com.likelion.slash.task.TaskService;
import com.likelion.slash.task.dto.CreateRequestRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인과 GPU 요약이 겹칠 때. (#60 리뷰)
 *
 * <p><b>재개 경로가 엔진 설정을 무시하면 아무 데서도 걸리지 않는다.</b> 실행 위치는 두 엔진이
 * 똑같이 {@code BACKEND} 이고 입력값도 그대로라 해시 대조까지 통과한다 — 사용자가 GPU 로
 * 승인한 요약이 조용히 CPU 추출 요약으로 실행돼도 오류가 나지 않는다.
 *
 * <p>두 설정은 서로를 모른다. {@code ApprovalPolicy} 는 작업 유형만 보고
 * {@code summaryEngine} 과의 조합을 확인하지 않으므로, 둘 다 환경 변수만으로 켤 수 있다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        "slash.summary.engine=GEMMA",
        "slash.approval.required-task-types=TEXT_SUMMARY"
})
class GemmaApprovalTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskApprovalRepository approvalRepository;

    @Autowired
    private DSLContext dsl;

    @MockitoBean
    private NluClient nluClient;

    @MockitoBean
    private LlmSummaryRunner llmSummaryRunner;

    @MockitoBean
    private LlmReadiness llmReadiness;

    /** CPU 경로로 새어 나가는지 보려고 둔다. 승인받은 GPU 요약은 이쪽을 부르면 안 된다. */
    @MockitoBean
    private NluSummaryClient nluSummaryClient;

    private AuthenticatedUser 사용자;

    @BeforeEach
    void setUp() {
        long userId = 사용자(dsl);
        this.사용자 = new AuthenticatedUser(
                userId, UUID.randomUUID(), "tester@example.com", "시험 사용자",
                "Asia/Seoul", "ACTIVE", SlashTime.now());

        given(nluClient.analyze(any(), any(), any())).willReturn(
                new NluAnalyzeResponse("r", NluDecision.TASK, "TEXT_SUMMARY",
                        Map.of("text", "요약할 긴 글"), List.of(), null, 1.0, "SLASH"));

        given(llmReadiness.canAccept()).willReturn(true);
    }

    private CreateRequestResponse 요약을_요청한다() {
        return taskService.accept(사용자, new CreateRequestRequest("/summary 요약할 긴 글", null), null);
    }

    private TasksRecord 작업조회(UUID taskId) {
        return dsl.selectFrom(TASKS).where(TASKS.PUBLIC_ID.eq(taskId)).fetchOne();
    }

    @Test
    @DisplayName("GPU 요약도 실행 전에 멈춘다")
    void 물어보고_멈춘다() {
        CreateRequestResponse 응답 = 요약을_요청한다();

        assertThat(응답.status()).isEqualTo(TaskStatus.WAITING_FOR_APPROVAL);
        assertThat(작업조회(응답.taskId()).getExecutionTarget()).isEqualTo(ExecutionTarget.BACKEND.name());

        // 물어보기만 하고 모델을 부르지 않는다. 원장도 아직 없다.
        verify(llmSummaryRunner, never()).runAsync(anyLong(), anyLong(), any(), any(), any());
        assertThat(dsl.selectFrom(ASYNC_JOBS)
                .where(ASYNC_JOBS.TASK_ID.eq(작업조회(응답.taskId()).getId())).fetch()).isEmpty();
    }

    @Test
    @DisplayName("승인하면 GPU 로 간다 — CPU 추출 요약으로 새지 않는다")
    void 승인하면_GPU_로_간다() {
        CreateRequestResponse 접수 = 요약을_요청한다();
        TasksRecord 작업 = 작업조회(접수.taskId());
        int version = approvalRepository.findByTaskId(작업.getId()).orElseThrow().getVersion();

        TaskStatus 결과 = taskService.decideApproval(사용자, 작업, ApprovalDecision.APPROVE, version);

        // 접수 직후와 같아야 한다. GPU 요약은 모델을 기다리므로 QUEUED 로 답한다.
        assertThat(결과).isEqualTo(TaskStatus.QUEUED);
        verify(llmSummaryRunner).runAsync(anyLong(), anyLong(), any(), any(), any());

        // 사용자가 승인한 것은 GPU 요약이다. 엔진이 조용히 바뀌면 안 된다.
        verify(nluSummaryClient, never()).summarize(any(), any(), any());
    }

    @Test
    @DisplayName("승인 뒤에도 원장이 남아 스윕이 이어받을 수 있다")
    void 원장이_남는다() {
        CreateRequestResponse 접수 = 요약을_요청한다();
        TasksRecord 작업 = 작업조회(접수.taskId());
        int version = approvalRepository.findByTaskId(작업.getId()).orElseThrow().getVersion();

        taskService.decideApproval(사용자, 작업, ApprovalDecision.APPROVE, version);

        // 원장 없이 QUEUED 로 굳으면 스윕이 찾지 못해 화면에 끝나지 않는 진행 표시가 남는다.
        assertThat(dsl.selectFrom(ASYNC_JOBS).where(ASYNC_JOBS.TASK_ID.eq(작업.getId())).fetch())
                .hasSize(1);
    }

    @Test
    @DisplayName("승인했는데 모델이 받을 수 없으면 그 이유로 마감한다")
    void 모델이_없으면_마감한다() {
        CreateRequestResponse 접수 = 요약을_요청한다();
        TasksRecord 작업 = 작업조회(접수.taskId());
        int version = approvalRepository.findByTaskId(작업.getId()).orElseThrow().getVersion();

        // 사용자가 승인을 고민하는 동안 GPU 가 내려갈 수 있다.
        given(llmReadiness.canAccept()).willReturn(false);

        TaskStatus 결과 = taskService.decideApproval(사용자, 작업, ApprovalDecision.APPROVE, version);

        assertThat(결과).isEqualTo(TaskStatus.FAILED);
        verify(llmSummaryRunner, never()).runAsync(anyLong(), anyLong(), any(), any(), any());
    }
}
