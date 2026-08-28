package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.ASYNC_JOBS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ExecutionTarget;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.NluSummaryClient;
import com.likelion.slash.nlu.SummaryOutcome;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluDecision;
import com.likelion.slash.nlu.dto.NluSummaryResponse;
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
 * CPU 추출 요약 경로 확인. (slash-docs#3 권장 순서 3번)
 *
 * <p>Gemma 경로는 {@link TaskServiceTest} 가 본다. 두 경로는 <b>같은 작업 유형이 서로 다른
 * 방식으로 실행되는</b> 것이라 한 클래스에 둘 수 없다 — 엔진이 설정으로 정해지기 때문이다.
 *
 * <p>여기서 지키는 것은 셋이다.
 * <ul>
 *   <li>실행 위치가 {@code BACKEND} 로 남는가 — Gemma 와 같은 자리다</li>
 *   <li><b>원장을 남기지 않는가</b> — 몇십 밀리초에 끝나는 일이라 이어받을 것이 없다.
 *       Gemma 경로와 갈라지는 지점이다</li>
 *   <li>무엇으로 요약했는지가 결과에 남는가 — 실행 위치만으로는 둘을 구분할 수 없다</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "slash.summary.engine=EXTRACTIVE")
class ExtractiveSummaryRoutingTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NluClient nluClient;

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
    }

    private CreateRequestResponse 요약을_요청한다() {
        return taskService.accept(사용자, new CreateRequestRequest("/summary 요약할 긴 글", null), null);
    }

    private TasksRecord 작업조회(UUID taskId) {
        return dsl.selectFrom(com.likelion.slash.jooq.Tables.TASKS)
                .where(com.likelion.slash.jooq.Tables.TASKS.PUBLIC_ID.eq(taskId))
                .fetchOne();
    }

    @Test
    @DisplayName("GPU 없이 요약하고 그 자리에서 끝난다")
    void 요약을_곧바로_끝낸다() {
        given(nluSummaryClient.summarize(any(), any(), any())).willReturn(
                new SummaryOutcome.Success(new NluSummaryResponse(
                        "r", "t", "고른 문장.", "EXTRACTIVE", "TFIDF_CENTROID", "1", 8, 3, 16)));

        CreateRequestResponse 응답 = 요약을_요청한다();

        // Gemma 는 QUEUED 로 답하고 뒤에서 실행하지만, 이쪽은 응답이 나갈 때 이미 끝나 있다.
        assertThat(응답.status()).isEqualTo(TaskStatus.SUCCEEDED);

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getTaskType()).isEqualTo("TEXT_SUMMARY");
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.BACKEND.name());

        // 처리 경로는 유형에서 파생된 상수라 엔진과 무관하게 그대로다.

        // PC 를 붙들지 않는다.
        assertThat(작업.getDeviceId()).isNull();
    }

    @Test
    @DisplayName("원장을 남기지 않는다")
    void 원장이_없다() {
        given(nluSummaryClient.summarize(any(), any(), any())).willReturn(
                new SummaryOutcome.Success(new NluSummaryResponse(
                        "r", "t", "고른 문장.", "EXTRACTIVE", "TFIDF_CENTROID", "1", 8, 3, 16)));

        CreateRequestResponse 응답 = 요약을_요청한다();

        // 원장과 스윕은 모델이 수십 초를 쓰기 때문에 두는 것이다. 여기에는 그 이유가 없다.
        // 남기면 스윕이 이미 끝난 작업을 두고 돌게 된다.
        long taskId = 작업조회(응답.taskId()).getId();
        assertThat(dsl.selectFrom(ASYNC_JOBS).where(ASYNC_JOBS.TASK_ID.eq(taskId)).fetch()).isEmpty();
    }

    @Test
    @DisplayName("무엇으로 요약했는지가 결과에 남는다")
    void 엔진을_결과에_남긴다() throws Exception {
        given(nluSummaryClient.summarize(any(), any(), any())).willReturn(
                new SummaryOutcome.Success(new NluSummaryResponse(
                        "r", "t", "고른 문장.", "EXTRACTIVE", "TFIDF_CENTROID", "1", 8, 3, 16)));

        CreateRequestResponse 응답 = 요약을_요청한다();
        JsonNode result = objectMapper.readTree(작업조회(응답.taskId()).getResult().data());

        // 문자열이 아니라 값으로 본다. JSONB 는 저장하면서 공백과 키 순서를 바꾼다.
        //
        // 실행 위치(BACKEND)는 Gemma 와 같으므로, 둘을 가르는 것은 이 값들이다.
        // (slash-docs#3 리뷰에서 "어디서" 와 "무엇으로" 를 갈라 두기로 한 경계)
        assertThat(result.path("engine").asText()).isEqualTo("EXTRACTIVE");
        assertThat(result.path("algorithm").asText()).isEqualTo("TFIDF_CENTROID");
        assertThat(result.path("algorithmVersion").asText()).isEqualTo("1");

        // 화면은 summary 만 그린다. 엔진이 바뀌어도 그 자리는 그대로여야 한다.
        assertThat(result.path("summary").asText()).isEqualTo("고른 문장.");
    }

    @Test
    @DisplayName("요약할 수 없는 입력은 그 이유로 마감한다")
    void 요약할_수_없으면_실패로_마감한다() {
        given(nluSummaryClient.summarize(any(), any(), any())).willReturn(
                new SummaryOutcome.Failure(ErrorCode.INVALID_PARAMETERS, "요약할 내용이 너무 짧습니다."));

        CreateRequestResponse 응답 = 요약을_요청한다();

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETERS.name());
        assertThat(작업.getResult()).isNull();

        // 실패해도 어디서 하려 했는지는 남는다. 이력에서 경로를 읽을 수 있어야 한다.
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.BACKEND.name());
    }
}
