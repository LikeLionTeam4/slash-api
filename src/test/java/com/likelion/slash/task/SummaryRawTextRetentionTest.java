package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.SlashTime;
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
 * 요약이 끝나면 원문을 갖고 있지 않는지 확인한다. (slash-docs#3 · 원문 기본 미저장)
 *
 * <p><b>실행 경로에 따라 달랐던 것을 맞추는 작업이다.</b> {@code BROWSER} 는 애초에 원문을
 * 받지 않아 문구만 남겼는데, {@code BACKEND} 는 원문 전체를 두 벌(<code>input_text</code> ·
 * <code>parameters.text</code>) 저장하고 앞 80자를 {@code request_summary} 에 또 남겼다.
 *
 * <p><b>성공한 건에서만 지운다.</b> 원문을 들고 있어야 하는 이유가 실패 시 재시도뿐이라,
 * 결과가 나온 순간 서버가 들고 있을 이유가 사라진다. 실패한 건은 사용자가 다시 누를
 * 근거가 원문뿐이므로 그대로 둔다.
 *
 * <p>완전한 미저장은 애초에 불가능하다 — 추출 요약은 원문에서 문장을 그대로 고르므로
 * 결과가 원문의 부분집합이다. 여기서 지키는 것은 <b>전체 원문이 남지 않는 것</b>이다.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "slash.summary.engine=EXTRACTIVE")
class SummaryRawTextRetentionTest {

    /** 요약 대상 원문. 결과에 그대로 나타나지 않는 문장을 골라 둔다. */
    private static final String 원문 = "서울의 아침 공기는 축축했고 길 건너 목련이 먼저 피었다.";

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
                        Map.of("text", 원문), List.of(), null, 1.0, "SLASH"));
    }

    private void 요약이_성공한다() {
        given(nluSummaryClient.summarize(any(), any(), any())).willReturn(
                new SummaryOutcome.Success(new NluSummaryResponse(
                        "r", "t", "고른 문장이다.", "EXTRACTIVE", "TFIDF_CENTROID", "1", 8, 3, 16)));
    }

    private void 요약이_실패한다() {
        given(nluSummaryClient.summarize(any(), any(), any())).willReturn(
                new SummaryOutcome.Failure(ErrorCode.UPSTREAM_UNAVAILABLE, "요약 서비스에 닿지 못했습니다."));
    }

    private CreateRequestResponse 요약을_요청한다() {
        return taskService.accept(사용자, new CreateRequestRequest("/summary " + 원문, null), null);
    }

    private TasksRecord 작업조회(UUID taskId) {
        return dsl.selectFrom(TASKS).where(TASKS.PUBLIC_ID.eq(taskId)).fetchOne();
    }

    @Test
    @DisplayName("요약이 끝나면 원문이 어디에도 남지 않는다")
    void 성공하면_원문을_지운다() throws Exception {
        요약이_성공한다();

        CreateRequestResponse 응답 = 요약을_요청한다();
        assertThat(응답.status()).isEqualTo(TaskStatus.SUCCEEDED);

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getInputText()).doesNotContain(원문);
        assertThat(작업.getParameters().data()).doesNotContain("목련");
        assertThat(작업.getRequestSummary()).doesNotContain("목련");
    }

    @Test
    @DisplayName("원문 자리에는 무엇이 있었는지만 남는다")
    void 원문_자리에_문구를_남긴다() throws Exception {
        요약이_성공한다();

        TasksRecord 작업 = 작업조회(요약을_요청한다().taskId());

        // 화면은 이 문구를 그대로 그린다 — BROWSER 경로가 쓰던 방식과 같은 모양이라
        // 프론트가 "원문이 없는 경우"로 이미 다룰 줄 안다.
        assertThat(작업.getInputText()).contains("요약 후 저장하지 않음");
        assertThat(작업.getInputText()).contains(String.valueOf(원문.length()));

        // 길이는 남긴다. 무엇을 요약했는지 세어 보는 것까지 막을 이유는 없다.
        JsonNode parameters = objectMapper.readTree(작업.getParameters().data());
        assertThat(parameters.has("text")).isFalse();
        assertThat(parameters.path("inputLength").asInt()).isEqualTo(원문.length());
    }

    @Test
    @DisplayName("목록에 보여줄 한 줄은 원문 발췌가 아니라 요약 결과다")
    void 목록_요약을_결과로_바꾼다() {
        요약이_성공한다();

        TasksRecord 작업 = 작업조회(요약을_요청한다().taskId());

        // 지금까지는 원문 앞 80자였다. 분량이 작아도 원문 발췌인 것은 같고,
        // 이 시점에는 결과가 있으므로 그것을 쓴다. (BROWSER 가 처음부터 쓰던 방식)
        assertThat(작업.getRequestSummary()).isEqualTo("고른 문장이다.");
    }

    @Test
    @DisplayName("요약이 실패하면 원문을 그대로 둔다")
    void 실패하면_원문을_남긴다() {
        요약이_실패한다();

        CreateRequestResponse 응답 = 요약을_요청한다();
        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);

        // 사용자가 다시 누를 근거가 원문뿐이다. 여기서 지우면 재시도할 방법이 없어진다.
        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getInputText()).contains(원문);
        assertThat(작업.getParameters().data()).contains("목련");
    }
}
