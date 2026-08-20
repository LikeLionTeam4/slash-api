package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.AGENT_DISPATCHES;
import static com.likelion.slash.jooq.Tables.ASYNC_JOBS;
import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.TASK_EVENTS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AsyncJobStatus;
import com.likelion.slash.common.enums.AsyncJobType;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.ProcessingRoute;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.device.DeviceProjectWorkspaceRepository;
import com.likelion.slash.device.DeviceSearchFolderRepository;
import com.likelion.slash.device.ProjectWorkspace;
import com.likelion.slash.device.SearchFolder;
import com.likelion.slash.dispatch.TaskDispatcher;
import com.likelion.slash.jooq.tables.records.AsyncJobsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.llm.LlmReadiness;
import com.likelion.slash.llm.LlmSummaryRunner;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluDecision;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TaskService} 확인. (WBS W1-04)
 *
 * <p>여기가 종단 경로의 분기점이다. 요청 하나가 어느 길로 가는지, 그리고 <b>갈 수 없을 때
 * 어떻게 남는지</b>를 본다. 특히 PC 가 꺼져 있을 때 실패로 끝내지 않고 기다리는 것이
 * 참조 구현과 다른 지점이라 명시적으로 확인한다.
 *
 * <p>NLU 와 전달은 대역으로 바꾼다. 둘 다 밖으로 나가는 호출이고 각자의 시험이 따로 있다.
 */
@SpringBootTest
@Transactional
class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private DSLContext dsl;

    @Autowired
    private DeviceSearchFolderRepository deviceSearchFolderRepository;

    @Autowired
    private DeviceProjectWorkspaceRepository deviceProjectWorkspaceRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NluClient nluClient;

    @MockitoBean
    private TaskDispatcher taskDispatcher;

    /**
     * 실행만 막는다. 접수({@link com.likelion.slash.llm.LlmSummaryEnqueuer})는 실제로 돌려서
     * Task 전이와 원장이 한 트랜잭션에서 남는 것까지 본다. 모델 호출은 LlmSummaryRunnerTest 가 본다.
     */
    @MockitoBean
    private LlmSummaryRunner llmSummaryRunner;

    /** 실제 slash-llm 에 묻지 않는다. 판정 자체는 LlmReadinessTest 가 본다. */
    @MockitoBean
    private LlmReadiness llmReadiness;
    /** 밖으로 나가는 호출이다. 계약은 WeatherClientTest 가 본다. */
    @MockitoBean
    private WeatherClient weatherClient;

    private AuthenticatedUser 사용자;

    @BeforeEach
    void setUp() {
        long userId = 사용자(dsl);
        this.사용자 = new AuthenticatedUser(
                userId, UUID.randomUUID(), "tester@example.com", "시험 사용자",
                "Asia/Seoul", "ACTIVE", SlashTime.now());

        // 대역의 boolean 기본값은 거짓이라 명시하지 않으면 요약이 모두 거부된다.
        given(llmReadiness.canAccept()).willReturn(true);
    }

    // ------------------------------------------------------------------
    // 로컬 실행 경로
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PC 가 연결돼 있으면 QUEUED 로 두고 곧바로 내보낸다")
    void 연결된_PC_로_바로_내보낸다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("SYSTEM_STATUS"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);
        verify(taskDispatcher).dispatch(any(), anyLong());

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getTaskType()).isEqualTo("SYSTEM_STATUS");
        assertThat(작업.getProcessingRoute()).isEqualTo(ProcessingRoute.LOCAL_AGENT.name());
        assertThat(작업.getDeviceId()).isEqualTo(deviceId);
    }

    @Test
    @DisplayName("PC 가 꺼져 있어도 요청을 받아 WAITING_FOR_DEVICE 로 남긴다")
    void 꺼진_PC_의_요청도_받는다() {
        기기상태를(준비된_기기(dsl, 사용자.id()), DeviceStatus.OFFLINE);
        NLU가(작업분석("SYSTEM_STATUS"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.WAITING_FOR_DEVICE);

        // 전달을 미리 만들지 않는다. 언제 켜질지 모르는데 기한을 먼저 박으면 켜지기 전에 만료된다.
        verify(taskDispatcher, never()).dispatch(any(), anyLong());
    }

    @Test
    @DisplayName("PC 가 다시 붙으면 기다리던 작업을 내보낸다")
    void 다시_붙으면_밀린_작업을_내보낸다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        기기상태를(deviceId, DeviceStatus.OFFLINE);
        NLU가(작업분석("SYSTEM_STATUS"));

        UUID taskId = taskService.accept(사용자, new CreateRequestRequest("/status", null), null).taskId();

        int 내보낸건수 = taskService.dispatchWaiting(deviceId);

        assertThat(내보낸건수).isEqualTo(1);
        assertThat(작업조회(taskId).getStatus()).isEqualTo(TaskStatus.QUEUED.name());
        verify(taskDispatcher).dispatch(any(), anyLong());
    }

    @Test
    @DisplayName("등록된 PC 가 없으면 실패로 마감한다")
    void 등록된_PC_가_없으면_실패한다() {
        NLU가(작업분석("SYSTEM_STATUS"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.DEVICE_NOT_READY.name());
    }

    @Test
    @DisplayName("PC 가 꺼져 있으면 기다리는 작업이 있어도 새 요청을 받는다")
    void 꺼진_PC_는_여러_건을_받아_둔다() {
        기기상태를(준비된_기기(dsl, 사용자.id()), DeviceStatus.OFFLINE);
        NLU가(작업분석("SYSTEM_STATUS"));

        taskService.accept(사용자, new CreateRequestRequest("/status", null), null);
        CreateRequestResponse 두번째 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        // 꺼진 PC 는 아무것도 실행 중이 아니다. 여기서 막으면 미리 접수해 두는 것 자체가 안 된다.
        assertThat(두번째.status()).isEqualTo(TaskStatus.WAITING_FOR_DEVICE);
    }

    @Test
    @DisplayName("밀린 작업이 여러 건이어도 한 번에 한 건만 내보낸다")
    void 밀린_작업은_한_건씩_내보낸다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        기기상태를(deviceId, DeviceStatus.OFFLINE);
        NLU가(작업분석("SYSTEM_STATUS"));

        taskService.accept(사용자, new CreateRequestRequest("/status", null), null);
        taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        // uk_dispatch_active_device 가 기기당 활성 전달 한 건만 허용한다.
        assertThat(taskService.dispatchWaiting(deviceId)).isEqualTo(1);
        assertThat(taskService.dispatchWaiting(deviceId)).isZero();
    }

    @Test
    @DisplayName("사전 확인을 지나고 나서 기기를 뺏기면 DEVICE_BUSY 로 마감한다")
    void 전달_경쟁에서_지면_마감한다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("SYSTEM_STATUS"));

        // isDeviceOccupied 는 조회 시점의 스냅샷이라 동시에 들어온 두 요청을 갈라내지 못한다.
        // 최종 판정은 uk_dispatch_active_device 가 하고, 진 쪽은 여기서 예외를 받는다.
        given(taskDispatcher.dispatch(any(), anyLong()))
                .willThrow(new DuplicateKeyException("uk_dispatch_active_device"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        // 전달이 없는데 QUEUED 로 남으면 ACK·RESULT 를 영영 못 받아 무한 대기로 보인다.
        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.FAILED.name());
        assertThat(작업.getErrorCode()).isEqualTo(ErrorCode.DEVICE_BUSY.name());
        assertThat(dsl.fetchCount(AGENT_DISPATCHES, AGENT_DISPATCHES.DEVICE_ID.eq(deviceId))).isZero();
    }

    @Test
    @DisplayName("밀린 작업을 내보내다 기기를 뺏겨도 QUEUED 로 방치하지 않는다")
    void 대기작업_전달_경쟁에서_지면_마감한다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        기기상태를(deviceId, DeviceStatus.OFFLINE);
        NLU가(작업분석("SYSTEM_STATUS"));

        UUID taskId = taskService.accept(사용자, new CreateRequestRequest("/status", null), null).taskId();
        given(taskDispatcher.dispatch(any(), anyLong()))
                .willThrow(new DuplicateKeyException("uk_dispatch_active_device"));

        assertThat(taskService.dispatchWaiting(deviceId)).isZero();

        TasksRecord 작업 = 작업조회(taskId);
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.FAILED.name());
        assertThat(작업.getErrorCode()).isEqualTo(ErrorCode.DEVICE_BUSY.name());
    }

    @Test
    @DisplayName("PC 가 이미 다른 작업을 하고 있으면 받지 않는다 — P0 는 기기당 1건")
    void 실행_중인_PC_는_받지_않는다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        com.likelion.slash.support.TestFixtures.작업(dsl, 사용자.id(), deviceId, TaskStatus.RUNNING.name());
        NLU가(작업분석("SYSTEM_STATUS"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.DEVICE_BUSY.name());
    }

    // ------------------------------------------------------------------
    // 분석 결과별 분기
    // ------------------------------------------------------------------

    @Test
    @DisplayName("알아듣지 못한 요청은 UNRECOGNIZED_COMMAND 로 마감한다")
    void 못_알아들으면_실패한다() {
        NLU가(new NluAnalyzeResponse("r", NluDecision.UNSUPPORTED, null, Map.of(), List.of(), null, 0.1, "RULE_KIWI"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("음악 틀어줘", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.UNRECOGNIZED_COMMAND.name());
    }

    @Test
    @DisplayName("되물어야 하면 NEEDS_CLARIFICATION 으로 두고 질문을 남긴다")
    void 되물을_때는_질문을_남긴다() {
        NLU가(new NluAnalyzeResponse("r", NluDecision.CLARIFY, null, Map.of(),
                List.of("location"), "어느 지역 날씨를 알려드릴까요?", 0.6, "RULE_KIWI"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("날씨 알려줘", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.NEEDS_CLARIFICATION);
    }

    @Test
    @DisplayName("NLU 가 필수 입력값 누락을 알리면 되묻는다")
    void 필수값이_비면_되묻는다() {
        NLU가(new NluAnalyzeResponse("r", NluDecision.TASK, "TEXT_SUMMARY", Map.of(),
                List.of("text"), "무엇을 요약할까요?", 0.9, "SLASH"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/summary", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.NEEDS_CLARIFICATION);
    }

    @Test
    @DisplayName("서버가 채우는 값(searchFolderId)이 비었다고 되묻지 않는다")
    void 서버가_채우는_값으로는_되묻지_않는다() {
        준비된_기기(dsl, 사용자.id());
        // NLU 는 searchFolderId 를 누락값으로 보고하지 않지만, 보고하더라도 되물으면 안 된다.
        NLU가(new NluAnalyzeResponse("r", NluDecision.TASK, "FILE_SEARCH", Map.of("query", "보고서"),
                List.of("searchFolderId"), null, 0.9, "SLASH"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/file 보고서", null), null);

        assertThat(응답.status()).isNotEqualTo(TaskStatus.NEEDS_CLARIFICATION);
    }

    // ------------------------------------------------------------------
    // AI 도구 사용량 (P0-B)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("/usage 는 PC 로 보내고 도구 이름을 그대로 싣는다")
    void 사용량을_PC_로_보낸다() {
        준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("AI_AGENT_USAGE", Map.of("provider", "CLAUDE_CODE")));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/usage claude", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(작업조회(응답.taskId()).getProcessingRoute()).isEqualTo(ProcessingRoute.LOCAL_AGENT.name());
        assertThat(파라미터(응답.taskId(), "provider")).isEqualTo("CLAUDE_CODE");
    }

    @Test
    @DisplayName("도구 이름의 대소문자·이음표는 실행기가 아는 형태로 맞춰 보낸다")
    void 도구_이름을_맞춰_보낸다() {
        준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("AI_AGENT_USAGE", Map.of("provider", "claude-code")));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/usage claude", null), null);

        // 실행기의 COLLECTORS 는 정확한 이름만 받는다. 대소문자만 달라도 INVALID_PARAMETERS 다.
        assertThat(파라미터(응답.taskId(), "provider")).isEqualTo("CLAUDE_CODE");
    }

    @Test
    @DisplayName("모르는 도구는 PC 로 보내지 않고 INVALID_PARAMETERS 로 마감한다")
    void 모르는_도구는_보내지_않는다() {
        준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("AI_AGENT_USAGE", Map.of("provider", "gpt")));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/usage gpt", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETERS.name());
        verify(taskDispatcher, never()).dispatch(any(), anyLong());
    }

    @Test
    @DisplayName("PC 가 없어도 입력값이 잘못된 것을 먼저 알린다")
    void 입력값_오류를_기기보다_먼저_알린다() {
        // 기기를 등록하지 않았다. 기기를 먼저 보면 DEVICE_NOT_READY 가 나가서, 실제 원인이
        // 입력값이라는 것을 사용자가 알 수 없다.
        NLU가(작업분석("AI_AGENT_USAGE", Map.of("provider", "gpt")));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/usage gpt", null), null);

        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETERS.name());
    }

    // ------------------------------------------------------------------
    // 코드 분석 (P0-B)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("/code 는 PC 가 보고한 프로젝트 폴더 하나를 workspaceId 로 채워 보낸다")
    void 프로젝트폴더를_채워_보낸다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        프로젝트폴더가(deviceId, new ProjectWorkspace(
                "ws-1", "slash-api", ProjectWorkspace.GIT_REPOSITORY, List.of("CLAUDE_CODE")));
        NLU가(작업분석("CODE_ANALYSIS", Map.of("query", "이 프로젝트 구조 설명해줘")));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/code 이 프로젝트 구조 설명해줘", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);
        // Agent 는 이 값으로 자기 폴더를 되찾는다. 서버는 경로를 모른다.
        assertThat(파라미터(응답.taskId(), "workspaceId")).isEqualTo("ws-1");
        assertThat(파라미터(응답.taskId(), "query")).isEqualTo("이 프로젝트 구조 설명해줘");
    }

    @Test
    @DisplayName("무엇을 물어볼지 없으면 되묻는다 — 빈 질문으로 CLI 를 돌리지 않는다")
    void 질문이_없으면_되묻는다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        프로젝트폴더가(deviceId, new ProjectWorkspace(
                "ws-1", "slash-api", ProjectWorkspace.GIT_REPOSITORY, List.of("CLAUDE_CODE")));

        // 실행기는 query 를 검증하지 않는다. 빈 질문으로 보내면 CLI 가 최대 300초를 쓰고
        // 의미 없는 답을 돌려준다. 서버가 여기서 막아야 한다.
        NLU가(new NluAnalyzeResponse("r", NluDecision.TASK, "CODE_ANALYSIS", Map.of(),
                List.of("query"), "무엇을 분석할까요?", 1.0, "SLASH"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/code", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.NEEDS_CLARIFICATION);
        verify(taskDispatcher, never()).dispatch(any(), anyLong());
    }

    @Test
    @DisplayName("프로젝트 폴더가 없으면 WORKSPACE_NOT_FOUND 로 마감한다")
    void 프로젝트폴더가_없으면_마감한다() {
        준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("CODE_ANALYSIS", Map.of("query", "설명해줘")));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/code 설명해줘", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.WORKSPACE_NOT_FOUND.name());
        verify(taskDispatcher, never()).dispatch(any(), anyLong());
    }

    @Test
    @DisplayName("폴더는 있는데 도구가 없으면 CODE_AGENT_NOT_CONFIGURED 로 나눠 알린다")
    void 도구가_없으면_다르게_알린다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        // 폴더는 등록했지만 Claude Code·Codex 가 설치되지 않은 PC 다.
        프로젝트폴더가(deviceId, new ProjectWorkspace(
                "ws-1", "slash-api", ProjectWorkspace.GIT_REPOSITORY, List.of()));
        NLU가(작업분석("CODE_ANALYSIS", Map.of("query", "설명해줘")));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/code 설명해줘", null), null);

        // 사용자가 할 일이 다르다 — 폴더를 등록하는 것이 아니라 CLI 를 설치하는 것이다.
        // 둘 다 "폴더를 추가해 주세요" 로 안내하면 몇 번을 등록해도 같은 실패를 본다.
        assertThat(작업조회(응답.taskId()).getErrorCode())
                .isEqualTo(ErrorCode.CODE_AGENT_NOT_CONFIGURED.name());
    }

    // ------------------------------------------------------------------
    // 파일 열기 (P0-B)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("/open 은 파일 표식을 그대로 PC 로 보낸다")
    void 파일_열기를_PC_로_보낸다() {
        준비된_기기(dsl, 사용자.id());
        // fileRef 는 FILE_SEARCH 결과가 준 값이다. 서버는 무엇을 가리키는지 알지 못한 채 옮긴다.
        NLU가(작업분석("FILE_OPEN", Map.of("fileRef", "f62dfe8a-8525-4ba9-a0b5-7f6d70ebfedd")));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/open f62dfe8a-8525-4ba9-a0b5-7f6d70ebfedd", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(작업조회(응답.taskId()).getProcessingRoute()).isEqualTo(ProcessingRoute.LOCAL_AGENT.name());
        assertThat(파라미터(응답.taskId(), "fileRef")).isEqualTo("f62dfe8a-8525-4ba9-a0b5-7f6d70ebfedd");
    }

    @Test
    @DisplayName("/open 은 검색 폴더를 채우지 않는다 — 파일 표식만으로 찾는다")
    void 파일_열기에는_검색폴더가_필요없다() {
        준비된_기기(dsl, 사용자.id());
        // 검색 폴더를 보고받지 않은 기기여도 FILE_SEARCH 와 달리 막히지 않아야 한다.
        NLU가(작업분석("FILE_OPEN", Map.of("fileRef", "f62dfe8a-8525-4ba9-a0b5-7f6d70ebfedd")));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/open f62dfe8a-8525-4ba9-a0b5-7f6d70ebfedd", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isNull();
    }

    // ------------------------------------------------------------------
    // 검색 폴더 (WBS W1-03)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("/file 은 PC 가 보고한 폴더 중 하나를 searchFolderId 로 채워 보낸다")
    void 검색폴더를_채워_보낸다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        검색폴더가(deviceId, new SearchFolder("sf-1", "문서", SearchFolder.INDEXED));
        NLU가(작업분석("FILE_SEARCH", Map.of("query", "보고서")));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/file 보고서", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);

        // Agent 는 이 값으로 자기 폴더를 되찾는다. 우리가 만든 값이 아니라 받은 값 그대로여야 한다.
        // JSONB 는 PostgreSQL 이 정규화하므로 문자열이 아니라 값으로 본다.
        assertThat(파라미터(응답.taskId(), "searchFolderId")).isEqualTo("sf-1");
        assertThat(파라미터(응답.taskId(), "query")).isEqualTo("보고서");
    }

    @Test
    @DisplayName("검색할 폴더가 없으면 SEARCH_FOLDER_NOT_FOUND 로 마감한다")
    void 폴더가_없으면_마감한다() {
        준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("FILE_SEARCH", Map.of("query", "보고서")));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/file 보고서", null), null);

        // 채우지 못한 채 보내면 Agent 가 INVALID_PARAMETERS 로 거절한다. 여기서 끝내는 편이 이유가 분명하다.
        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.SEARCH_FOLDER_NOT_FOUND.name());
        verify(taskDispatcher, never()).dispatch(any(), anyLong());
    }

    @Test
    @DisplayName("색인 중인 폴더뿐이어도 보낸다 — 버리면 재접속 전까지 /file 이 죽는다")
    void 색인_중이어도_보낸다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        검색폴더가(deviceId, new SearchFolder("sf-1", "문서", SearchFolder.INDEXING));
        NLU가(작업분석("FILE_SEARCH", Map.of("query", "보고서")));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/file 보고서", null), null);

        // Agent 의 is_searchable() 은 UNAVAILABLE 만 거른다. 색인 중이어도 검색해 준다.
        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(파라미터(응답.taskId(), "searchFolderId")).isEqualTo("sf-1");
    }

    @Test
    @DisplayName("읽을 수 없는 폴더뿐이면 SEARCH_FOLDER_NOT_FOUND 로 마감한다")
    void 읽을_수_없으면_마감한다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        검색폴더가(deviceId, new SearchFolder("sf-1", "문서", SearchFolder.UNAVAILABLE));
        NLU가(작업분석("FILE_SEARCH", Map.of("query", "보고서")));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/file 보고서", null), null);

        // 보내 봐야 Agent 가 SEARCH_FOLDER_NOT_FOUND 로 거절한다. 여기서 끝내는 편이 빠르다.
        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.SEARCH_FOLDER_NOT_FOUND.name());
    }

    @Test
    @DisplayName("PC 가 꺼져 있어도 폴더가 있으면 접수한다")
    void 꺼진_PC_도_폴더가_있으면_받는다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        검색폴더가(deviceId, new SearchFolder("sf-1", "문서", SearchFolder.INDEXED));
        기기상태를(deviceId, DeviceStatus.OFFLINE);
        NLU가(작업분석("FILE_SEARCH", Map.of("query", "보고서")));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/file 보고서", null), null);

        // 지난 연결에서 보고한 목록을 본다. 그 사이 폴더를 뺐다면 Agent 가 ACK 로 거절한다.
        assertThat(응답.status()).isEqualTo(TaskStatus.WAITING_FOR_DEVICE);
        assertThat(파라미터(응답.taskId(), "searchFolderId")).isEqualTo("sf-1");
    }

    // ------------------------------------------------------------------
    // 작업 수신 중지 (#24)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("수신을 꺼 두면 붙어 있어도 보내지 않고 기다린다")
    void 수신을_끄면_기다린다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        수신을(deviceId, false);
        NLU가(작업분석("SYSTEM_STATUS"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        // 실패로 마감하지 않는다. 다시 켜면 나가야 하므로 꺼진 PC 와 같은 자리에 둔다.
        assertThat(응답.status()).isEqualTo(TaskStatus.WAITING_FOR_DEVICE);
        verify(taskDispatcher, never()).dispatch(any(), anyLong());

        // 꺼진 PC 와 구분해 알린다. 켜져 있는 PC 를 두고 무엇을 기다려야 하는지 알 수 있어야 한다.
        assertThat(마지막_안내(응답.taskId())).contains("수신");
    }

    @Test
    @DisplayName("꺼져 있으면서 수신도 꺼 둔 PC 는 연결부터 안내한다")
    void 꺼진_PC_는_연결을_먼저_안내한다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        수신을(deviceId, false);
        기기상태를(deviceId, DeviceStatus.OFFLINE);
        NLU가(작업분석("SYSTEM_STATUS"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        // 둘 다 풀어야 실행된다. 수신만 켜라고 하면 그대로 했는데도 아무 일이 없는 것으로 보인다.
        assertThat(마지막_안내(응답.taskId())).contains("연결");
    }

    @Test
    @DisplayName("수신을 꺼 둔 기기에는 밀린 작업도 내보내지 않는다")
    void 수신을_끄면_밀린_작업도_멈춘다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        기기상태를(deviceId, DeviceStatus.OFFLINE);
        NLU가(작업분석("SYSTEM_STATUS"));
        taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        기기상태를(deviceId, DeviceStatus.READY);
        수신을(deviceId, false);

        // 접수 경로에서만 막으면 꺼 두기 전에 쌓인 작업이 재연결과 함께 쏟아진다.
        assertThat(taskService.dispatchWaiting(deviceId)).isZero();
    }

    @Test
    @DisplayName("다시 켜면 기다리던 작업이 나간다")
    void 다시_켜면_나간다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        수신을(deviceId, false);
        NLU가(작업분석("SYSTEM_STATUS"));
        UUID taskId = taskService.accept(사용자, new CreateRequestRequest("/status", null), null).taskId();

        수신을(deviceId, true);

        assertThat(taskService.dispatchWaiting(deviceId)).isEqualTo(1);
        assertThat(작업조회(taskId).getStatus()).isEqualTo(TaskStatus.QUEUED.name());
    }

    @Test
    @DisplayName("수신을 꺼 둔 PC 보다 받는 PC 를 먼저 고른다")
    void 받는_PC_를_먼저_고른다() {
        long 꺼둔_기기 = 준비된_기기(dsl, 사용자.id());
        수신을(꺼둔_기기, false);
        long 받는_기기 = 준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("SYSTEM_STATUS"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(작업조회(응답.taskId()).getDeviceId()).isEqualTo(받는_기기);
    }

    @Test
    @DisplayName("폴더를 쓰지 않는 작업에는 searchFolderId 를 넣지 않는다")
    void 다른_작업에는_넣지_않는다() {
        long deviceId = 준비된_기기(dsl, 사용자.id());
        검색폴더가(deviceId, new SearchFolder("sf-1", "문서", SearchFolder.INDEXED));
        NLU가(작업분석("SYSTEM_STATUS"));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        assertThat(파라미터(응답.taskId(), "searchFolderId")).isNull();
    }

    private void 검색폴더가(long deviceId, SearchFolder... folders) {
        deviceSearchFolderRepository.replaceAll(deviceId, List.of(folders));
    }

    private void 프로젝트폴더가(long deviceId, ProjectWorkspace... workspaces) {
        deviceProjectWorkspaceRepository.replaceAll(deviceId, List.of(workspaces));
    }

    /** 저장된 작업 입력값에서 값 하나를 꺼낸다. 없으면 {@code null}. */
    private String 파라미터(UUID taskId, String name) {
        try {
            JsonNode parameters = objectMapper.readTree(작업조회(taskId).getParameters().data());
            return parameters.hasNonNull(name) ? parameters.get(name).asText() : null;
        } catch (Exception e) {
            throw new AssertionError("작업 입력값을 읽지 못했습니다.", e);
        }
    }

    @Test
    @DisplayName("NLU 를 부르지 못하면 NLU_UNAVAILABLE 로 마감한다")
    void NLU_장애는_실패로_마감한다() {
        given(nluClient.analyze(any(), any(), any())).willThrow(new SlashException(ErrorCode.NLU_UNAVAILABLE));

        CreateRequestResponse 응답 = taskService.accept(사용자, new CreateRequestRequest("/status", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.NLU_UNAVAILABLE.name());
    }

    // ------------------------------------------------------------------
    // 날씨 경로
    // ------------------------------------------------------------------

    @Test
    @DisplayName("날씨는 PC 없이 서버가 조회해 바로 마감한다")
    void 날씨를_조회한다() {
        날씨가(new WeatherOutcome.Success(
                new GeocodingResponse.Place("수원시", 37.29, 127.01, "대한민국", "KR", "경기도", "Asia/Seoul"),
                new ForecastResponse.Current("2026-08-19T11:00", 27.4, 32.7, 78, 0.0, 2, 2.6)));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/weather 수원", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.SUCCEEDED);

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getProcessingRoute()).isEqualTo(ProcessingRoute.BACKEND_SERVICE.name());
        assertThat(작업.getDeviceId()).isNull();

        // 사용자가 말한 "수원" 과 실제로 조회한 곳이 다를 수 있어 찾아낸 지명을 함께 싣는다.
        assertThat(작업.getResult().data())
                .contains("수원시").contains("경기도")
                .contains("27.4").contains("구름 조금");

        // 계약 문서 §2.1 에 적어 둔 필드가 그대로 나가야 한다. 화면이 그 표를 보고 타입을
        // 만들기 때문에, 이름을 바꾸거나 빠뜨리면 문서가 거짓이 된다.
        assertThat(작업.getResult().data())
                .contains("\"location\"").contains("\"region\"").contains("\"country\"")
                .contains("\"temperature\"").contains("\"apparentTemperature\"")
                .contains("\"humidity\"").contains("\"precipitation\"").contains("\"windSpeed\"")
                .contains("\"description\"").contains("\"observedAt\"");
    }

    @Test
    @DisplayName("지역을 못 찾은 것과 서비스가 멈춘 것을 나눠 알린다")
    void 지역을_못_찾으면_다르게_알린다() {
        날씨가(new WeatherOutcome.Failure(ErrorCode.LOCATION_NOT_FOUND, "찾지 못했습니다."));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/weather 없는동네", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);

        // UPSTREAM_UNAVAILABLE 로 뭉뚱그리면 사용자가 다시 말하면 된다는 것을 알 수 없다.
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.LOCATION_NOT_FOUND.name());
    }

    @Test
    @DisplayName("날씨 서비스에 닿지 못하면 작업을 마감한다")
    void 날씨_서비스가_멈추면_마감한다() {
        날씨가(new WeatherOutcome.Failure(ErrorCode.UPSTREAM_UNAVAILABLE, "가져오지 못했습니다."));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/weather 서울", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE.name());
    }

    // ------------------------------------------------------------------
    // 멱등
    // ------------------------------------------------------------------

    @Test
    @DisplayName("같은 키로 같은 요청을 다시 보내면 작업을 새로 만들지 않는다")
    void 같은_키_같은_본문은_같은_작업이다() {
        준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("SYSTEM_STATUS"));
        CreateRequestRequest 요청 = new CreateRequestRequest("/status", null);

        UUID 첫번째 = taskService.accept(사용자, 요청, "key-1").taskId();
        UUID 두번째 = taskService.accept(사용자, 요청, "key-1").taskId();

        assertThat(두번째).isEqualTo(첫번째);
        assertThat(dsl.fetchCount(TASKS, TASKS.USER_ID.eq(사용자.id()))).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키에 다른 내용이 오면 거부한다")
    void 같은_키_다른_본문은_거부한다() {
        준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("SYSTEM_STATUS"));

        taskService.accept(사용자, new CreateRequestRequest("/status", null), "key-2");

        assertThatThrownBy(() ->
                taskService.accept(사용자, new CreateRequestRequest("/file 보고서", null), "key-2"))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    @Test
    @DisplayName("남의 작업은 찾을 수 없다")
    void 남의_작업은_보이지_않는다() {
        준비된_기기(dsl, 사용자.id());
        NLU가(작업분석("SYSTEM_STATUS"));
        UUID taskId = taskService.accept(사용자, new CreateRequestRequest("/status", null), null).taskId();

        AuthenticatedUser 남 = new AuthenticatedUser(사용자(dsl), UUID.randomUUID(), "other@example.com",
                "남", "Asia/Seoul", "ACTIVE", SlashTime.now());

        assertThatThrownBy(() -> taskService.findOwned(남, taskId))
                .isInstanceOf(SlashException.class)
                .extracting(e -> ((SlashException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 요약 경로
    // ------------------------------------------------------------------

    @Test
    @DisplayName("요약은 QUEUED 로 답하고 실행은 뒤로 넘긴다")
    void 요약을_맡긴다() {
        NLU가(작업분석("TEXT_SUMMARY", Map.of("text", "요약할 긴 글")));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/summary 요약할 긴 글", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);

        TasksRecord 작업 = 작업조회(응답.taskId());
        assertThat(작업.getTaskType()).isEqualTo("TEXT_SUMMARY");
        assertThat(작업.getProcessingRoute()).isEqualTo(ProcessingRoute.LLM_SERVICE.name());

        // 요약은 서버 쪽 모델이 하는 일이라 PC 를 붙들지 않는다.
        assertThat(작업.getDeviceId()).isNull();
        verify(taskDispatcher, never()).dispatch(any(), anyLong());
    }

    @Test
    @DisplayName("요약을 맡기면 원장을 남기고 실행을 시작한다")
    void 요약_원장을_남긴다() {
        NLU가(작업분석("TEXT_SUMMARY", Map.of("text", "요약할 긴 글")));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/summary 요약할 긴 글", null), null);

        long taskId = 작업조회(응답.taskId()).getId();
        AsyncJobsRecord 원장 = dsl.selectFrom(ASYNC_JOBS)
                .where(ASYNC_JOBS.TASK_ID.eq(taskId))
                .fetchOne();

        assertThat(원장).isNotNull();
        assertThat(원장.getJobType()).isEqualTo(AsyncJobType.TEXT_SUMMARY.name());

        // PENDING 이 아니라 QUEUED 다 — 원장만 남기고 아무에게도 맡기지 않으면 아무도 집지 않는다.
        assertThat(원장.getStatus()).isEqualTo(AsyncJobStatus.QUEUED.name());
        assertThat(원장.getDeadlineAt()).isAfter(SlashTime.now());

        verify(llmSummaryRunner).runAsync(
                eq(원장.getId()), eq(taskId), any(), eq(응답.taskId()), eq("요약할 긴 글"));
    }

    @Test
    @DisplayName("모델이 받을 수 없으면 원장을 만들지 않고 바로 알린다")
    void 준비되지_않으면_접수하지_않는다() {
        given(llmReadiness.canAccept()).willReturn(false);
        given(llmReadiness.reason()).willReturn(java.util.Optional.of("OLLAMA_UNAVAILABLE"));
        NLU가(작업분석("TEXT_SUMMARY", Map.of("text", "요약할 긴 글")));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/summary 요약할 긴 글", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(작업조회(응답.taskId()).getErrorCode()).isEqualTo(ErrorCode.LLM_NOT_READY.name());

        // 만들어 두면 호출했다가 실패로 마감하는 일을 반복한다.
        assertThat(dsl.fetchCount(ASYNC_JOBS, ASYNC_JOBS.TASK_ID.eq(작업조회(응답.taskId()).getId()))).isZero();
        verify(llmSummaryRunner, never()).runAsync(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("PC 가 없어도 요약은 받는다")
    void PC_없이도_요약한다() {
        // 기기를 하나도 만들지 않는다.
        NLU가(작업분석("TEXT_SUMMARY", Map.of("text", "요약할 긴 글")));

        CreateRequestResponse 응답 = taskService.accept(
                사용자, new CreateRequestRequest("/summary 요약할 긴 글", null), null);

        assertThat(응답.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(마지막_안내(응답.taskId())).isEqualTo("요약을 맡겼습니다.");
    }

    private void 날씨가(WeatherOutcome 결과) {
        NLU가(작업분석("WEATHER_LOOKUP", Map.of("location", "수원")));
        given(weatherClient.lookup(any())).willReturn(결과);
    }

    private void NLU가(NluAnalyzeResponse 응답) {
        given(nluClient.analyze(any(), any(), any())).willReturn(응답);
    }

    private NluAnalyzeResponse 작업분석(String taskType) {
        return 작업분석(taskType, Map.of());
    }

    private NluAnalyzeResponse 작업분석(String taskType, Map<String, Object> parameters) {
        return new NluAnalyzeResponse("r", NluDecision.TASK, taskType, parameters, List.of(), null, 1.0, "SLASH");
    }

    private void 기기상태를(long deviceId, DeviceStatus status) {
        dsl.update(DEVICES).set(DEVICES.STATUS, status.name()).where(DEVICES.ID.eq(deviceId)).execute();
    }

    /** 타임라인에 마지막으로 남은 안내 문구. 화면이 사용자에게 보여주는 값이다. */
    private String 마지막_안내(UUID taskId) {
        return dsl.select(TASK_EVENTS.MESSAGE)
                .from(TASK_EVENTS)
                .where(TASK_EVENTS.TASK_ID.eq(작업조회(taskId).getId()))
                .orderBy(TASK_EVENTS.SEQUENCE.desc())
                .limit(1)
                .fetchOne(TASK_EVENTS.MESSAGE);
    }

    private void 수신을(long deviceId, boolean accepting) {
        dsl.update(DEVICES).set(DEVICES.ACCEPTING_TASKS, accepting).where(DEVICES.ID.eq(deviceId)).execute();
    }

    private TasksRecord 작업조회(UUID publicId) {
        return taskRepository.findByPublicIdAndUserId(publicId, 사용자.id()).orElseThrow();
    }
}
