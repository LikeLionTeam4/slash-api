package com.likelion.slash.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.auth.AuthenticatedUserService;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.response.ApiResponse;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.jooq.tables.records.TaskEventsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.dto.CreateRequestRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
import com.likelion.slash.task.dto.TaskDetailResponse;
import com.likelion.slash.task.dto.TaskHistoryResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작업 접수와 조회. (WBS W1-04)
 *
 * <p>브라우저는 오직 {@code POST /api/v1/requests} 로만 작업을 접수한다. 슬래시 명령인지
 * 자연어인지 가르지 않고 입력창의 한 줄을 그대로 보낸다.
 *
 * <p>접수 응답의 {@code statusUrl} 을 폴링해 결과를 받는다. 사용자 WSS 로 밀어 주는 것은
 * Ticket 발급과 함께 붙일 다음 단계다.
 */
@RestController
@RequestMapping("/api/v1")
public class RequestController {

    private static final Logger log = LoggerFactory.getLogger(RequestController.class);

    private final AuthenticatedUserService authenticatedUserService;
    private final TaskService taskService;
    private final TaskHistoryService taskHistoryService;
    private final TaskEventRepository taskEventRepository;
    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    public RequestController(AuthenticatedUserService authenticatedUserService,
                             TaskService taskService,
                             TaskHistoryService taskHistoryService,
                             TaskEventRepository taskEventRepository,
                             DeviceRepository deviceRepository,
                             ObjectMapper objectMapper) {
        this.authenticatedUserService = authenticatedUserService;
        this.taskService = taskService;
        this.taskHistoryService = taskHistoryService;
        this.taskEventRepository = taskEventRepository;
        this.deviceRepository = deviceRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 요청을 접수한다.
     *
     * <p>202 로 돌려준다. 접수 시점에 이미 전달까지 마쳤더라도 <b>결과는 아직 없다</b> —
     * 실행은 PC 나 외부 서비스에서 일어나고, 그 결과는 {@code statusUrl} 로 확인한다.
     *
     * @param idempotencyKey 중복 클릭·재전송으로 같은 작업이 두 번 만들어지는 것을 막는다.
     *                       같은 키에 다른 본문이 오면 {@code IDEMPOTENCY_CONFLICT} 로 거부한다.
     */
    @PostMapping("/requests")
    public ResponseEntity<ApiResponse<CreateRequestResponse>> create(
            @Valid @RequestBody CreateRequestRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        AuthenticatedUser user = authenticatedUserService.current();
        CreateRequestResponse accepted = taskService.accept(user, request, idempotencyKey);

        log.info("요청 접수 taskId={} status={} userId={}",
                accepted.taskId(), accepted.status(), user.publicId());

        return ResponseEntity.accepted()
                .header("Location", accepted.statusUrl())
                .body(ApiResponse.of(accepted));
    }

    /**
     * 내 작업 이력. (P0-B)
     *
     * <p>최신순이고 커서로 이어 받는다. 갈래·상태·PC 로 좁힐 수 있으며, 셋 다 비우면 전체다.
     *
     * <p><b>결과 본문은 담기지 않는다.</b> 한 건을 펼쳐 볼 때 {@code GET /tasks/{taskId}} 를 부른다.
     *
     * @param cursor 이전 응답의 {@code nextCursor} 를 그대로 넘긴다. 첫 쪽이면 비운다
     */
    @GetMapping("/tasks")
    public ApiResponse<TaskHistoryResponse> history(
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID deviceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {

        AuthenticatedUser user = authenticatedUserService.current();

        return ApiResponse.of(taskHistoryService.find(
                user.id(), taskType, status, deviceId, cursor, limit));
    }

    /** 작업 하나의 현재 상태와 결과. 남의 작업은 404 다. */
    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskDetailResponse> get(@PathVariable UUID taskId) {
        AuthenticatedUser user = authenticatedUserService.current();
        TasksRecord task = taskService.findOwned(user, taskId);

        return ApiResponse.of(new TaskDetailResponse(
                task.getPublicId(),
                task.getStatus(),
                task.getTaskType(),
                task.getProcessingRoute(),
                devicePublicId(task.getDeviceId()),
                task.getInputText(),
                readJson(task.getParameters()),
                readJson(task.getResult()),
                task.getErrorCode(),
                question(task),
                task.getCorrelationId(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt()));
    }

    /**
     * 되물어야 할 때 보여줄 말.
     *
     * <p>별도 열을 두지 않고 마지막 전이 기록의 설명을 쓴다. 되묻기는 그 자체가 상태 전이라
     * 타임라인에 이미 남아 있고, 열을 늘리면 두 곳이 어긋날 수 있다.
     */
    private String question(TasksRecord task) {
        if (!TaskStatus.NEEDS_CLARIFICATION.name().equals(task.getStatus())) {
            return null;
        }
        List<TaskEventsRecord> events = taskEventRepository.findAllByTaskId(task.getId());
        return events.isEmpty() ? null : events.get(events.size() - 1).getMessage();
    }

    /** 내부 PK 를 외부 식별자로 바꾼다. 기기가 없는 작업이면 비어 있다. */
    private UUID devicePublicId(Long deviceId) {
        if (deviceId == null) {
            return null;
        }
        return deviceRepository.findById(deviceId)
                .map(device -> device.getPublicId())
                .orElse(null);
    }

    private JsonNode readJson(JSONB value) {
        if (value == null || value.data() == null) {
            return null;
        }
        try {
            return objectMapper.readTree(value.data());
        } catch (Exception e) {
            log.warn("저장된 JSON 을 읽지 못했다: {}", e.getMessage());
            return null;
        }
    }
}
