package com.likelion.slash.approval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.Sha256;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ApprovalDecision;
import com.likelion.slash.common.enums.ApprovalStatus;
import com.likelion.slash.common.enums.AuditActorType;
import com.likelion.slash.common.enums.AuditTargetType;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.common.error.SlashException;
import com.likelion.slash.audit.AuditEventRepository;
import com.likelion.slash.jooq.tables.records.TaskApprovalsRecord;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 확인이 필요한 작업의 승인 단계. (P0-C · 계획 문서 §1.5)
 *
 * <p>계획 문서가 slash-api 몫으로 든 네 가지가 여기 있다 — <b>Hash·Version·만료·감사</b>.
 *
 * <p><b>승인은 실행 직전에 묻는다.</b> 기기를 고르고 입력값까지 다 채운 뒤 멈춘다. 그래야
 * 사용자가 본 것과 실행되는 것이 같다. 먼저 물어 놓고 나중에 값을 채우면 승인한 내용이
 * 무엇이었는지 아무도 말할 수 없다.
 */
@Service
public class TaskApprovalService {

    private static final Logger log = LoggerFactory.getLogger(TaskApprovalService.class);

    static final String ACTION_APPROVED = "TASK_APPROVED";
    static final String ACTION_REJECTED = "TASK_REJECTED";

    private final TaskApprovalRepository approvalRepository;
    private final AuditEventRepository auditEventRepository;
    private final ApprovalPolicy policy;
    private final ObjectMapper objectMapper;

    public TaskApprovalService(TaskApprovalRepository approvalRepository,
                               AuditEventRepository auditEventRepository,
                               ApprovalPolicy policy,
                               ObjectMapper objectMapper) {
        this.approvalRepository = approvalRepository;
        this.auditEventRepository = auditEventRepository;
        this.policy = policy;
        this.objectMapper = objectMapper;
    }

    /**
     * 승인 요청을 만든다. 작업은 이미 {@code WAITING_FOR_APPROVAL} 로 옮겨진 뒤여야 한다.
     *
     * @param parameters 승인 시점의 입력값. 해시로 굳혀 실행 직전에 대조한다
     */
    public TaskApprovalsRecord request(long taskId, Map<String, Object> parameters) {
        return approvalRepository.create(
                taskId, hash(parameters), SlashTime.now().plus(policy.ttl()));
    }

    /**
     * 입력값을 해시로 굳힌다.
     *
     * <p><b>키 순서에 흔들리지 않아야 한다.</b> 같은 내용인데 순서만 달라도 해시가 바뀌면
     * 승인한 작업이 실행 직전에 거부된다. {@code Map} 의 순회 순서는 구현에 달렸고 JSONB 로
     * 오갈 때도 보존되지 않으므로, 정렬한 뒤 직렬화한다.
     *
     * <p><b>정렬은 최상위 키까지다.</b> 지금 입력값은 모두 평면이라({@code location}·
     * {@code query}·{@code text}·{@code workspaceId}…) 문제가 없지만, 중첩된 객체가 들어오면
     * 그 안쪽은 순서가 보장되지 않아 <b>같은 내용인데 해시가 갈릴 수 있다.</b> 중첩 입력값을
     * 쓰는 작업이 생기면 재귀 정렬로 바꿔야 한다.
     */
    String hash(Map<String, Object> parameters) {
        try {
            Map<String, Object> sorted = new TreeMap<>(parameters == null ? Map.of() : parameters);
            return Sha256.hex(objectMapper.writeValueAsString(sorted));

        } catch (Exception e) {
            // 입력값을 굳히지 못하면 승인 자체가 뜻을 잃는다. 조용히 넘어가면 안 된다.
            throw new IllegalStateException("승인 대상 입력값을 해시할 수 없습니다. taskId 확인 필요", e);
        }
    }

    /**
     * 사용자의 결정을 반영한다.
     *
     * @param expectedVersion {@code If-Match} 로 받은 값. 화면이 낡은 값을 들고 두 번 눌러도
     *                        한 번만 반영된다
     * @throws SlashException 승인 요청이 없거나({@code RESOURCE_NOT_FOUND}),
     *                        이미 결정·만료됐거나 버전이 어긋날 때({@code RESOURCE_VERSION_MISMATCH})
     */
    @Transactional
    public TaskApprovalsRecord decide(AuthenticatedUser user,
                                      TasksRecord task,
                                      ApprovalDecision decision,
                                      int expectedVersion) {

        TaskApprovalsRecord approval = approvalRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new SlashException(ErrorCode.RESOURCE_NOT_FOUND));

        // 이미 결정됐거나 만료된 것을 뒤집을 수 없다. 실행이 시작된 뒤에 승인을 취소해도
        // 되돌릴 방법이 없으므로, 상태로 막는 것이 유일한 보호다.
        if (!TaskStatus.WAITING_FOR_APPROVAL.name().equals(task.getStatus())) {
            log.info("승인을 기다리는 작업이 아니다 taskId={} status={}",
                    task.getPublicId(), task.getStatus());
            throw new SlashException(ErrorCode.RESOURCE_VERSION_MISMATCH);
        }

        TaskApprovalsRecord decided = approvalRepository
                .decide(task.getId(), expectedVersion, decision.toStatus(), user.id())
                .orElseThrow(() -> new SlashException(ErrorCode.RESOURCE_VERSION_MISMATCH));

        audit(user, task, decision);
        return decided;
    }

    /**
     * 누가 무엇을 승인·거절했는지 남긴다.
     *
     * <p>{@code AuditEventRepository} 의 첫 사용처다. 승인은 <b>사용자가 책임을 지는
     * 지점</b>이라, 나중에 "누가 이걸 허락했나" 를 답할 수 있어야 한다.
     *
     * <p>입력값은 남기지 않는다. 파일 경로나 질의어가 들어 있어 감사 기록에 둘 것이 아니다
     * (V007 주석). 대신 해시를 남겨 <b>무엇을 승인했는지 대조할 수는 있게</b> 한다.
     */
    private void audit(AuthenticatedUser user, TasksRecord task, ApprovalDecision decision) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskType", task.getTaskType());
        approvalRepository.findByTaskId(task.getId())
                .ifPresent(a -> detail.put("parametersHash", a.getParametersHash()));

        try {
            auditEventRepository.record(
                    user.id(),
                    AuditActorType.USER,
                    decision == ApprovalDecision.APPROVE ? ACTION_APPROVED : ACTION_REJECTED,
                    AuditTargetType.TASK,
                    task.getPublicId(),
                    JSONB.valueOf(objectMapper.writeValueAsString(detail)),
                    null);

        } catch (Exception e) {
            // 감사 기록에 실패해도 사용자의 결정은 이미 반영됐다. 되돌리면 사용자가 두 번
            // 누르게 되고, 그 사이에 실행이 시작될 수도 있다. 남기지 못한 것을 로그로 알린다.
            log.error("승인 감사 기록에 실패했다 taskId={} decision={}: {}",
                    task.getPublicId(), decision, e.getMessage());
        }
    }

    /**
     * 승인한 내용과 지금 실행하려는 내용이 같은지 확인한다.
     *
     * <p>다르면 실행하지 않는다. 사용자가 본 것과 다른 것이 실행되면 승인은 뜻을 잃는다.
     */
    public boolean matches(TaskApprovalsRecord approval, Map<String, Object> parameters) {
        return approval.getParametersHash().equals(hash(parameters));
    }

    public ApprovalStatus statusOf(TaskApprovalsRecord approval) {
        return ApprovalStatus.valueOf(approval.getStatus());
    }
}
