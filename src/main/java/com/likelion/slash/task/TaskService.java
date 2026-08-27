package com.likelion.slash.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.approval.ApprovalPolicy;
import com.likelion.slash.approval.TaskApprovalRepository;
import com.likelion.slash.approval.TaskApprovalService;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.Sha256;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AiAgentProvider;
import com.likelion.slash.common.enums.ApprovalDecision;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.ExecutionTarget;
import com.likelion.slash.common.enums.SummaryEngine;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.device.DeviceCapabilityRepository;
import com.likelion.slash.device.DeviceProjectWorkspaceRepository;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.device.DeviceSearchFolderRepository;
import com.likelion.slash.dispatch.TaskDispatcher;
import com.likelion.slash.jooq.tables.records.AsyncJobsRecord;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import com.likelion.slash.jooq.tables.records.TaskApprovalsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.llm.LlmReadiness;
import com.likelion.slash.llm.LlmSummaryEnqueuer;
import com.likelion.slash.llm.LlmSummaryRunner;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.NluSummaryClient;
import com.likelion.slash.nlu.SummaryOutcome;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.nlu.dto.NluSummaryResponse;
import com.likelion.slash.task.TaskStateWriter.RawTextDisposal;
import com.likelion.slash.task.dto.BrowserSummaryResultRequest;
import com.likelion.slash.task.dto.CreateRequestRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
import com.likelion.slash.weather.WeatherClient;
import com.likelion.slash.weather.WeatherCode;
import com.likelion.slash.weather.WeatherOutcome;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 요청 접수부터 실행 위치 결정까지. (WBS W1-04)
 *
 * <pre>
 *   접수 → ANALYZING → NLU 분석 → 처리 경로 결정 → QUEUED(전달) 또는 WAITING_FOR_DEVICE
 * </pre>
 *
 * <p><b>분석과 전달을 요청 안에서 끝낸다.</b> 참조 구현은 202 를 먼저 돌려주고 뒤에서
 * 이어가지만, 여기서는 응답이 나갈 때 이미 전달까지 마친 상태로 둔다. NLU 호출이 2초로
 * 잘려 있어 최악이 예측 가능하고, 별도 실행기 없이 상태 흐름을 그대로 따라갈 수 있다.
 * 비동기가 필요해지는 것은 LLM 경로를 붙일 때다.
 *
 * <p><b>PC 가 꺼져 있어도 요청을 받는다.</b> 참조 구현은 기기가 READY 가 아니면 곧바로
 * 실패로 끝내지만, 우리는 {@code WAITING_FOR_DEVICE} 로 남긴다. 클라우드가 없으면 성립하지
 * 않는 동작이고 표({@code ck_tasks_status})와 전이 규칙에 이미 들어 있다.
 * 밀린 작업은 Agent 가 READY 를 보고할 때 {@link #dispatchWaiting} 이 내보낸다.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** FILE_SEARCH 의 서버가 채우는 입력값. Agent 가 READY 로 보고한 폴더 중 하나를 넣는다. */
    private static final String PARAMETER_SEARCH_FOLDER_ID = "searchFolderId";

    /** TEXT_SUMMARY 의 원문. NLU 가 채운다. */
    private static final String PARAMETER_TEXT = "text";

    /**
     * 원문을 걷어낸 자리에 남기는 글자 수. <b>원문을 버릴 때만</b> 넣는다.
     *
     * <p>그래서 이 값이 있으면 {@code input_text} 는 원문이 아니다 —
     * {@link #inputTextIsOriginal} 이 그것으로 판단한다. 정리 SQL
     * ({@link TaskRepository#dropRawTextFromSucceededSummaries}) 도 같은 키를 쓴다.
     */
    private static final String PARAMETER_INPUT_LENGTH = "inputLength";

    /** WEATHER_LOOKUP 의 지명. NLU 가 채운다. */
    private static final String PARAMETER_LOCATION = "location";

    /** AI_AGENT_USAGE 의 대상 도구. NLU 가 채운다. */
    private static final String PARAMETER_PROVIDER = "provider";

    /** CODE_ANALYSIS 의 분석 대상 폴더. Agent 가 READY 로 보고한 것 중 서버가 고른다. */
    private static final String PARAMETER_WORKSPACE_ID = "workspaceId";

    /** 멱등 기록의 범위. 같은 키라도 다른 Endpoint 면 별개로 본다. */
    private static final String REQUEST_PATH = "/api/v1/requests";

    /** 브라우저 요약 결과 제출의 멱등 범위. (slash-docs#3 권장 순서 3번) */
    private static final String BROWSER_SUMMARY_RESULT_PATH = "/api/v1/tasks/text-summary/browser-result";

    /** 멱등 기록 보존 기간. (문서 3.4.6) */
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final TaskRepository taskRepository;
    private final TaskStateWriter stateWriter;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceCapabilityRepository deviceCapabilityRepository;
    private final DeviceSearchFolderRepository deviceSearchFolderRepository;
    private final DeviceProjectWorkspaceRepository deviceProjectWorkspaceRepository;
    private final NluClient nluClient;
    private final TaskDispatcher taskDispatcher;
    private final ObjectMapper objectMapper;
    private final WeatherClient weatherClient;
    /**
     * GPU 요약을 쓸 때만 있는 빈이다. CPU 추출 요약이 기본이 되면서 조건부가 됐다.
     * ({@code slash.summary.engine=GEMMA} · slash-docs#3)
     */
    private final ObjectProvider<LlmReadiness> llmReadiness;
    private final LlmSummaryEnqueuer llmSummaryEnqueuer;
    private final LlmSummaryRunner llmSummaryRunner;
    private final NluSummaryClient nluSummaryClient;
    private final ApprovalPolicy approvalPolicy;
    private final TaskApprovalService approvalService;
    private final TaskApprovalRepository approvalRepository;

    /** 요약을 무엇으로 할지. 실행할 때 고르는 값이라 설정으로 둔다. (slash-docs#3) */
    private final SummaryEngine summaryEngine;

    /**
     * {@code TEXT_SUMMARY} 를 선택한 PC(RUNNER)로 보낼지. 기본값은 {@code false} —
     * PC 쪽 요약 어댑터(Claude Code/Codex CLI 실행)의 도구 차단이 아직 OS 수준으로
     * 이중화되지 않아(slash-docs#3, 2026-08-24 보안 검토), 그 작업이 끝나기 전까지는
     * 이 경로로 보내지 않는다. 배포 없이 다시 열 수 있게 코드에서 분기를 지우지 않고
     * 설정으로 둔다 — {@link ApprovalPolicy} 와 같은 이유다.
     */
    private final boolean textSummaryRunnerEnabled;

    /**
     * 요약 작업의 기한. 이 시각까지 끝나지 않으면 스윕이 마감한다.
     *
     * <p>전달 기한과 따로 두는 이유 — 전달은 켜져 있는 PC 에만 만들어 60초면 충분하지만,
     * 요약은 모델이 밀려 있으면 그보다 오래 걸린다.
     */
    private final Duration summaryDeadline;

    public TaskService(TaskRepository taskRepository,
                       TaskStateWriter stateWriter,
                       IdempotencyRecordRepository idempotencyRecordRepository,
                       DeviceRepository deviceRepository,
                       DeviceCapabilityRepository deviceCapabilityRepository,
                       DeviceSearchFolderRepository deviceSearchFolderRepository,
                       DeviceProjectWorkspaceRepository deviceProjectWorkspaceRepository,
                       NluClient nluClient,
                       TaskDispatcher taskDispatcher,
                       ObjectMapper objectMapper,
                       WeatherClient weatherClient,
                       ObjectProvider<LlmReadiness> llmReadiness,
                       LlmSummaryEnqueuer llmSummaryEnqueuer,
                       LlmSummaryRunner llmSummaryRunner,
                       NluSummaryClient nluSummaryClient,
                       ApprovalPolicy approvalPolicy,
                       TaskApprovalService approvalService,
                       TaskApprovalRepository approvalRepository,
                       @Value("${slash.summary.engine}") SummaryEngine summaryEngine,
                       @Value("${slash.llm.job-deadline}") Duration summaryDeadline,
                       @Value("${slash.text-summary.runner-enabled}") boolean textSummaryRunnerEnabled) {
        this.taskRepository = taskRepository;
        this.stateWriter = stateWriter;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.deviceRepository = deviceRepository;
        this.deviceCapabilityRepository = deviceCapabilityRepository;
        this.deviceSearchFolderRepository = deviceSearchFolderRepository;
        this.deviceProjectWorkspaceRepository = deviceProjectWorkspaceRepository;
        this.nluClient = nluClient;
        this.nluSummaryClient = nluSummaryClient;
        this.approvalPolicy = approvalPolicy;
        this.approvalService = approvalService;
        this.approvalRepository = approvalRepository;
        this.summaryEngine = summaryEngine;
        this.taskDispatcher = taskDispatcher;
        this.objectMapper = objectMapper;
        this.weatherClient = weatherClient;
        this.llmReadiness = llmReadiness;
        this.llmSummaryEnqueuer = llmSummaryEnqueuer;
        this.llmSummaryRunner = llmSummaryRunner;
        this.summaryDeadline = summaryDeadline;
        this.textSummaryRunnerEnabled = textSummaryRunnerEnabled;
    }

    // ------------------------------------------------------------------
    // 접수
    // ------------------------------------------------------------------

    /**
     * 요청을 접수하고 실행까지 밀어 넣는다.
     *
     * <p>분석에 실패해도 예외를 던지지 않는다. 이미 접수된 요청이라 Task 를 실패로 마감하고
     * 그 상태를 돌려주는 것이 맞다. 화면은 접수 응답과 조회 응답을 같은 규칙으로 다룰 수 있다.
     *
     * @param idempotencyKey {@code Idempotency-Key} 헤더. 없으면 {@code null}
     */
    public CreateRequestResponse accept(AuthenticatedUser user,
                                        CreateRequestRequest request,
                                        String idempotencyKey) {

        String requestHash = requestHash(request);

        if (idempotencyKey != null) {
            Optional<CreateRequestResponse> replayed = replay(user, idempotencyKey, REQUEST_PATH, requestHash);
            if (replayed.isPresent()) {
                return replayed.get();
            }
        }

        UUID correlationId = UUID.randomUUID();
        Optional<TasksRecord> created = stateWriter.create(
                user.id(), request.text(), correlationId, summarize(request.text()),
                claimOf(idempotencyKey, REQUEST_PATH, requestHash));

        if (created.isEmpty()) {
            return followWinner(user, idempotencyKey, REQUEST_PATH, requestHash);
        }
        TasksRecord task = created.get();

        if (!stateWriter.move(task.getId(), TaskStatus.CREATED, TaskStatus.ANALYZING, null, "요청 분석 시작")) {
            return currentStateOf(task.getId(), task.getPublicId());
        }

        TaskStatus finalStatus = analyzeAndRoute(user, task, request);
        return CreateRequestResponse.of(task.getPublicId(), finalStatus);
    }

    /**
     * 같은 키로 이미 처리한 요청이 있는지 본다.
     *
     * <p>같은 키 + 같은 본문이면 그때 만든 작업의 <b>현재</b> 상태를 돌려준다. 접수 당시의
     * 상태를 저장해 두었다가 그대로 되돌려주면, 재전송한 화면만 옛 상태를 보게 된다.
     *
     * <p>요청 형태(레코드 타입)에 매이지 않도록 경로와 해시만 받는다 — 접수(
     * {@code CreateRequestRequest})와 브라우저 요약 결과 제출({@link BrowserSummaryResultRequest})
     * 이 같은 멱등 처리를 쓰되 서로 다른 경로·해시 규칙을 갖는다.
     *
     * @return 재전송으로 판정했으면 그때의 작업, 처음 보는 키면 비어 있음
     * @throws SlashException 같은 키에 다른 본문이 왔을 때 {@link ErrorCode#IDEMPOTENCY_CONFLICT}
     */
    private Optional<CreateRequestResponse> replay(AuthenticatedUser user,
                                                   String idempotencyKey,
                                                   String requestPath,
                                                   String requestHash) {

        return idempotencyRecordRepository.find(user.id(), idempotencyKey, requestPath)
                .map(record -> {
                    if (!record.getRequestHash().equals(requestHash)) {
                        throw new SlashException(ErrorCode.IDEMPOTENCY_CONFLICT);
                    }
                    return taskRepository.findById(record.getTaskId())
                            .map(existing -> CreateRequestResponse.of(
                                    existing.getPublicId(), TaskStatus.valueOf(existing.getStatus())))
                            .orElseThrow(() -> new SlashException(ErrorCode.RESOURCE_NOT_FOUND));
                });
    }

    /** 작업을 만들면서 함께 선점할 키. 헤더가 없으면 선점할 것이 없다. */
    private IdempotencyClaim claimOf(String idempotencyKey, String requestPath, String requestHash) {
        return idempotencyKey == null
                ? null
                : new IdempotencyClaim(idempotencyKey, requestPath, requestHash,
                        SlashTime.now().plus(IDEMPOTENCY_RETENTION));
    }

    /**
     * 같은 키를 다른 요청이 먼저 선점했을 때 그 쪽 작업을 따라간다.
     *
     * <p>여기 왔다는 것은 {@code uk_idempotency_scope} 가 이미 커밋된 기록을 막았다는 뜻이라,
     * 그 기록은 반드시 읽힌다. 그래서 비어 있는 경우를 정상 흐름으로 다루지 않는다 — 24시간
     * 보존 기간이 그 찰나에 만료돼 배치가 지운 것 같은, 설명되지 않는 상태다.
     */
    private CreateRequestResponse followWinner(AuthenticatedUser user,
                                               String idempotencyKey,
                                               String requestPath,
                                               String requestHash) {

        log.info("멱등 키 선점에 실패해 기존 작업을 따라간다 userId={} path={}", user.id(), requestPath);
        return replay(user, idempotencyKey, requestPath, requestHash)
                .orElseThrow(() -> {
                    log.warn("선점에 실패했는데 이긴 쪽 기록이 없다 userId={} path={}", user.id(), requestPath);
                    return new SlashException(ErrorCode.INTERNAL_ERROR);
                });
    }

    /**
     * 같은 키에 다른 본문이 왔는지 판별할 해시.
     *
     * <p>본문 JSON 을 그대로 해시하면 필드 순서나 공백만 달라도 다른 요청으로 본다.
     * 실제로 의미가 있는 두 값만 골라 고정된 순서로 잇는다.
     */
    private String requestHash(CreateRequestRequest request) {
        return Sha256.hex(request.text().trim() + "\n" + request.selectedDeviceId());
    }

    /**
     * 브라우저가 이미 끝낸 요약 결과를 받아 작업 이력에 남긴다. (slash-docs#3 권장 순서 3번)
     *
     * <p><b>여기는 접수({@link #accept})와 완전히 다른 입구다.</b> NLU 분석도, 실행 위치
     * 결정도 하지 않는다 — 브라우저가 이미 {@code TEXT_SUMMARY} 를 {@code BROWSER} 에서
     * 실행했다는 사실 자체가 입력이다. 남은 일은 그 결과를 다른 실행 경로와 같은 모양의
     * 작업 이력 한 줄로 만드는 것뿐이다.
     *
     * <p><b>원문은 어디에도 없다.</b> {@code tasks.input_text} 는 NOT NULL 이라 값을 채워야
     * 하지만, 실제 원문 대신 무엇이 있었는지만 적은 문구를 넣는다 — 그 열에 원문이 들어
     * 있다고 나중에 오해하면 안 된다. 목록에 보여줄 {@code request_summary} 는 성공했으면
     * 요약 결과 자체를 쓴다 — 사용자가 이력에서 "이게 무엇을 요약한 것인지" 알아볼 수
     * 있어야 하고, 지금 가진 것 중 그 역할을 할 수 있는 것은 결과뿐이다.
     *
     * <p>{@code Idempotency-Key} 는 필수다. 접수({@code /requests})와 달리 재전송이 실행을
     * 다시 트리거하지 않고 <b>새 이력 한 줄을 또 만드는 것</b>으로 이어지므로, 없이 받으면
     * 네트워크 재시도 한 번이 중복 이력으로 남는다.
     */
    public CreateRequestResponse submitBrowserSummaryResult(AuthenticatedUser user,
                                                             BrowserSummaryResultRequest request,
                                                             String idempotencyKey) {

        if (request.status() == BrowserSummaryResultRequest.Status.SUCCEEDED
                && (request.summary() == null || request.summary().isBlank())) {
            throw new SlashException(ErrorCode.VALIDATION_ERROR);
        }

        String requestHash = browserSummaryResultHash(request);

        Optional<CreateRequestResponse> replayed =
                replay(user, idempotencyKey, BROWSER_SUMMARY_RESULT_PATH, requestHash);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        String placeholder = "[브라우저에서 직접 요약 · 원문 " + request.inputLength() + "자, 서버로 전송되지 않음]";
        UUID correlationId = UUID.randomUUID();
        Optional<TasksRecord> created = stateWriter.create(
                user.id(), placeholder, correlationId, "브라우저에서 계산한 요약 결과를 받았습니다.",
                claimOf(idempotencyKey, BROWSER_SUMMARY_RESULT_PATH, requestHash));

        if (created.isEmpty()) {
            return followWinner(user, idempotencyKey, BROWSER_SUMMARY_RESULT_PATH, requestHash);
        }
        TasksRecord task = created.get();

        if (!stateWriter.move(task.getId(), TaskStatus.CREATED, TaskStatus.ANALYZING, null,
                "결과를 반영하고 있습니다.")) {
            return currentStateOf(task.getId(), task.getPublicId());
        }

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(PARAMETER_INPUT_LENGTH, request.inputLength());
        parameters.put("modelId", request.modelId());
        parameters.put("promptVersion", request.promptVersion());

        String requestSummary = request.status() == BrowserSummaryResultRequest.Status.SUCCEEDED
                ? summarize(request.summary())
                : placeholder;

        boolean applied = stateWriter.applyAnalysisAndMove(
                task.getId(),
                TaskType.TEXT_SUMMARY,
                ExecutionTarget.BROWSER,
                null,
                toJsonb(parameters),
                requestSummary,
                TaskStatus.QUEUED,
                null,
                "브라우저 요약 결과를 반영하고 있습니다.");

        if (!applied) {
            return currentStateOf(task.getId(), task.getPublicId());
        }

        stateWriter.move(task.getId(), TaskStatus.QUEUED, TaskStatus.RUNNING, null,
                "브라우저 요약 결과를 반영하고 있습니다.");

        if (request.status() == BrowserSummaryResultRequest.Status.FAILED) {
            String message = request.errorMessage() != null && !request.errorMessage().isBlank()
                    ? request.errorMessage()
                    : ErrorCode.BROWSER_TASK_FAILED.defaultMessage();
            stateWriter.fail(task.getId(), TaskStatus.RUNNING, ErrorCode.BROWSER_TASK_FAILED, message);
            return currentStateOf(task.getId(), task.getPublicId());
        }

        stateWriter.succeed(task.getId(), toJsonb(browserSummaryResult(request)), "브라우저에서 요약했습니다.");
        return currentStateOf(task.getId(), task.getPublicId());
    }

    /** 같은 요약 결과가 다시 오는지 판별할 해시. 원문이 없으니 결과 자체로 판별한다. */
    private String browserSummaryResultHash(BrowserSummaryResultRequest request) {
        return Sha256.hex(String.join("\n",
                String.valueOf(request.inputLength()),
                request.modelId(),
                request.promptVersion(),
                request.status().name(),
                String.valueOf(request.summary())));
    }

    /**
     * 화면이 그대로 읽을 수 있는 모양으로 옮긴다.
     *
     * <p>{@code summary} 는 다른 실행 경로의 결과와 같은 자리에 둔다 — 화면은 그 값만 그리므로
     * 실행 위치가 달라져도 고칠 것이 없다. {@code engine} 은 두지 않는다. GPU·CPU 처럼 서버가
     * 고른 것이 아니라 브라우저의 WebLLM 모델 자체가 그 역할이라, {@code modelId} 가 대신한다.
     */
    private Map<String, Object> browserSummaryResult(BrowserSummaryResultRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", request.summary());
        result.put("modelId", request.modelId());
        result.put("promptVersion", request.promptVersion());
        result.put("durationMs", request.durationMs());
        return result;
    }

    /**
     * NLU 분석 결과에 따라 처리 경로를 정하고 실행으로 넘긴다.
     *
     * @return 이 요청이 끝난 시점의 작업 상태
     */
    private TaskStatus analyzeAndRoute(AuthenticatedUser user, TasksRecord task, CreateRequestRequest request) {
        NluAnalyzeResponse nlu;
        try {
            nlu = nluClient.analyze(task.getCorrelationId(), request.text(), SlashTime.now());
        } catch (SlashException e) {
            stateWriter.fail(task.getId(), TaskStatus.ANALYZING, ErrorCode.NLU_UNAVAILABLE, "요청을 분석하지 못했습니다.");
            return TaskStatus.FAILED;
        }

        switch (nlu.decision()) {
            case UNSUPPORTED -> {
                stateWriter.fail(task.getId(), TaskStatus.ANALYZING,
                        ErrorCode.UNRECOGNIZED_COMMAND, "지원하지 않는 요청입니다.");
                return TaskStatus.FAILED;
            }
            case CLARIFY -> {
                // 되묻는 말은 별도 열을 두지 않고 전이 기록에 남긴다. 조회에서 그대로 꺼내 쓴다.
                stateWriter.move(task.getId(), TaskStatus.ANALYZING, TaskStatus.NEEDS_CLARIFICATION,
                        String.join(",", nlu.missingOrEmpty()), nlu.question());
                return TaskStatus.NEEDS_CLARIFICATION;
            }
            default -> {
                return route(user, task, nlu, request.selectedDeviceId());
            }
        }
    }

    private TaskStatus route(AuthenticatedUser user,
                             TasksRecord task,
                             NluAnalyzeResponse nlu,
                             UUID selectedDeviceId) {

        Optional<TaskType> resolved = parseTaskType(nlu.taskType());
        if (resolved.isEmpty()) {
            // NLU 가 우리 목록에 없는 유형을 보냈다. 계약 위반이라 실행하지 않는다.
            log.warn("알 수 없는 작업 유형 taskId={} taskType={}", task.getPublicId(), nlu.taskType());
            stateWriter.fail(task.getId(), TaskStatus.ANALYZING,
                    ErrorCode.UNRECOGNIZED_COMMAND, "지원하지 않는 요청입니다.");
            return TaskStatus.FAILED;
        }

        TaskType taskType = resolved.get();

        // NLU 가 채워야 할 값이 비어 있으면 되묻는다. 서버가 채우는 값(searchFolderId)은
        // 이 판정에 넣지 않는다. NLU 도 그 값을 누락으로 보고하지 않는다.
        List<String> missing = nlu.missingOrEmpty().stream()
                .filter(taskType.nluRequiredParameters()::contains)
                .toList();

        if (!missing.isEmpty()) {
            // 되묻는 단계에서는 분석 결과를 반영하지 않아 taskType 이 비어 있다.
            // 로컬 실행 작업은 대상 기기가 있어야 저장할 수 있는데(ck_tasks_local_agent_requires_device),
            // 아직 실행할지도 정해지지 않은 시점에 기기를 붙들 이유가 없다.
            // 사용자가 다시 답하면 새 요청으로 처음부터 분석한다.
            stateWriter.move(task.getId(), TaskStatus.ANALYZING, TaskStatus.NEEDS_CLARIFICATION,
                    String.join(",", missing), nlu.question());
            return TaskStatus.NEEDS_CLARIFICATION;
        }

        return switch (resolveExecutionTarget(user, taskType, selectedDeviceId)) {
            case RUNNER -> routeToDevice(user, task, taskType, nlu, selectedDeviceId);
            case BACKEND -> routeToBackend(task, taskType, nlu);
            // resolveExecutionTarget 이 아직 이 값을 내지 않는다. 도달하면 그쪽의 결함이다.
            case BROWSER -> throw new IllegalStateException(
                    "브라우저 실행은 아직 접수하지 않는다. taskType=" + taskType);
        };
    }

    /**
     * 이 작업을 어디서 실행할지 정한다. (slash-docs#3)
     *
     * <p><b>사용자나 NLU 가 정하지 않는다.</b> NLU 는 사용자 권한도 브라우저 상태도 PC 상태도
     * 알지 못하고, 요청에 실려 온 값을 그대로 믿으면 브라우저가 자기 결과를 PC 결과인 것처럼
     * 제출할 수 있다.
     *
     * <p>대부분은 작업 유형에서 그대로 파생된다. {@code TEXT_SUMMARY} 만 다르다 — PC 없이
     * 브라우저나 서버에서도 실행되지만, 사용자가 PC 를 선택했고 그 PC 가 실제로 처리할 수
     * 있다고 보고했으면(Claude Code·Codex 로컬 CLI) 그쪽으로 보낸다. (slash-docs#3 권장
     * 순서 7번)
     *
     * <p><b>사용자가 PC 를 선택한 것만으로는 부족하다.</b> 오래된 실행기 버전이거나 로컬
     * CLI 가 설치돼 있지 않으면 {@code device_capabilities} 에 보고가 없다 — 그때는 조용히
     * 서버 경로로 넘어간다({@code BACKEND}), PC 를 강제하지 않는다.
     *
     * <p>{@code LLM_SERVICE} 가 {@code BACKEND} 로 오는 것은 GPU Gemma 도 서버가 실행하기
     * 때문이다. CPU 추출 요약과 같은 자리에 서지만 <b>무엇으로 실행했는지</b>는 작업 결과가
     * 구분한다.
     *
     * <p><b>재개 경로(`resumeAfterApproval`)는 이 메서드를 다시 부르지 않는다.</b> 승인
     * 시점에 이미 정해서 {@code tasks.execution_target} 에 남겨 뒀으므로, 재개는 그 값을
     * 그대로 읽는다 — 다시 판단하면 그 사이 PC 능력 보고가 바뀌었을 때 승인한 것과 다른
     * 곳에서 실행될 수 있다.
     */
    private ExecutionTarget resolveExecutionTarget(AuthenticatedUser user, TaskType taskType, UUID selectedDeviceId) {
        if (textSummaryRunnerEnabled && taskType == TaskType.TEXT_SUMMARY && selectedDeviceId != null) {
            boolean deviceSupportsSummary = deviceRepository.findByPublicIdAndUserId(selectedDeviceId, user.id())
                    .filter(device -> deviceCapabilityRepository.supports(device.getId(), TaskType.TEXT_SUMMARY))
                    .isPresent();
            if (deviceSupportsSummary) {
                return ExecutionTarget.RUNNER;
            }
        }
        return switch (taskType.processingRoute()) {
            case LOCAL_AGENT -> ExecutionTarget.RUNNER;
            case BACKEND_SERVICE, LLM_SERVICE -> ExecutionTarget.BACKEND;
        };
    }

    /**
     * 서버가 실행하는 작업을 유형에 맞는 처리로 보낸다.
     *
     * <p><b>실행 위치와 처리 방법을 갈라 둔 이유가 여기다.</b> 예전에는 {@code BACKEND_SERVICE}
     * 하나가 곧 날씨 조회였다. 서버가 하는 일이 둘 이상이 되는 순간 그 방식으로는 CPU 추출
     * 요약이 날씨 조회로 들어간다. (slash-docs#3 리뷰)
     *
     * <p><b>{@code default} 를 쓰지 않고 전부 적는다.</b> 새 작업 유형을 서버 실행으로 붙이면서
     * 여기를 잊으면 컴파일이 실패해야 한다 — {@code default} 로 두면 그 실수가 실행 중에
     * 500 으로 드러난다.
     */
    private TaskStatus routeToBackend(TasksRecord task, TaskType taskType, NluAnalyzeResponse nlu) {
        return switch (taskType) {
            case WEATHER_LOOKUP -> routeToWeather(task, taskType, nlu);
            case TEXT_SUMMARY -> switch (summaryEngine) {
                case EXTRACTIVE -> routeToExtractiveSummary(task, taskType, nlu);
                case GEMMA -> routeToLlm(task, taskType, nlu);
            };
            case FILE_SEARCH, FILE_OPEN, SYSTEM_STATUS, CODE_ANALYSIS, AI_AGENT_USAGE ->
                    throw new IllegalStateException(
                            "PC 실행 작업이 서버 경로로 들어왔다. taskType=" + taskType);
        };
    }

    /**
     * CPU 추출 요약을 한다. (slash-docs#3 권장 순서 3번)
     *
     * <p><b>날씨와 같은 모양이다.</b> 원장({@code async_jobs})을 두지 않고 곧바로 부른다 —
     * 원문에서 문장을 고르는 일이라 몇십 밀리초에 끝나고, 실패해도 사용자가 다시 누르면
     * 그만이라 남겨서 이어받을 것이 없다. Gemma 경로가 원장과 스윕을 두는 것은 모델이
     * 수십 초를 쓰기 때문이고, 여기에는 그 이유가 없다.
     *
     * <p><b>{@link LlmReadiness} 를 보지 않는다.</b> 그것은 GPU 모델이 작업을 받을 수 있는지를
     * 묻는 것이고, 이 경로는 GPU 를 쓰지 않는다.
     *
     * <p>입력 길이를 여기서 미리 확인하지 않는다. NLU 가 판정하고 이유를 코드로 돌려준다.
     */
    private TaskStatus routeToExtractiveSummary(TasksRecord task, TaskType taskType, NluAnalyzeResponse nlu) {
        Map<String, Object> parameters = new LinkedHashMap<>(nlu.parametersOrEmpty());

        Optional<TaskStatus> paused = pauseForApproval(task, taskType, ExecutionTarget.BACKEND, null, parameters);
        if (paused.isPresent()) {
            return paused.get();
        }

        boolean applied = stateWriter.applyAnalysisAndMove(
                task.getId(),
                taskType,
                ExecutionTarget.BACKEND,
                null,
                toJsonb(parameters),
                summarize(task.getInputText()),
                TaskStatus.QUEUED,
                null,
                "요약하고 있습니다.");

        if (!applied) {
            return currentStatusOf(task.getId());
        }

        return executeExtractiveSummary(task, parameters);
    }

    /** 요약을 실제로 하고 마감한다. 접수 직후와 승인 뒤가 이 자리를 함께 쓴다. */
    private TaskStatus executeExtractiveSummary(TasksRecord task, Map<String, Object> parameters) {
        stateWriter.move(task.getId(), TaskStatus.QUEUED, TaskStatus.RUNNING, null, "요약하고 있습니다.");

        SummaryOutcome outcome = nluSummaryClient.summarize(
                task.getCorrelationId(), task.getPublicId(),
                String.valueOf(parameters.get(PARAMETER_TEXT)));

        if (outcome instanceof SummaryOutcome.Failure failure) {
            stateWriter.fail(task.getId(), TaskStatus.RUNNING, failure.errorCode(), failure.message());
            return TaskStatus.FAILED;
        }

        SummaryOutcome.Success success = (SummaryOutcome.Success) outcome;
        stateWriter.succeed(task.getId(), toJsonb(summaryResult(success.response())), "요약했습니다.",
                summaryRawTextDisposal(parameters, success.response().summary()));
        return TaskStatus.SUCCEEDED;
    }

    /**
     * 요약이 끝난 뒤 원문 자리에 남길 값들. (slash-docs#3 · 원문 기본 미저장)
     *
     * <p><b>원문을 들고 있어야 하는 이유가 실패 재시도뿐이라, 성공한 순간 사라진다.</b>
     * 실패·되묻기로 끝난 작업은 그대로 두어 사용자가 다시 누를 수 있게 한다.
     *
     * <p><b>{@code requestSummary} 도 함께 바꾼다.</b> 지금까지는 원문 앞 80자였는데 그것도
     * 원문 발췌라, 분량과 무관하게 남길 이유가 같다. 이 시점에는 요약 결과가 이미 있으므로
     * 그것을 쓴다 — 목록에서 "무엇을 요약했는지" 알아보는 데도 원문 앞부분보다 낫다.
     * ({@code BROWSER} 경로가 처음부터 쓰던 방식이다)
     *
     * <p>완전한 미저장은 애초에 불가능하다. 추출 요약은 원문에서 문장을 그대로 고르므로
     * 결과 자체가 원문의 부분집합이다. 목표는 <b>전체 원문을 오래 갖고 있지 않는 것</b>이다.
     */
    private RawTextDisposal summaryRawTextDisposal(Map<String, Object> parameters, String summary) {
        String rawText = String.valueOf(parameters.get(PARAMETER_TEXT));

        Map<String, Object> kept = new LinkedHashMap<>(parameters);
        kept.remove(PARAMETER_TEXT);
        kept.put(PARAMETER_INPUT_LENGTH, rawText.length());

        return new RawTextDisposal(
                "[서버에서 요약 · 원문 " + rawText.length() + "자, 요약 후 저장하지 않음]",
                toJsonb(kept),
                summarize(summary));
    }

    /**
     * 화면이 그대로 읽을 수 있는 모양으로 옮긴다.
     *
     * <p><b>{@code summary} 는 Gemma 결과와 같은 자리에 둔다.</b> 화면은 그 값만 그리므로
     * 엔진이 바뀌어도 고칠 것이 없다.
     *
     * <p>나머지는 <b>무엇으로 요약했는지</b>다. 실행 위치({@code executionTarget})가 어디서
     * 했는지만 나타내기로 했으므로, 그 안에서 Gemma 와 추출 요약을 가르는 것은 이 값들이다.
     * (slash-docs#3 리뷰에서 확정한 경계)
     */
    private Map<String, Object> summaryResult(NluSummaryResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", response.summary());
        result.put("engine", response.engine());
        result.put("algorithm", response.algorithm());
        result.put("algorithmVersion", response.algorithmVersion());
        result.put("inputSentenceCount", response.inputSentenceCount());
        result.put("outputSentenceCount", response.outputSentenceCount());
        result.put("durationMs", response.durationMs());
        return result;
    }

    /**
     * 실행 직전에 사용자 확인이 필요한지 보고, 필요하면 그 자리에서 멈춘다.
     * (P0-C · 계획 문서 §1.5)
     *
     * <p><b>준비를 마친 뒤에 부른다.</b> 기기를 고르고 입력값까지 다 채운 다음이라야 사용자가
     * 본 것과 실행되는 것이 같아진다. 먼저 물어 놓고 값을 나중에 채우면 무엇을 승인한 것인지
     * 아무도 말할 수 없다.
     *
     * @return 멈췄으면 그 상태. 비어 있으면 승인이 필요 없으니 그대로 진행한다
     */
    private Optional<TaskStatus> pauseForApproval(TasksRecord task,
                                                  TaskType taskType,
                                                  ExecutionTarget target,
                                                  Long deviceId,
                                                  Map<String, Object> parameters) {

        if (!approvalPolicy.requiresApproval(taskType)) {
            return Optional.empty();
        }

        boolean applied = stateWriter.applyAnalysisAndMove(
                task.getId(),
                taskType,
                target,
                deviceId,
                toJsonb(parameters),
                summarize(task.getInputText()),
                TaskStatus.WAITING_FOR_APPROVAL,
                null,
                "실행하기 전에 확인이 필요합니다.");

        if (!applied) {
            return Optional.of(currentStatusOf(task.getId()));
        }

        approvalService.request(task.getId(), parameters);
        log.info("승인을 기다린다 taskId={} taskType={}", task.getPublicId(), taskType);
        return Optional.of(TaskStatus.WAITING_FOR_APPROVAL);
    }

    /** 작업에 걸린 승인 요청. 없으면 비어 있다. */
    public Optional<TaskApprovalsRecord> findApproval(long taskId) {
        return approvalRepository.findByTaskId(taskId);
    }

    /**
     * 사용자의 결정을 받아 처리한다. (P0-C · 계획 문서 §1.5)
     *
     * <p>승인이면 그 자리에서 실행으로 이어지고, 거절이면 아무것도 실행하지 않고 마감한다.
     *
     * <p><b>결정과 실행을 한 트랜잭션으로 묶지 않는다.</b> 실행은 PC 전달이나 외부 호출을
     * 포함해 수 초가 걸리는데, 그동안 승인 행을 잠그고 있으면 같은 사용자의 다른 요청까지
     * 밀린다. 결정이 먼저 커밋되고 실행이 뒤따르는 편이, 실행 도중 Pod 이 내려가도
     * "승인은 했는데 실행이 시작되지 않은" 상태로 남아 스윕이 마감할 수 있다.
     */
    public TaskStatus decideApproval(AuthenticatedUser user,
                                     TasksRecord task,
                                     ApprovalDecision decision,
                                     int expectedVersion) {

        approvalService.decide(user, task, decision, expectedVersion);

        if (decision == ApprovalDecision.REJECT) {
            stateWriter.fail(task.getId(), TaskStatus.WAITING_FOR_APPROVAL,
                    ErrorCode.APPROVAL_REJECTED, "실행하지 않았습니다.");
            return TaskStatus.FAILED;
        }

        return resumeAfterApproval(task);
    }

    /**
     * 승인받은 작업을 이어서 실행한다. (P0-C)
     *
     * <p><b>승인한 내용과 지금 실행하려는 내용이 같은지 먼저 확인한다.</b> 다르면 실행하지
     * 않는다 — 사용자가 본 것과 다른 것이 실행되면 승인은 뜻을 잃는다. 지금 구조에서는 그
     * 사이에 입력값이 바뀔 길이 없지만, 확인을 두지 않으면 나중에 바뀔 수 있게 만드는 순간
     * 아무도 알아채지 못한다.
     *
     * <p>기기와 입력값은 승인 전에 이미 정해 두었으므로 <b>다시 고르지 않는다.</b> 다시 고르면
     * 사용자가 승인한 PC 가 아닌 곳에서 실행될 수 있다. 실행 위치도 마찬가지라
     * {@link #resolveExecutionTarget} 을 다시 부르지 않고 승인 시점에 저장해 둔
     * {@code tasks.execution_target} 을 그대로 읽는다 — {@code TEXT_SUMMARY} 처럼 PC 능력에
     * 따라 갈리는 유형은 그 사이 보고가 바뀌면 다시 판단했을 때 승인한 것과 달라질 수 있다.
     */
    public TaskStatus resumeAfterApproval(TasksRecord task) {
        TaskType taskType = TaskType.valueOf(task.getTaskType());
        Map<String, Object> parameters = readParameters(task);

        TaskApprovalsRecord approval = approvalRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "승인 기록 없이 재개할 수 없습니다. taskId=" + task.getPublicId()));

        if (!approvalService.matches(approval, parameters)) {
            log.error("승인한 내용과 실행하려는 내용이 다르다 taskId={}", task.getPublicId());
            stateWriter.fail(task.getId(), TaskStatus.WAITING_FOR_APPROVAL,
                    ErrorCode.INVALID_PARAMETERS, "승인한 내용과 달라져 실행하지 않았습니다.");
            return TaskStatus.FAILED;
        }

        return switch (ExecutionTarget.valueOf(task.getExecutionTarget())) {
            case RUNNER -> resumeOnDevice(task);
            case BACKEND -> resumeOnBackend(task, taskType, parameters);
            case BROWSER -> throw new IllegalStateException(
                    "브라우저 실행은 아직 접수하지 않는다. taskType=" + taskType);
        };
    }

    /**
     * 승인받은 PC 작업을 보낸다.
     *
     * <p>기기는 승인 전에 정해져 있다. <b>그 사이에 꺼졌을 수 있으므로</b> 지금 받을 수 있는지
     * 다시 본다 — 받을 수 없으면 접수 때와 같이 {@code WAITING_FOR_DEVICE} 로 기다린다.
     */
    private TaskStatus resumeOnDevice(TasksRecord task) {
        Optional<DevicesRecord> device = deviceRepository.findById(task.getDeviceId());
        boolean ready = device.isPresent() && acceptsTask(device.get());

        TaskStatus next = ready ? TaskStatus.QUEUED : TaskStatus.WAITING_FOR_DEVICE;
        String message = ready
                ? "PC 로 작업을 보냈습니다."
                : device.map(TaskService::waitingMessage).orElse("PC 가 연결되면 실행합니다.");

        if (!stateWriter.move(task.getId(), TaskStatus.WAITING_FOR_APPROVAL, next, null, message)) {
            return currentStatusOf(task.getId());
        }

        return ready ? dispatchOrRelease(task.getId()) : next;
    }

    /**
     * 승인받은 서버 작업을 실행한다. 접수 직후와 같은 자리를 쓴다.
     *
     * <p><b>{@link #routeToBackend} 와 똑같이 갈라야 한다.</b> 한쪽만 고치면 사용자가 승인한
     * 것과 다른 방법으로 실행된다 — 요약이 그렇다. {@code executionTarget} 은 둘 다
     * {@code BACKEND} 이고 입력값 해시도 같아서 {@code matches()} 도 통과하므로,
     * <b>엔진이 바뀐 것만 아무 데서도 걸리지 않는다.</b> (#60 리뷰)
     */
    private TaskStatus resumeOnBackend(TasksRecord task, TaskType taskType, Map<String, Object> parameters) {
        return switch (taskType) {
            case WEATHER_LOOKUP -> {
                if (!moveToQueued(task)) {
                    yield currentStatusOf(task.getId());
                }
                yield executeWeather(task, parameters);
            }
            case TEXT_SUMMARY -> switch (summaryEngine) {
                case EXTRACTIVE -> {
                    if (!moveToQueued(task)) {
                        yield currentStatusOf(task.getId());
                    }
                    yield executeExtractiveSummary(task, parameters);
                }
                // GPU 요약은 상태 전이와 원장을 한 트랜잭션으로 묶어야 해서 따로 간다.
                case GEMMA -> resumeOnLlm(task, parameters);
            };
            case FILE_SEARCH, FILE_OPEN, SYSTEM_STATUS, CODE_ANALYSIS, AI_AGENT_USAGE ->
                    throw new IllegalStateException(
                            "PC 실행 작업이 서버 재개 경로로 들어왔다. taskType=" + taskType);
        };
    }

    private boolean moveToQueued(TasksRecord task) {
        return stateWriter.move(task.getId(), TaskStatus.WAITING_FOR_APPROVAL, TaskStatus.QUEUED,
                null, "승인을 받아 실행합니다.");
    }

    /**
     * 승인받은 GPU 요약을 맡긴다.
     *
     * <p>접수 직후와 달리 분석 결과를 다시 쓰지 않는다 — 물어보기 전에 이미 저장했고,
     * 사용자가 본 것과 같아야 한다. 상태 전이와 원장 생성은 그때와 같이 한 트랜잭션이다.
     */
    private TaskStatus resumeOnLlm(TasksRecord task, Map<String, Object> parameters) {
        if (!llmReadiness.getObject().canAccept()) {
            log.info("요약 모델이 작업을 받을 수 없어 승인받은 작업을 실행하지 못한다 taskId={} reason={}",
                    task.getPublicId(), llmReadiness.getObject().reason().orElse("UNKNOWN"));
            stateWriter.fail(task.getId(), TaskStatus.WAITING_FOR_APPROVAL, ErrorCode.LLM_NOT_READY,
                    "요약 모델이 아직 준비되지 않았습니다. 잠시 뒤 다시 시도해 주세요.");
            return TaskStatus.FAILED;
        }

        JSONB input = toJsonb(parameters);
        Optional<AsyncJobsRecord> job = llmSummaryEnqueuer.enqueueApproved(
                task.getId(), input, SlashTime.now().plus(summaryDeadline));

        if (job.isEmpty()) {
            return currentStatusOf(task.getId());
        }

        llmSummaryRunner.runAsync(
                job.get().getId(),
                task.getId(),
                task.getCorrelationId(),
                task.getPublicId(),
                String.valueOf(parameters.get(PARAMETER_TEXT)));

        return TaskStatus.QUEUED;
    }

    /** 저장해 둔 입력값을 읽는다. 승인 전에 굳혀 둔 것과 같은 값이어야 한다. */
    private Map<String, Object> readParameters(TasksRecord task) {
        JSONB parameters = task.getParameters();
        if (parameters == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(parameters.data(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("저장된 입력값을 읽을 수 없습니다. taskId=" + task.getPublicId(), e);
        }
    }

    /**
     * 날씨를 조회한다. (Open-Meteo)
     *
     * <p><b>여기서 곧바로 부른다.</b> 요약과 달리 원장을 두지 않는다 — 두 번의 조회가 보통
     * 1초 안에 끝나고, 실패해도 사용자가 다시 누르면 그만이라 남겨서 이어받을 것이 없다.
     * ({@code async_jobs} 의 {@code ck_async_jobs_job_type} 도 LLM 작업만 허용한다)
     *
     * <p><b>기기를 고르지 않는다.</b> 서버가 하는 일이라 PC 가 없어도 된다.
     *
     * <p>지역을 못 찾은 것과 서비스가 멈춘 것을 나눈다. 앞은 사용자가 다시 말하면 되지만
     * 뒤는 기다리는 수밖에 없어서, 같은 말로 안내하면 사용자가 할 수 있는 일을 가린다.
     */
    private TaskStatus routeToWeather(TasksRecord task, TaskType taskType, NluAnalyzeResponse nlu) {
        Map<String, Object> parameters = new LinkedHashMap<>(nlu.parametersOrEmpty());

        Optional<TaskStatus> paused = pauseForApproval(task, taskType, ExecutionTarget.BACKEND, null, parameters);
        if (paused.isPresent()) {
            return paused.get();
        }

        boolean applied = stateWriter.applyAnalysisAndMove(
                task.getId(),
                taskType,
                ExecutionTarget.BACKEND,
                null,
                toJsonb(parameters),
                summarize(task.getInputText()),
                TaskStatus.QUEUED,
                null,
                "날씨를 조회합니다.");

        if (!applied) {
            return currentStatusOf(task.getId());
        }

        return executeWeather(task, parameters);
    }

    /**
     * 날씨를 실제로 조회하고 마감한다. 접수 직후와 승인 뒤가 이 자리를 함께 쓴다.
     *
     * <p>{@code QUEUED} 에서 시작하는 것이 전제다.
     */
    private TaskStatus executeWeather(TasksRecord task, Map<String, Object> parameters) {
        String location = String.valueOf(parameters.get(PARAMETER_LOCATION));

        stateWriter.move(task.getId(), TaskStatus.QUEUED, TaskStatus.RUNNING, null, "날씨를 조회하고 있습니다.");

        WeatherOutcome outcome = weatherClient.lookup(location);
        if (outcome instanceof WeatherOutcome.Failure failure) {
            stateWriter.fail(task.getId(), TaskStatus.RUNNING, failure.errorCode(), failure.message());
            return TaskStatus.FAILED;
        }

        WeatherOutcome.Success success = (WeatherOutcome.Success) outcome;
        stateWriter.succeed(task.getId(), toJsonb(weatherResult(success)), "날씨를 알려 드립니다.");
        return TaskStatus.SUCCEEDED;
    }

    /**
     * 화면이 그대로 읽을 수 있는 모양으로 옮긴다.
     *
     * <p>찾아낸 지명을 함께 싣는 이유 — 사용자가 말한 "수원" 과 실제로 조회한 "수원시(경기도)"
     * 가 다를 수 있다. 어디의 날씨인지 보여 줘야 엉뚱한 곳이면 사용자가 알아챈다.
     */
    private Map<String, Object> weatherResult(WeatherOutcome.Success success) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("location", success.place().name());
        result.put("region", success.place().admin1());
        result.put("country", success.place().country());
        result.put("temperature", success.current().temperature());
        result.put("apparentTemperature", success.current().apparentTemperature());
        result.put("humidity", success.current().humidity());
        result.put("precipitation", success.current().precipitation());
        result.put("windSpeed", success.current().windSpeed());
        result.put("description", WeatherCode.describe(success.current().weatherCode()));
        result.put("observedAt", success.current().time());
        return result;
    }

    /**
     * 로컬 실행 작업을 기기로 보낸다.
     *
     * <p>기기가 READY 면 전달을 만들어 곧바로 내보내고, 아니면 {@code WAITING_FOR_DEVICE} 로
     * 남긴다. 전달을 미리 만들지 않는 것은 기한 때문이다 — 언제 켜질지 모르는데 기한을 먼저
     * 박으면 켜지기도 전에 만료된다.
     */
    private TaskStatus routeToDevice(AuthenticatedUser user,
                                     TasksRecord task,
                                     TaskType taskType,
                                     NluAnalyzeResponse nlu,
                                     UUID selectedDeviceId) {

        Map<String, Object> parameters = new LinkedHashMap<>(nlu.parametersOrEmpty());

        // 입력값부터 본다. 기기를 먼저 고르면 PC 가 없을 때 DEVICE_NOT_READY 가 나가서,
        // 실제 원인이 입력값이라는 것을 사용자가 알 수 없다.
        if (!validateAgentParameters(task, taskType, parameters)) {
            return TaskStatus.FAILED;
        }

        Optional<DevicesRecord> found = resolveDevice(user, selectedDeviceId);
        if (found.isEmpty()) {
            stateWriter.fail(task.getId(), TaskStatus.ANALYZING, ErrorCode.DEVICE_NOT_READY,
                    "이 작업을 실행할 PC 가 등록되어 있지 않습니다.");
            return TaskStatus.FAILED;
        }

        DevicesRecord device = found.get();

        // P0 는 기기당 동시 1건이다. 최종 판정은 agent_dispatches 의 부분 UNIQUE 제약이 한다.
        //
        // 여기서 보는 것은 "실행 중"이지 "미완료"가 아니다. 꺼진 PC 를 기다리는 작업이 있어도
        // 새 요청은 받는다 — 그것을 막으면 미리 접수해 두는 것 자체가 안 된다.
        if (taskRepository.isDeviceOccupied(device.getId())) {
            stateWriter.fail(task.getId(), TaskStatus.ANALYZING, ErrorCode.DEVICE_BUSY,
                    "선택한 PC 가 다른 작업을 실행 중입니다.");
            return TaskStatus.FAILED;
        }

        // NLU 가 채우지 않는 값을 서버가 채운다. 채우지 못하면 실행할 수 없으므로 여기서 마감한다.
        if (!fillBackendProvided(task, taskType, device, parameters)) {
            return TaskStatus.FAILED;
        }

        // 기기와 입력값이 모두 정해진 지금이 물어볼 자리다. 이보다 앞이면 무엇을 승인하는지
        // 알 수 없고, 이보다 뒤면 이미 PC 로 나간 뒤다.
        Optional<TaskStatus> paused = pauseForApproval(
                task, taskType, ExecutionTarget.RUNNER, device.getId(), parameters);
        if (paused.isPresent()) {
            return paused.get();
        }

        boolean ready = acceptsTask(device);
        TaskStatus next = ready ? TaskStatus.QUEUED : TaskStatus.WAITING_FOR_DEVICE;
        String message = ready ? "PC 로 작업을 보냈습니다." : waitingMessage(device);

        boolean applied = stateWriter.applyAnalysisAndMove(
                task.getId(),
                taskType,
                ExecutionTarget.RUNNER,
                device.getId(),
                toJsonb(parameters),
                summarize(task.getInputText()),
                next,
                null,
                message);

        if (!applied) {
            return currentStatusOf(task.getId());
        }

        return ready ? dispatchOrRelease(task.getId()) : next;
    }

    /**
     * 전달을 만들어 내보내되, 기기를 다른 요청에 뺏겼으면 이 작업을 마감한다.
     *
     * <p>{@link TaskRepository#isDeviceOccupied} 는 조회 시점의 스냅샷이라 같은 순간에 들어온
     * 두 요청을 갈라내지 못한다. <b>최종 판정은 {@code uk_dispatch_active_device} 가 한다.</b>
     * 진 쪽은 {@link DuplicateKeyException} 을 받는데, 이때 이미 {@code QUEUED} 는 별도
     * 트랜잭션으로 커밋된 뒤다.
     *
     * <p>그대로 두면 <b>전달 없이 {@code QUEUED} 로 굳은 작업</b>이 남는다. 그 작업은 ACK 도
     * RESULT 도 받을 수 없어 화면에는 끝나지 않는 진행 표시로 보인다. 되돌릴 자리가 여기뿐이라
     * 여기서 마감한다. ({@code AgentDispatchRepository} 주석이 요구하는 처리다)
     */
    private TaskStatus dispatchOrRelease(long taskId) {
        try {
            dispatch(taskId);
            return TaskStatus.QUEUED;

        } catch (DuplicateKeyException e) {
            log.info("전달 경쟁에서 밀려 작업을 마감한다 taskId={}", taskId);
            stateWriter.fail(taskId, TaskStatus.QUEUED, ErrorCode.DEVICE_BUSY,
                    "선택한 PC 가 다른 작업을 실행 중입니다.");
            return TaskStatus.FAILED;
        }
    }

    /**
     * 요약을 맡긴다. (slash-llm {@code docs/BACKEND_CONTRACT.md})
     *
     * <p><b>여기서 모델을 기다리지 않는다.</b> {@code QUEUED} 까지만 옮기고 원장을 남긴 뒤
     * 곧바로 응답한다. 실제 호출은 {@link LlmSummaryRunner} 가 뒤에서 하고, 화면은 WSS·폴링으로
     * 따라온다. ({@code docs/frontend-api-contract.md} §7)
     *
     * <p><b>기기를 고르지 않는다.</b> 요약은 서버 쪽 모델이 하는 일이라 PC 가 없어도 된다.
     * ({@code TaskType#requiresDevice} 가 거짓이고 {@code ck_tasks_local_agent_requires_device} 도
     * 로컬 실행 작업에만 기기를 요구한다)
     *
     * <p>원장을 먼저 남기는 이유는 전달과 같다 — Pod 이 호출 도중에 죽어도 작업이 사라지지
     * 않고 {@link com.likelion.slash.llm.LlmJobSweeper} 가 이어받는다.
     *
     * <p><b>Task 전이와 원장 생성은 한 트랜잭션이어야 한다.</b> 그 묶음은
     * {@link LlmSummaryEnqueuer#enqueue} 가 맡는다 — 여기서 나눠 부르면 그 사이의 실패가
     * 원장 없는 {@code QUEUED} Task 를 남기고, 스윕은 원장을 보고 도는 것이라 찾지 못한다.
     */
    private TaskStatus routeToLlm(TasksRecord task, TaskType taskType, NluAnalyzeResponse nlu) {
        // 받을 수 없는 상태라면 원장을 만들지 않고 여기서 답한다. 만들어 두면 호출했다가
        // 실패로 마감하는 일을 반복하고, 사용자는 같은 말을 한참 뒤에 듣는다.
        if (!llmReadiness.getObject().canAccept()) {
            log.info("요약 모델이 작업을 받을 수 없어 접수하지 않는다 taskId={} reason={}",
                    task.getPublicId(), llmReadiness.getObject().reason().orElse("UNKNOWN"));
            stateWriter.fail(task.getId(), TaskStatus.ANALYZING, ErrorCode.LLM_NOT_READY,
                    "요약 모델이 아직 준비되지 않았습니다. 잠시 뒤 다시 시도해 주세요.");
            return TaskStatus.FAILED;
        }

        Map<String, Object> parameters = new LinkedHashMap<>(nlu.parametersOrEmpty());

        Optional<TaskStatus> paused = pauseForApproval(task, taskType, ExecutionTarget.BACKEND, null, parameters);
        if (paused.isPresent()) {
            return paused.get();
        }

        JSONB input = toJsonb(parameters);

        Optional<AsyncJobsRecord> job = llmSummaryEnqueuer.enqueue(
                task.getId(), taskType, input, summarize(task.getInputText()),
                SlashTime.now().plus(summaryDeadline));

        if (job.isEmpty()) {
            return currentStatusOf(task.getId());
        }

        // enqueue 가 돌아왔다는 것은 커밋됐다는 뜻이다. 이제 다른 Pod 과 스윕도 이 원장을 본다.
        llmSummaryRunner.runAsync(
                job.get().getId(),
                task.getId(),
                task.getCorrelationId(),
                task.getPublicId(),
                String.valueOf(parameters.get(PARAMETER_TEXT)));

        return TaskStatus.QUEUED;
    }


    /**
     * 실행할 기기를 고른다.
     *
     * <p>사용자가 골랐으면 그것을 쓰고, 아니면 등록된 PC 중에서 정한다. 지금은 P0 라
     * 한 대만 쓰는 것을 전제로 하되, 여러 대가 있으면 <b>지금 작업을 받는 것</b>을 먼저 고른다.
     * 꺼져 있거나 수신을 꺼 둔 PC 를 골라 놓고 기다리게 하는 것보다 낫다.
     */
    private Optional<DevicesRecord> resolveDevice(AuthenticatedUser user, UUID selectedDeviceId) {
        if (selectedDeviceId != null) {
            return deviceRepository.findByPublicIdAndUserId(selectedDeviceId, user.id());
        }

        return deviceRepository.findActiveByUserId(user.id()).stream()
                .min(Comparator
                        .comparing((DevicesRecord device) -> acceptsTask(device) ? 0 : 1)
                        .thenComparing(DevicesRecord::getId, Comparator.reverseOrder()));
    }

    /**
     * 지금 이 기기로 작업을 보낼 수 있는가.
     *
     * <p>연결 상태와 <b>사용자가 켜 둔 수신 여부</b>를 함께 본다. PC 가 붙어 있어도 사용자가
     * 작업 수신을 꺼 두었으면 보내지 않는다. ({@code devices.accepting_tasks} · #24)
     */
    private static boolean acceptsTask(DevicesRecord device) {
        return DeviceStatus.valueOf(device.getStatus()).canAcceptTask(device.getAcceptingTasks());
    }

    /**
     * 곧바로 보내지 못할 때 사용자에게 알릴 말.
     *
     * <p>꺼진 PC 와 수신을 꺼 둔 PC 를 구분한다. 둘 다 "PC 가 연결되면 실행합니다" 로 안내하면,
     * 정지해 둔 사용자는 이미 켜져 있는 PC 를 두고 무엇을 더 기다려야 하는지 알 수 없다.
     *
     * <p><b>연결을 먼저 말한다.</b> 꺼져 있으면서 수신도 꺼 둔 PC 는 둘 다 풀어야 실행되는데,
     * 수신만 켜라고 안내하면 그대로 했는데도 아무 일이 없는 것으로 보인다.
     */
    private static String waitingMessage(DevicesRecord device) {
        return DeviceStatus.READY.name().equals(device.getStatus())
                ? "PC 가 작업 수신을 다시 켜면 실행합니다."
                : "PC 가 연결되면 실행합니다.";
    }

    // ------------------------------------------------------------------
    // 전달
    // ------------------------------------------------------------------

    /**
     * 기기가 다시 붙었을 때 밀려 있던 작업을 내보낸다. (Agent READY 시점)
     *
     * <p><b>한 번에 한 건만 내보낸다.</b> {@code uk_dispatch_active_device} 가 기기당 활성 전달
     * 한 건만 허용하기 때문이다. 밀린 것을 한꺼번에 밀어 넣으면 두 번째부터 제약 위반으로
     * 튕기는데, 그때는 이미 상태를 QUEUED 로 옮긴 뒤라 되돌릴 자리가 없다.
     *
     * <p>기기가 이미 다른 작업을 붙들고 있으면 아무것도 하지 않는다. 남은 대기 작업은 다음
     * 연결에서 이어서 나간다 — 실행이 끝나는 시점에 이어 보내는 것은 RESULT 처리에 붙일 몫이다.
     *
     * @return 내보낸 건수 (0 또는 1)
     */
    public int dispatchWaiting(long deviceId) {
        // 사용자가 수신을 꺼 두었으면 붙어 있어도 보내지 않는다. 접수 경로에서만 막으면
        // 꺼 두기 전에 쌓인 작업이 재연결과 함께 쏟아진다. (#24)
        if (deviceRepository.findById(deviceId)
                .map(device -> !Boolean.TRUE.equals(device.getAcceptingTasks()))
                .orElse(true)) {
            log.debug("작업 수신이 꺼져 있어 대기 작업을 내보내지 않는다 deviceId={}", deviceId);
            return 0;
        }

        if (taskRepository.isDeviceOccupied(deviceId)) {
            log.debug("기기가 이미 작업을 붙들고 있어 대기 작업을 내보내지 않는다 deviceId={}", deviceId);
            return 0;
        }

        List<TasksRecord> waiting = taskRepository.findWaitingForDevice(deviceId, 1);
        if (waiting.isEmpty()) {
            return 0;
        }

        TasksRecord task = waiting.get(0);
        try {
            if (!stateWriter.move(task.getId(), TaskStatus.WAITING_FOR_DEVICE, TaskStatus.QUEUED,
                    null, "PC 가 연결되어 작업을 보냈습니다.")) {
                return 0;
            }

            // 여기서도 전달 경쟁에서 밀릴 수 있다. 접수 경로와 같은 이유로, 마감하지 않으면
            // 전달 없이 QUEUED 로 굳는다. 예외를 밖으로 내보내지 않는 것만으로는 부족하다.
            if (dispatchOrRelease(task.getId()) == TaskStatus.FAILED) {
                return 0;
            }

            log.info("대기 작업 전달 taskId={} deviceId={}", task.getPublicId(), deviceId);
            return 1;

        } catch (Exception e) {
            log.warn("대기 작업 전달 실패 taskId={}: {}", task.getPublicId(), e.getMessage());
            return 0;
        }
    }

    /**
     * 전달을 만들고 프레임을 발행한다.
     *
     * <p>상태를 옮긴 뒤의 최신 행을 다시 읽는다. 프레임에 실을 {@code taskType}·{@code parameters}
     * 는 {@code applyAnalysis} 로 방금 채워진 값이라 접수 시점에 읽어 둔 행에는 없다.
     */
    private void dispatch(long taskId) {
        taskRepository.findById(taskId)
                .ifPresent(fresh -> taskDispatcher.dispatch(fresh, fresh.getDeviceId()));
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    /** 소유권을 강제한 단건 조회. 남의 작업이면 404 로 막는다. */
    public TasksRecord findOwned(AuthenticatedUser user, UUID taskId) {
        return taskRepository.findByPublicIdAndUserId(taskId, user.id())
                .orElseThrow(() -> new SlashException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * {@code inputText} 가 사용자가 넣은 원문 그대로인지. (#84 · slash-web#67)
     *
     * <p>요약이 끝나면 원문을 갖고 있지 않으므로(#83 · slash-docs#3) 그 자리에는 안내 문구가
     * 들어간다. 문구는 <b>실행 위치마다 다르고</b> 앞으로 더 늘 수 있어서, 화면이 문구를
     * 알아보게 두면 문구가 바뀔 때 조용히 깨진다. 재생성처럼 원문을 다시 보내는 동작은
     * 이 값으로 판단하게 한다.
     *
     * <p>판단은 {@link #PARAMETER_INPUT_LENGTH} 로 한다. 원문을 걷어낼 때만 넣는 값이라
     * 세 자리가 같은 표식을 공유한다 — 접수 시점부터 원문이 없는 {@code BROWSER},
     * 요약 직후의 {@link #summaryRawTextDisposal}, 배포 롤링 창에 남은 것을 뒤늦게 거두는
     * {@link TaskRepository#dropRawTextFromSucceededSummaries}.
     *
     * <p>원문이 아직 있는 동안(분석 중·실패)에는 {@code true} 다. 요약이 실패했으면 원문이
     * 그대로 남아 있어 다시 보낼 수 있다.
     */
    public boolean inputTextIsOriginal(TasksRecord task) {
        return !readParameters(task).containsKey(PARAMETER_INPUT_LENGTH);
    }

    // ------------------------------------------------------------------
    // 보조
    // ------------------------------------------------------------------

    private Optional<TaskType> parseTaskType(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(TaskType.valueOf(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * NLU 가 채운 값이 PC 실행기의 계약에 맞는지 본다.
     *
     * <p><b>여기서 거르지 않으면 PC 까지 갔다 온 뒤에야 실패한다.</b> 실행기는 모르는 값을
     * {@code INVALID_PARAMETERS} 로 거부하는데, PC 가 꺼져 있으면 그 판정조차 켜질 때까지
     * 미뤄진다. 값이 잘못된 것은 지금 알 수 있는 일이라 지금 답한다.
     *
     * <p>값이 맞으면 실행기가 쓰는 정확한 이름으로 맞춰 둔다. 대소문자만 달라도 거부당한다.
     */
    private boolean validateAgentParameters(TasksRecord task,
                                            TaskType taskType,
                                            Map<String, Object> parameters) {

        if (taskType != TaskType.AI_AGENT_USAGE) {
            return true;
        }

        Object raw = parameters.get(PARAMETER_PROVIDER);
        Optional<AiAgentProvider> provider = AiAgentProvider.from(raw == null ? null : String.valueOf(raw));

        if (provider.isEmpty()) {
            log.debug("사용량을 볼 도구를 알 수 없다 taskId={} provider={}", task.getPublicId(), raw);
            stateWriter.fail(task.getId(), TaskStatus.ANALYZING, ErrorCode.INVALID_PARAMETERS,
                    "어떤 도구의 사용량을 볼지 알 수 없습니다. Claude Code 또는 Codex 중에서 골라 주세요.");
            return false;
        }

        parameters.put(PARAMETER_PROVIDER, provider.get().name());
        return true;
    }

    /**
     * NLU 가 채우지 않는 필수 입력값을 서버가 채운다. ({@link TaskType#backendProvidedParameters()})
     *
     * <p>검색 폴더처럼 <b>사용자가 PC 에 미리 등록해 둔 목록에서 고르는 값</b>이라 자연어에서
     * 뽑아낼 수 없다. NLU 는 이 값을 반환하지도, 누락으로 보고하지도 않는다.
     *
     * <p><b>고르는 시점이 접수 시점인 것에 유의한다.</b> PC 가 꺼져 있으면 지난 연결에서 보고한
     * 목록을 보게 되는데, 그 사이 사용자가 폴더를 뺐다면 이미 없는 식별자를 보내게 된다.
     * 그 경우 Agent 가 {@code SEARCH_FOLDER_NOT_FOUND} 로 거절하므로 조용히 틀리지는 않는다.
     * 전달 시점으로 미루려면 이미 저장된 작업 입력값을 다시 써야 해서 지금은 접수 시점에 고른다.
     *
     * <p>채우는 값은 둘이다 — {@code FILE_SEARCH} 의 {@code searchFolderId} 와
     * {@code CODE_ANALYSIS} 의 {@code workspaceId}. 둘 다 Agent 가 READY 로 보고한 목록에서
     * 고르며, 고르지 못하면 작업을 여기서 마감한다. 값 없이 내보내 봐야 Agent 가 거절한다.
     *
     * @return 채웠으면 참. 거짓이면 채우지 못해 작업을 이미 실패로 마감했다
     */
    private boolean fillBackendProvided(TasksRecord task,
                                        TaskType taskType,
                                        DevicesRecord device,
                                        Map<String, Object> parameters) {

        List<String> needed = taskType.backendProvidedParameters();

        if (needed.contains(PARAMETER_SEARCH_FOLDER_ID)
                && !fillSearchFolder(task, device, parameters)) {
            return false;
        }
        if (needed.contains(PARAMETER_WORKSPACE_ID)
                && !fillProjectWorkspace(task, device, parameters)) {
            return false;
        }
        return true;
    }

    /** {@code FILE_SEARCH} 가 뒤질 폴더를 고른다. */
    private boolean fillSearchFolder(TasksRecord task, DevicesRecord device, Map<String, Object> parameters) {
        Optional<String> searchFolderId = deviceSearchFolderRepository.pickSearchable(device.getId());
        if (searchFolderId.isEmpty()) {
            // 폴더를 한 번도 보고받지 못했거나, 있어도 전부 읽을 수 없는(UNAVAILABLE) 상태다.
            // 둘을 구분해 알리지 않는다. 사용자가 할 일은 어느 쪽이든 Agent 에서 폴더를 확인하는 것이다.
            // 색인 중인 폴더는 여기 오지 않는다 — Agent 가 검색해 주므로 그대로 내보낸다.
            log.debug("검색할 폴더가 없다 taskId={} deviceId={}", task.getPublicId(), device.getId());
            stateWriter.fail(task.getId(), TaskStatus.ANALYZING, ErrorCode.SEARCH_FOLDER_NOT_FOUND,
                    "PC 에 검색할 수 있는 폴더가 없습니다. Agent 에서 폴더를 추가해 주세요.");
            return false;
        }

        parameters.put(PARAMETER_SEARCH_FOLDER_ID, searchFolderId.get());
        return true;
    }

    /**
     * {@code CODE_ANALYSIS} 가 분석할 프로젝트 폴더를 고른다.
     *
     * <p><b>폴더가 없는 것과 도구가 없는 것을 나눠 알린다.</b> 사용자가 할 일이 다르기 때문이다 —
     * 앞은 Agent 에서 폴더를 등록하는 것이고, 뒤는 Claude Code 나 Codex 를 설치하는 것이다.
     * 둘 다 "폴더를 추가해 주세요" 로 안내하면, CLI 를 안 깔아 둔 사용자는 폴더를 몇 번을
     * 등록해도 같은 실패를 본다.
     */
    private boolean fillProjectWorkspace(TasksRecord task, DevicesRecord device, Map<String, Object> parameters) {
        Optional<String> workspaceId = deviceProjectWorkspaceRepository.pickAnalyzable(device.getId());
        if (workspaceId.isPresent()) {
            parameters.put(PARAMETER_WORKSPACE_ID, workspaceId.get());
            return true;
        }

        boolean hasAnyFolder = !deviceProjectWorkspaceRepository.findAllByDeviceId(device.getId()).isEmpty();
        if (hasAnyFolder) {
            log.debug("프로젝트 폴더는 있으나 쓸 수 있는 도구가 없다 taskId={} deviceId={}",
                    task.getPublicId(), device.getId());
            stateWriter.fail(task.getId(), TaskStatus.ANALYZING, ErrorCode.CODE_AGENT_NOT_CONFIGURED,
                    "PC 에 Claude Code 나 Codex 가 설치되어 있지 않습니다.");
            return false;
        }

        log.debug("분석할 프로젝트 폴더가 없다 taskId={} deviceId={}", task.getPublicId(), device.getId());
        stateWriter.fail(task.getId(), TaskStatus.ANALYZING, ErrorCode.WORKSPACE_NOT_FOUND,
                "PC 에 분석할 프로젝트 폴더가 없습니다. Agent 에서 폴더를 추가해 주세요.");
        return false;
    }

    private JSONB toJsonb(Map<String, Object> parameters) {
        try {
            return JSONB.valueOf(objectMapper.writeValueAsString(parameters));
        } catch (Exception e) {
            // 여기서 실패하면 NLU 가 직렬화할 수 없는 값을 보낸 것이다. 입력값 없이 진행하면
            // Agent 가 거부하므로 남기고 빈 값으로 둔다.
            log.warn("작업 입력값을 저장할 형태로 바꾸지 못했다: {}", e.getMessage());
            return JSONB.valueOf("{}");
        }
    }

    private String summarize(String text) {
        return RequestSummary.of(text);
    }

    private TaskStatus currentStatusOf(long taskId) {
        return taskRepository.findById(taskId)
                .map(record -> TaskStatus.valueOf(record.getStatus()))
                .orElse(TaskStatus.FAILED);
    }

    private CreateRequestResponse currentStateOf(long taskId, UUID publicId) {
        return CreateRequestResponse.of(publicId, currentStatusOf(taskId));
    }
}
