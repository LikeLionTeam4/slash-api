package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.dispatch.TaskDispatcher;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.NluSummaryClient;
import com.likelion.slash.nlu.SummaryOutcome;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluDecision;
import com.likelion.slash.nlu.dto.NluSummaryResponse;
import com.likelion.slash.task.dto.BrowserSummaryResultRequest;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code inputText} 가 원문인지를 화면이 필드 하나로 판단할 수 있는지 확인한다. (#84 · slash-web#67)
 *
 * <p><b>문구로 판단하지 않게 하려고 두는 값이다.</b> 요약이 끝나면 원문 자리에 안내 문구가
 * 들어가는데(#83 · slash-docs#3) 그 문구가 실행 위치마다 다르다 — {@code BROWSER} 는
 * "서버로 전송되지 않음", {@code BACKEND} 는 "요약 후 저장하지 않음". 프론트가 이 둘을
 * 하드코딩해서 알아보게 두면 문구가 하나 더 늘 때 조용히 깨진다.
 *
 * <p>그래서 <b>실행 위치가 다른 세 자리가 같은 답을 내는지</b>를 여기서 함께 본다. 판단 근거는
 * 문구가 아니라 원문을 걷어낼 때만 넣는 {@code parameters.inputLength} 다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "slash.summary.engine=EXTRACTIVE")
class InputTextIsOriginalTest {

    /** 임시 인증에서는 {@code Authorization} 의 문자열이 곧 사용자다. */
    private static final String 인증 = "input-text-original-tester";

    private static final String 원문 = "서울의 아침 공기는 축축했고 길 건너 목련이 먼저 피었다.";

    @Autowired
    private TaskService taskService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NluClient nluClient;

    @MockitoBean
    private NluSummaryClient nluSummaryClient;

    @MockitoBean
    private TaskDispatcher taskDispatcher;

    private AuthenticatedUser 사용자;

    @BeforeEach
    void setUp() {
        long userId = 사용자(dsl, 인증);
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

    private boolean 원문인가(UUID taskId) {
        return taskService.inputTextIsOriginal(작업조회(taskId));
    }

    @Test
    @DisplayName("서버에서 요약이 끝나면 원문이 아니다")
    void 서버_요약이_끝나면_원문이_아니다() {
        요약이_성공한다();

        CreateRequestResponse 응답 = 요약을_요청한다();
        assertThat(응답.status()).isEqualTo(TaskStatus.SUCCEEDED);

        assertThat(원문인가(응답.taskId())).isFalse();
    }

    @Test
    @DisplayName("요약이 실패하면 원문이 그대로 남아 다시 보낼 수 있다")
    void 요약이_실패하면_원문이다() {
        요약이_실패한다();

        CreateRequestResponse 응답 = 요약을_요청한다();
        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);

        // 실패한 건은 사용자가 다시 누를 근거가 원문뿐이라 지우지 않는다. 재생성도 그래서 된다.
        assertThat(원문인가(응답.taskId())).isTrue();
    }

    @Test
    @DisplayName("브라우저 요약은 접수 시점부터 원문이 아니다")
    void 브라우저_요약은_처음부터_원문이_아니다() {
        CreateRequestResponse 응답 = taskService.submitBrowserSummaryResult(
                사용자,
                new BrowserSummaryResultRequest(
                        1200, "Qwen2.5-1.5B-Instruct-q4f16_1-MLC", "v1",
                        BrowserSummaryResultRequest.Status.SUCCEEDED,
                        "브라우저에서 만든 요약문.", 2400, null),
                UUID.randomUUID().toString());

        // 문구가 BACKEND 와 다른데도 같은 답이 나와야 한다 — 이게 필드를 두는 이유다.
        assertThat(작업조회(응답.taskId()).getInputText()).contains("서버로 전송되지 않음");
        assertThat(원문인가(응답.taskId())).isFalse();
    }

    @Test
    @DisplayName("요약이 아닌 작업은 언제나 원문 그대로다")
    void 요약이_아니면_원문이다() {
        given(nluClient.analyze(any(), any(), any())).willReturn(
                new NluAnalyzeResponse("r", NluDecision.TASK, "SYSTEM_STATUS",
                        Map.of(), List.of(), null, 1.0, "SLASH"));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/status", null), null);

        assertThat(원문인가(응답.taskId())).isTrue();
    }

    @Test
    @DisplayName("상세 응답에 실려 나간다")
    void 상세_응답에_실린다() throws Exception {
        요약이_성공한다();
        UUID 요약 = 요약을_요청한다().taskId();

        mockMvc.perform(get("/api/v1/tasks/" + 요약).header("Authorization", "Bearer " + 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inputTextIsOriginal").value(false));

        given(nluClient.analyze(any(), any(), any())).willReturn(
                new NluAnalyzeResponse("r", NluDecision.TASK, "SYSTEM_STATUS",
                        Map.of(), List.of(), null, 1.0, "SLASH"));

        String 접수 = mockMvc.perform(post("/api/v1/requests")
                        .header("Authorization", "Bearer " + 인증)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "/status"}
                                """))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        // 값이 없어서 화면이 판단을 못 하는 일이 없어야 하므로, 요약이 아닌 작업에도 실린다.
        mockMvc.perform(get(statusUrl(접수)).header("Authorization", "Bearer " + 인증))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inputTextIsOriginal").value(true));
    }

    private String statusUrl(String 접수응답) throws Exception {
        return objectMapper.readTree(접수응답).path("data").path("statusUrl").asText();
    }
}
