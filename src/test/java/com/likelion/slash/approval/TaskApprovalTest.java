package com.likelion.slash.approval;

import static com.likelion.slash.jooq.Tables.AUDIT_EVENTS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ApprovalDecision;
import com.likelion.slash.common.enums.ApprovalStatus;
import com.likelion.slash.common.enums.ExecutionTarget;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.dispatch.TaskDispatcher;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluDecision;
import com.likelion.slash.task.TaskService;
import com.likelion.slash.task.dto.CreateRequestRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
import com.likelion.slash.weather.WeatherClient;
import com.likelion.slash.weather.WeatherOutcome;
import com.likelion.slash.weather.dto.ForecastResponse;
import com.likelion.slash.weather.dto.GeocodingResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행 전 사용자 확인. (P0-C · 계획 문서 §1.5)
 *
 * <p><b>기본값으로는 아무 작업도 승인을 요구하지 않는다.</b> 지금 있는 작업이 모두 읽기
 * 전용이거나 서버가 하는 일이라 물어볼 것이 없기 때문이다. 그래서 이 클래스는 정책을 켜서
 * 확인한다 — 승인이 필요한 작업이 생겼을 때 곧바로 돌아야 하므로, 그 경로가 실제로 도는지는
 * 지금 고정해 둔다.
 *
 * <p>날씨를 대상으로 삼은 것은 <b>PC 없이 종단까지 갈 수 있는 유일한 작업</b>이기 때문이다.
 * 승인이 실제로 붙을 작업(파일·코드 변경)은 아직 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "slash.approval.required-task-types=WEATHER_LOOKUP")
class TaskApprovalTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @Autowired
    private TaskApprovalRepository approvalRepository;

    @Autowired
    private DSLContext dsl;

    @MockitoBean
    private NluClient nluClient;

    @MockitoBean
    private WeatherClient weatherClient;

    @MockitoBean
    private TaskDispatcher taskDispatcher;

    private AuthenticatedUser 사용자;

    /** 임시 인증에서 이 문자열이 곧 사용자다. (local 프로필) */
    private String 토큰;

    @BeforeEach
    void setUp() {
        long userId = 사용자(dsl);
        this.사용자 = new AuthenticatedUser(
                userId, UUID.randomUUID(), "tester@example.com", "시험 사용자",
                "Asia/Seoul", "ACTIVE", SlashTime.now());

        // HTTP 로 부르는 시험은 그 사용자로 인증돼야 한다. 임시 인증은 토큰 문자열을
        // 그대로 cognito_sub 로 삼으므로, 픽스처가 만든 값을 읽어 쓴다.
        this.토큰 = dsl.select(com.likelion.slash.jooq.Tables.USERS.COGNITO_SUB)
                .from(com.likelion.slash.jooq.Tables.USERS)
                .where(com.likelion.slash.jooq.Tables.USERS.ID.eq(userId))
                .fetchOne(com.likelion.slash.jooq.Tables.USERS.COGNITO_SUB);

        given(nluClient.analyze(any(), any(), any())).willReturn(
                new NluAnalyzeResponse("r", NluDecision.TASK, "WEATHER_LOOKUP",
                        Map.of("location", "서울"), List.of(), null, 1.0, "SLASH"));

        given(weatherClient.lookup(any())).willReturn(new WeatherOutcome.Success(
                new GeocodingResponse.Place("서울특별시", 37.5, 127.0, "대한민국", "KR", "서울특별시", "Asia/Seoul"),
                new ForecastResponse.Current("2026-08-21T15:00", 28.0, 30.0, 60, 0.0, 0, 2.0)));
    }

    private CreateRequestResponse 날씨를_요청한다() {
        return taskService.accept(사용자, new CreateRequestRequest("/weather 서울", null), null);
    }

    private TasksRecord 작업조회(UUID taskId) {
        return dsl.selectFrom(TASKS).where(TASKS.PUBLIC_ID.eq(taskId)).fetchOne();
    }

    private int 감사기록수(String action) {
        return dsl.fetchCount(AUDIT_EVENTS, AUDIT_EVENTS.ACTION.eq(action));
    }

    private int 승인버전(long taskId) {
        return approvalRepository.findByTaskId(taskId).orElseThrow().getVersion();
    }

    @Test
    @DisplayName("승인이 필요한 작업은 실행하지 않고 멈춘다")
    void 실행하지_않고_묻는다() {
        CreateRequestResponse 응답 = 날씨를_요청한다();

        assertThat(응답.status()).isEqualTo(TaskStatus.WAITING_FOR_APPROVAL);

        // 물어보기만 하고 아무것도 하지 않는다. 실행한 뒤에 묻는 것은 승인이 아니다.
        verify(weatherClient, never()).lookup(any());

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.WAITING_FOR_APPROVAL.name());

        // 무엇을 승인하는지 알 수 있어야 하므로 입력값과 실행 위치는 이미 정해져 있다.
        assertThat(작업.getTaskType()).isEqualTo("WEATHER_LOOKUP");
        assertThat(작업.getExecutionTarget()).isEqualTo(ExecutionTarget.BACKEND.name());
        assertThat(작업.getParameters().data()).contains("서울");

        var approval = approvalRepository.findByTaskId(작업.getId()).orElseThrow();
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.PENDING.name());
        assertThat(approval.getParametersHash()).isNotBlank();
        assertThat(approval.getExpiresAt()).isAfter(SlashTime.now());
    }

    @Test
    @DisplayName("승인하면 그 자리에서 실행으로 이어진다")
    void 승인하면_실행한다() {
        CreateRequestResponse 접수 = 날씨를_요청한다();
        TasksRecord 작업 = 작업조회(접수.taskId());

        TaskStatus 결과 = taskService.decideApproval(
                사용자, 작업, ApprovalDecision.APPROVE, 승인버전(작업.getId()));

        assertThat(결과).isEqualTo(TaskStatus.SUCCEEDED);
        verify(weatherClient).lookup(any());
        assertThat(작업조회(접수.taskId()).getResult().data()).contains("서울특별시");
    }

    @Test
    @DisplayName("거절하면 아무것도 실행하지 않는다")
    void 거절하면_실행하지_않는다() {
        CreateRequestResponse 접수 = 날씨를_요청한다();
        TasksRecord 작업 = 작업조회(접수.taskId());

        TaskStatus 결과 = taskService.decideApproval(
                사용자, 작업, ApprovalDecision.REJECT, 승인버전(작업.getId()));

        assertThat(결과).isEqualTo(TaskStatus.FAILED);
        verify(weatherClient, never()).lookup(any());

        TasksRecord 마감된_작업 = 작업조회(접수.taskId());
        assertThat(마감된_작업.getErrorCode()).isEqualTo(ErrorCode.APPROVAL_REJECTED.name());
        assertThat(마감된_작업.getResult()).isNull();
    }

    @Test
    @DisplayName("같은 결정을 두 번 보내도 한 번만 반영된다")
    void 두_번_눌러도_한_번이다() {
        CreateRequestResponse 접수 = 날씨를_요청한다();
        TasksRecord 작업 = 작업조회(접수.taskId());
        int version = 승인버전(작업.getId());

        taskService.decideApproval(사용자, 작업, ApprovalDecision.APPROVE, version);

        // 화면이 낡은 값을 들고 다시 눌러도 두 번 실행되지 않는다.
        assertThatThrownBy(() ->
                taskService.decideApproval(사용자, 작업조회(접수.taskId()), ApprovalDecision.APPROVE, version))
                .isInstanceOf(SlashException.class);

        verify(weatherClient).lookup(any());
    }

    @Test
    @DisplayName("버전이 어긋나면 반영하지 않는다")
    void 버전이_어긋나면_거부한다() {
        CreateRequestResponse 접수 = 날씨를_요청한다();
        TasksRecord 작업 = 작업조회(접수.taskId());

        assertThatThrownBy(() ->
                taskService.decideApproval(사용자, 작업, ApprovalDecision.APPROVE, 99))
                .isInstanceOf(SlashException.class);

        verify(weatherClient, never()).lookup(any());
        assertThat(작업조회(접수.taskId()).getStatus()).isEqualTo(TaskStatus.WAITING_FOR_APPROVAL.name());
    }

    @Test
    @DisplayName("누가 무엇을 승인했는지 감사 기록에 남는다")
    void 감사에_남는다() {
        CreateRequestResponse 접수 = 날씨를_요청한다();
        TasksRecord 작업 = 작업조회(접수.taskId());

        int 이전 = 감사기록수(TaskApprovalService.ACTION_APPROVED);
        taskService.decideApproval(사용자, 작업, ApprovalDecision.APPROVE, 승인버전(작업.getId()));

        // 승인은 사용자가 책임을 지는 지점이다. "누가 이걸 허락했나" 를 답할 수 있어야 한다.
        assertThat(감사기록수(TaskApprovalService.ACTION_APPROVED)).isEqualTo(이전 + 1);
    }

    @Test
    @DisplayName("목록에 없는 결정 값은 사용자 오류로 답한다")
    void 모르는_결정값은_사용자_오류다() throws Exception {
        // 500 으로 나가면 프론트가 "잠시 후 다시" 로 안내하는데, 몇 번을 다시 보내도
        // 같은 본문이면 결과가 같다. 사용자가 고칠 수 있는 오류로 알려야 한다.
        CreateRequestResponse 접수 = 날씨를_요청한다();

        mockMvc.perform(post("/api/v1/tasks/{taskId}/approval", 접수.taskId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + 토큰)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .content("{\"decision\":\"MAYBE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_ERROR.name()));
    }

    @Test
    @DisplayName("본문이 깨져도 사용자 오류로 답한다")
    void 깨진_본문도_사용자_오류다() throws Exception {
        CreateRequestResponse 접수 = 날씨를_요청한다();

        mockMvc.perform(post("/api/v1/tasks/{taskId}/approval", 접수.taskId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + 토큰)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .content("{깨진 본문"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_ERROR.name()));
    }

    @Test
    @DisplayName("승인한 내용과 달라지면 실행하지 않는다")
    void 내용이_달라지면_거부한다() {
        CreateRequestResponse 접수 = 날씨를_요청한다();
        TasksRecord 작업 = 작업조회(접수.taskId());

        // 승인과 실행 사이에 입력값이 바뀐 상황을 만든다. 지금 구조에서는 이런 길이 없지만,
        // 확인을 두지 않으면 나중에 바뀔 수 있게 만드는 순간 아무도 알아채지 못한다.
        dsl.update(TASKS)
                .set(TASKS.PARAMETERS, JSONB.valueOf("{\"location\":\"부산\"}"))
                .where(TASKS.ID.eq(작업.getId()))
                .execute();

        TaskStatus 결과 = taskService.resumeAfterApproval(작업조회(접수.taskId()));

        assertThat(결과).isEqualTo(TaskStatus.FAILED);
        verify(weatherClient, never()).lookup(any());
        assertThat(작업조회(접수.taskId()).getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETERS.name());
    }
}
