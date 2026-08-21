package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ExecutionTarget;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.dto.BrowserSummaryResultRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 브라우저(WebLLM) 요약 결과 접수 확인. (slash-docs#3 권장 순서 3번)
 *
 * <p>여기서 지키는 것은 넷이다.
 * <ul>
 *   <li>실행 위치가 {@code BROWSER} 로 남는가</li>
 *   <li>원문이 어디에도 저장되지 않는가 — {@code input_text} 에도 결과 필드에도 없어야 한다</li>
 *   <li>같은 {@code Idempotency-Key} 로 다시 보내면 새 이력이 또 생기지 않는가</li>
 *   <li>실패 보고를 그대로 실패 이력으로 남기는가</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class BrowserSummaryResultTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthenticatedUser 사용자;

    @BeforeEach
    void setUp() {
        long userId = 사용자(dsl);
        this.사용자 = new AuthenticatedUser(
                userId, UUID.randomUUID(), "tester@example.com", "시험 사용자",
                "Asia/Seoul", "ACTIVE", SlashTime.now());
    }

    private BrowserSummaryResultRequest 성공_결과() {
        return new BrowserSummaryResultRequest(
                1200, "Qwen2.5-1.5B-Instruct-q4f16_1-MLC", "v1",
                BrowserSummaryResultRequest.Status.SUCCEEDED,
                "브라우저에서 만든 요약문.", 2400, null);
    }

    private TasksRecord 작업조회(UUID taskId) {
        return dsl.selectFrom(TASKS).where(TASKS.PUBLIC_ID.eq(taskId)).fetchOne();
    }

    @Test
    @DisplayName("실행 위치가 BROWSER 로 남는다")
    void 실행_위치가_남는다() {
        CreateRequestResponse 응답 = taskService.submitBrowserSummaryResult(사용자, 성공_결과(), UUID.randomUUID().toString());

        assertThat(응답.status()).isEqualTo(TaskStatus.SUCCEEDED);
        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getTaskType()).isEqualTo("TEXT_SUMMARY");
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.BROWSER.name());
        assertThat(작업.getDeviceId()).isNull();
    }

    @Test
    @DisplayName("원문은 어디에도 저장하지 않는다")
    void 원문을_저장하지_않는다() throws Exception {
        CreateRequestResponse 응답 = taskService.submitBrowserSummaryResult(사용자, 성공_결과(), UUID.randomUUID().toString());

        TasksRecord 작업 = 작업조회(응답.taskId());

        // input_text 는 NOT NULL 이라 값이 있지만, 실제 원문이 아니라 안내 문구여야 한다.
        assertThat(작업.getInputText()).doesNotContain("브라우저에서 만든 요약문");
        assertThat(작업.getInputText()).contains("1200");

        JsonNode parameters = objectMapper.readTree(작업.getParameters().data());
        assertThat(parameters.has("text")).isFalse();
        assertThat(parameters.path("inputLength").asInt()).isEqualTo(1200);
    }

    @Test
    @DisplayName("결과에 요약문과 모델 정보가 남는다")
    void 결과가_남는다() throws Exception {
        CreateRequestResponse 응답 = taskService.submitBrowserSummaryResult(사용자, 성공_결과(), UUID.randomUUID().toString());

        JsonNode result = objectMapper.readTree(작업조회(응답.taskId()).getResult().data());
        assertThat(result.path("summary").asText()).isEqualTo("브라우저에서 만든 요약문.");
        assertThat(result.path("modelId").asText()).isEqualTo("Qwen2.5-1.5B-Instruct-q4f16_1-MLC");
        assertThat(result.path("durationMs").asInt()).isEqualTo(2400);
    }

    @Test
    @DisplayName("같은 Idempotency-Key 로 다시 보내면 새 이력이 또 생기지 않는다")
    void 같은_키로_다시_보내면_같은_작업을_돌려준다() {
        String key = UUID.randomUUID().toString();

        CreateRequestResponse 첫번째 = taskService.submitBrowserSummaryResult(사용자, 성공_결과(), key);
        CreateRequestResponse 두번째 = taskService.submitBrowserSummaryResult(사용자, 성공_결과(), key);

        assertThat(두번째.taskId()).isEqualTo(첫번째.taskId());
        assertThat(dsl.selectFrom(TASKS).where(TASKS.PUBLIC_ID.eq(첫번째.taskId())).fetch()).hasSize(1);
    }

    @Test
    @DisplayName("실패 보고를 그대로 실패 이력으로 남긴다")
    void 실패를_그대로_남긴다() {
        BrowserSummaryResultRequest 실패_결과 = new BrowserSummaryResultRequest(
                900, "Qwen2.5-1.5B-Instruct-q4f16_1-MLC", "v1",
                BrowserSummaryResultRequest.Status.FAILED,
                null, null, "모델을 불러오지 못했습니다.");

        CreateRequestResponse 응답 = taskService.submitBrowserSummaryResult(사용자, 실패_결과, UUID.randomUUID().toString());

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getErrorCode()).isEqualTo(ErrorCode.BROWSER_TASK_FAILED.name());
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.BROWSER.name());
        assertThat(작업.getResult()).isNull();
    }

    @Test
    @DisplayName("성공인데 요약문이 없으면 거부한다")
    void 성공인데_요약문이_없으면_거부한다() {
        BrowserSummaryResultRequest 잘못된_요청 = new BrowserSummaryResultRequest(
                900, "Qwen2.5-1.5B-Instruct-q4f16_1-MLC", "v1",
                BrowserSummaryResultRequest.Status.SUCCEEDED,
                null, null, null);

        assertThatThrownBy(() ->
                taskService.submitBrowserSummaryResult(사용자, 잘못된_요청, UUID.randomUUID().toString()))
                .isInstanceOf(SlashException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_ERROR);
    }
}
