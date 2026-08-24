package com.likelion.slash.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.approval.dto.ApprovalDecisionRequest;
import com.likelion.slash.approval.dto.TaskApprovalResponse;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.auth.AuthenticatedUserService;
import com.likelion.slash.common.EntityTag;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.response.ApiResponse;
import com.likelion.slash.device.DeviceRepository;
import com.likelion.slash.jooq.tables.records.TaskEventsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.dto.BrowserSummaryResultRequest;
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
import org.springframework.http.HttpHeaders;
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
     * 브라우저(WebLLM)가 이미 끝낸 요약 결과를 접수한다. (slash-docs#3 권장 순서 3번)
     *
     * <p>{@code POST /requests} 와 달리 실행하지 않는다 — 브라우저가 원문을 서버 밖에 둔 채
     * 이미 요약을 끝냈고, 여기는 그 결과를 다른 실행 경로와 같은 모양의 작업 이력으로
     * 남기는 자리다. 그래서 202 가 아니라 실행이 이미 끝난 상태({@code SUCCEEDED}·
     * {@code FAILED})로 응답한다.
     *
     * @param idempotencyKey 필수. {@code POST /requests} 와 달리 재전송이 같은 결과를 다시
     *                       계산하는 게 아니라 새 이력 한 줄을 또 만드는 것으로 이어지므로,
     *                       없이 받으면 네트워크 재시도 한 번이 중복 이력으로 남는다.
     */
    @PostMapping("/tasks/text-summary/browser-result")
    public ResponseEntity<ApiResponse<CreateRequestResponse>> submitBrowserSummaryResult(
            @Valid @RequestBody BrowserSummaryResultRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {

        AuthenticatedUser user = authenticatedUserService.current();
        CreateRequestResponse result = taskService.submitBrowserSummaryResult(user, request, idempotencyKey);

        log.info("브라우저 요약 결과 접수 taskId={} status={} userId={}",
                result.taskId(), result.status(), user.publicId());

        return ResponseEntity.ok().body(ApiResponse.of(result));
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
                task.getExecutionTarget(),
                devicePublicId(task.getDeviceId()),
                task.getInputText(),
                readJson(task.getParameters()),
                readJson(task.getResult()),
                task.getErrorCode(),
                question(task),
                approvalOf(task),
                task.getCorrelationId(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt()));
    }

    /**
     * 실행하기 전 확인에 답한다. (P0-C · 계획 문서 §1.5)
     *
     * <p><b>{@code If-Match} 가 필요하다.</b> 상세 조회 응답의 {@code approval.version} 을
     * 그대로 넣는다. 화면이 낡은 값을 들고 두 번 눌러도 한 번만 반영된다.
     *
     * <p>승인하면 그 자리에서 실행으로 이어진다 — 접수 때와 같은 경로를 탄다. 거절하면
     * 실패로 마감하고 아무것도 실행하지 않는다.
     *
     * <p>이미 결정됐거나 기한이 지난 요청은 {@code RESOURCE_VERSION_MISMATCH} (412) 다.
     * 실행이 시작된 뒤에는 되돌릴 방법이 없으므로 상태로 막는 것이 유일한 보호다.
     */
    @PostMapping("/tasks/{taskId}/approval")
    public ApiResponse<TaskDetailResponse> decideApproval(
            @PathVariable UUID taskId,
            @Valid @RequestBody ApprovalDecisionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {

        AuthenticatedUser user = authenticatedUserService.current();
        TasksRecord task = taskService.findOwned(user, taskId);

        taskService.decideApproval(user, task, request.decision(), EntityTag.parseVersion(ifMatch));

        // 결정 뒤의 모습을 그대로 돌려준다. 화면이 다시 조회하지 않아도 되고,
        // 승인이면 이미 실행까지 이어진 상태가 보인다.
        return get(taskId);
    }

    /** 실행 전 확인이 걸린 작업에만 있다. 그렇지 않으면 응답에서 빠진다. */
    private TaskApprovalResponse approvalOf(TasksRecord task) {
        return taskService.findApproval(task.getId())
                .map(TaskApprovalResponse::from)
                .orElse(null);
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
