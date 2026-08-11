package com.likelion.slash.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.Sha256;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.ProcessingRoute;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.dispatch.TaskDispatcher;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import com.likelion.slash.jooq.tables.records.IdempotencyRecordsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.nlu.NluClient;
import com.likelion.slash.nlu.dto.NluAnalyzeResponse;
import com.likelion.slash.task.dto.CreateRequestRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /** 멱등 기록의 범위. 같은 키라도 다른 Endpoint 면 별개로 본다. */
    private static final String REQUEST_PATH = "/api/v1/requests";

    /** 멱등 기록 보존 기간. (문서 3.4.6) */
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final TaskRepository taskRepository;
    private final TaskStateWriter stateWriter;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final DeviceRepository deviceRepository;
    private final NluClient nluClient;
    private final TaskDispatcher taskDispatcher;
    private final ObjectMapper objectMapper;

    public TaskService(TaskRepository taskRepository,
                       TaskStateWriter stateWriter,
                       IdempotencyRecordRepository idempotencyRecordRepository,
                       DeviceRepository deviceRepository,
                       NluClient nluClient,
                       TaskDispatcher taskDispatcher,
                       ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.stateWriter = stateWriter;
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.deviceRepository = deviceRepository;
        this.nluClient = nluClient;
        this.taskDispatcher = taskDispatcher;
        this.objectMapper = objectMapper;
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
            case LLM_SERVICE -> notReadyYet(task, taskType, ErrorCode.LLM_NOT_READY,
                    "요약은 아직 연결되어 있지 않습니다.");
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

        boolean ready = DeviceStatus.READY.name().equals(device.getStatus());
        TaskStatus next = ready ? TaskStatus.QUEUED : TaskStatus.WAITING_FOR_DEVICE;
        String message = ready ? "PC 로 작업을 보냈습니다." : "PC 가 연결되면 실행합니다.";

        boolean applied = stateWriter.applyAnalysisAndMove(
                task.getId(),
                taskType,
                ProcessingRoute.LOCAL_AGENT,
                device.getId(),
                toJsonb(nlu.parametersOrEmpty()),
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
     * 한 대만 쓰는 것을 전제로 하되, 여러 대가 있으면 <b>연결돼 있는 것</b>을 먼저 고른다.
     * 꺼진 PC 를 골라 놓고 기다리게 하는 것보다 낫다.
     */
    private Optional<DevicesRecord> resolveDevice(AuthenticatedUser user, UUID selectedDeviceId) {
        if (selectedDeviceId != null) {
            return deviceRepository.findByPublicIdAndUserId(selectedDeviceId, user.id());
        }

        return deviceRepository.findAllByUserId(user.id()).stream()
                .filter(device -> !DeviceStatus.REVOKED.name().equals(device.getStatus()))
                .min(Comparator
                        .comparing((DevicesRecord device) -> DeviceStatus.READY.name().equals(device.getStatus()) ? 0 : 1)
                        .thenComparing(DevicesRecord::getId, Comparator.reverseOrder()));
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
