package com.likelion.slash.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.Sha256;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AsyncJobType;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.ProcessingRoute;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.device.DeviceSearchFolderRepository;
import com.likelion.slash.dispatch.TaskDispatcher;
import com.likelion.slash.job.AsyncJobRepository;
import com.likelion.slash.jooq.tables.records.AsyncJobsRecord;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import com.likelion.slash.jooq.tables.records.IdempotencyRecordsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.llm.LlmSummaryRunner;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.task.dto.CreateRequestRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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

    /** 이력 목록에 보여줄 요약 길이. */
    private static final int SUMMARY_LENGTH = 80;

    /** FILE_SEARCH 의 서버가 채우는 입력값. Agent 가 READY 로 보고한 폴더 중 하나를 넣는다. */
    private static final String PARAMETER_SEARCH_FOLDER_ID = "searchFolderId";

    /** TEXT_SUMMARY 의 원문. NLU 가 채운다. */
    private static final String PARAMETER_TEXT = "text";

    /** 멱등 기록의 범위. 같은 키라도 다른 Endpoint 면 별개로 본다. */
    private static final String REQUEST_PATH = "/api/v1/requests";

    /** 멱등 기록 보존 기간. (문서 3.4.6) */
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final TaskRepository taskRepository;
    private final TaskStateWriter stateWriter;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceSearchFolderRepository deviceSearchFolderRepository;
    private final NluClient nluClient;
    private final TaskDispatcher taskDispatcher;
    private final ObjectMapper objectMapper;
    private final AsyncJobRepository asyncJobRepository;
    private final LlmSummaryRunner llmSummaryRunner;

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
                       DeviceSearchFolderRepository deviceSearchFolderRepository,
                       NluClient nluClient,
                       TaskDispatcher taskDispatcher,
                       ObjectMapper objectMapper,
                       AsyncJobRepository asyncJobRepository,
                       LlmSummaryRunner llmSummaryRunner,
                       @Value("${slash.llm.job-deadline}") Duration summaryDeadline) {
        this.taskRepository = taskRepository;
        this.stateWriter = stateWriter;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.deviceRepository = deviceRepository;
        this.deviceSearchFolderRepository = deviceSearchFolderRepository;
        this.nluClient = nluClient;
        this.taskDispatcher = taskDispatcher;
        this.objectMapper = objectMapper;
        this.asyncJobRepository = asyncJobRepository;
        this.llmSummaryRunner = llmSummaryRunner;
        this.summaryDeadline = summaryDeadline;
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

        if (idempotencyKey != null) {
            Optional<CreateRequestResponse> replayed = replay(user, idempotencyKey, request);
            if (replayed.isPresent()) {
                return replayed.get();
            }
        }

        UUID correlationId = UUID.randomUUID();
        TasksRecord task = taskRepository.create(user.id(), request.text(), correlationId);
        stateWriter.recordCreated(task.getId(), summarize(request.text()));

        if (idempotencyKey != null) {
            Optional<CreateRequestResponse> winner = claimOrFollow(user, idempotencyKey, request, task);
            if (winner.isPresent()) {
                return winner.get();
            }
        }

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
     * @return 재전송으로 판정했으면 그때의 작업, 처음 보는 키면 비어 있음
     * @throws SlashException 같은 키에 다른 본문이 왔을 때 {@link ErrorCode#IDEMPOTENCY_CONFLICT}
     */
    private Optional<CreateRequestResponse> replay(AuthenticatedUser user,
                                                   String idempotencyKey,
                                                   CreateRequestRequest request) {

        return idempotencyRecordRepository.find(user.id(), idempotencyKey, REQUEST_PATH)
                .map(record -> {
                    if (!record.getRequestHash().equals(requestHash(request))) {
                        throw new SlashException(ErrorCode.IDEMPOTENCY_CONFLICT);
                    }
                    return taskRepository.findById(record.getTaskId())
                            .map(existing -> CreateRequestResponse.of(
                                    existing.getPublicId(), TaskStatus.valueOf(existing.getStatus())))
                            .orElseThrow(() -> new SlashException(ErrorCode.RESOURCE_NOT_FOUND));
                });
    }

    /**
     * 멱등 기록을 선점한다.
     *
     * <p>두 요청이 같은 순간에 도착하면 {@code uk_idempotency_scope} 가 한 쪽만 남긴다.
     * 진 쪽은 방금 만든 작업을 버리고 이긴 쪽의 작업을 따라간다.
     *
     * <p>버려진 작업은 {@code CREATED} 로 남는다. 지우지 않는 이유는 그 사이 다른 곳에서
     * 참조했을 수 있어서다. 미완료 작업 만료 배치가 정리한다.
     *
     * @return 경쟁에서 졌으면 이긴 쪽의 작업, 선점했으면 비어 있음
     */
    private Optional<CreateRequestResponse> claimOrFollow(AuthenticatedUser user,
                                                          String idempotencyKey,
                                                          CreateRequestRequest request,
                                                          TasksRecord task) {

        Optional<IdempotencyRecordsRecord> claimed = idempotencyRecordRepository.tryInsert(
                user.id(),
                idempotencyKey,
                REQUEST_PATH,
                requestHash(request),
                task.getId(),
                HttpStatus.ACCEPTED.value(),
                SlashTime.now().plus(IDEMPOTENCY_RETENTION));

        if (claimed.isPresent()) {
            return Optional.empty();
        }

        log.info("멱등 키 선점에 실패해 기존 작업을 따라간다 taskId={}", task.getPublicId());
        return replay(user, idempotencyKey, request);
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

        return switch (taskType.processingRoute()) {
            case LOCAL_AGENT -> routeToDevice(user, task, taskType, nlu, selectedDeviceId);
            case BACKEND_SERVICE -> notReadyYet(task, taskType, ErrorCode.UPSTREAM_UNAVAILABLE,
                    "날씨 조회는 아직 연결되어 있지 않습니다.");
            case LLM_SERVICE -> routeToLlm(task, taskType, nlu);
        };
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

        Map<String, Object> parameters = new LinkedHashMap<>(nlu.parametersOrEmpty());

        // NLU 가 채우지 않는 값을 서버가 채운다. 채우지 못하면 실행할 수 없으므로 여기서 마감한다.
        if (!fillBackendProvided(task, taskType, device, parameters)) {
            return TaskStatus.FAILED;
        }

        boolean ready = acceptsTask(device);
        TaskStatus next = ready ? TaskStatus.QUEUED : TaskStatus.WAITING_FOR_DEVICE;
        String message = ready ? "PC 로 작업을 보냈습니다." : waitingMessage(device);

        boolean applied = stateWriter.applyAnalysisAndMove(
                task.getId(),
                taskType,
                ProcessingRoute.LOCAL_AGENT,
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
     */
    private TaskStatus routeToLlm(TasksRecord task, TaskType taskType, NluAnalyzeResponse nlu) {
        Map<String, Object> parameters = new LinkedHashMap<>(nlu.parametersOrEmpty());
        JSONB input = toJsonb(parameters);

        boolean applied = stateWriter.applyAnalysisAndMove(
                task.getId(),
                taskType,
                ProcessingRoute.LLM_SERVICE,
                null,
                input,
                summarize(task.getInputText()),
                TaskStatus.QUEUED,
                null,
                "요약을 맡겼습니다.");

        if (!applied) {
            return currentStatusOf(task.getId());
        }

        AsyncJobsRecord job = asyncJobRepository.create(
                task.getId(), AsyncJobType.TEXT_SUMMARY, input, SlashTime.now().plus(summaryDeadline));

        // PENDING 은 "아직 아무에게도 맡기지 않은" 상태다. 지금은 SQS 없이 곧바로 부르므로
        // 맡긴 시점이 여기다. (SQS 로 옮기면 발행에 성공한 시점으로 옮겨 간다)
        asyncJobRepository.markQueued(job.getId());

        llmSummaryRunner.runAsync(
                job.getId(),
                task.getId(),
                task.getCorrelationId(),
                task.getPublicId(),
                String.valueOf(parameters.get(PARAMETER_TEXT)));

        return TaskStatus.QUEUED;
    }

    /**
     * 아직 붙이지 않은 처리 경로.
     *
     * <p>있는 척하지 않고 실패로 마감한다. 화면에는 "아직 연결되어 있지 않다"가 그대로 보인다.
     * {@code BACKEND_SERVICE}(날씨)와 {@code LLM_SERVICE}(요약)는 종단 경로를 먼저 뚫은 뒤에
     * 붙인다.
     */
    private TaskStatus notReadyYet(TasksRecord task, TaskType taskType, ErrorCode errorCode, String message) {
        log.info("아직 연결되지 않은 처리 경로 taskId={} taskType={}", task.getPublicId(), taskType);
        stateWriter.fail(task.getId(), TaskStatus.ANALYZING, errorCode, message);
        return TaskStatus.FAILED;
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
     * <p><b>지금 채우는 것은 {@code searchFolderId} 하나뿐이다.</b> CODE_ANALYSIS 의
     * {@code workspaceId} 도 같은 자리(P1)인데 저장할 표가 없어 채우지 못한다. 그 경우 값 없이
     * 나가고 Agent 가 {@code WORKSPACE_NOT_FOUND} 로 거절한다. 표가 생기면 여기에 한 줄 더 붙는다.
     *
     * @return 채웠으면 참. 거짓이면 채우지 못해 작업을 이미 실패로 마감했다
     */
    private boolean fillBackendProvided(TasksRecord task,
                                        TaskType taskType,
                                        DevicesRecord device,
                                        Map<String, Object> parameters) {

        if (!taskType.backendProvidedParameters().contains(PARAMETER_SEARCH_FOLDER_ID)) {
            return true;
        }

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
        String trimmed = text.trim();
        return trimmed.length() <= SUMMARY_LENGTH ? trimmed : trimmed.substring(0, SUMMARY_LENGTH);
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
