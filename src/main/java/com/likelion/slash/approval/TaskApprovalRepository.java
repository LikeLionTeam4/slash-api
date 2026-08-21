package com.likelion.slash.approval;

import static com.likelion.slash.jooq.Tables.TASK_APPROVALS;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ApprovalStatus;
import com.likelion.slash.jooq.tables.records.TaskApprovalsRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * 승인 요청 원장. (P0-C · 계획 문서 §1.5)
 *
 * <p>작업당 하나만 만든다({@code uk_task_approvals_task}). 다시 묻고 싶으면 새 작업으로
 * 접수한다 — 같은 작업에 승인을 여러 번 두면 <b>어느 것이 실행 근거인지</b>가 흐려진다.
 */
@Repository
public class TaskApprovalRepository {

    private final DSLContext dsl;

    public TaskApprovalRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * 승인 요청을 만든다.
     *
     * @param parametersHash 승인 시점 입력값의 해시. 실행 직전에 다시 계산해 맞춘다
     * @param expiresAt      사용자 응답 기한
     */
    public TaskApprovalsRecord create(long taskId, String parametersHash, OffsetDateTime expiresAt) {
        return dsl.insertInto(TASK_APPROVALS)
                .set(TASK_APPROVALS.TASK_ID, taskId)
                .set(TASK_APPROVALS.STATUS, ApprovalStatus.PENDING.name())
                .set(TASK_APPROVALS.PARAMETERS_HASH, parametersHash)
                .set(TASK_APPROVALS.EXPIRES_AT, expiresAt)
                .returning()
                .fetchOne();
    }

    public Optional<TaskApprovalsRecord> findByTaskId(long taskId) {
        return dsl.selectFrom(TASK_APPROVALS)
                .where(TASK_APPROVALS.TASK_ID.eq(taskId))
                .fetchOptional();
    }

    /**
     * 사용자의 결정을 반영한다.
     *
     * <p><b>기다리는 상태일 때만 바뀐다.</b> 이미 결정됐거나 만료된 것을 다시 뒤집을 수
     * 없다. 버전까지 맞아야 하므로, 화면이 낡은 값을 들고 두 번 눌러도 한 번만 반영된다.
     *
     * @return 갱신된 행. 비어 있으면 이미 결정됐거나 버전이 어긋난 것이다
     */
    public Optional<TaskApprovalsRecord> decide(long taskId,
                                                int expectedVersion,
                                                ApprovalStatus decision,
                                                long decidedBy) {
        return dsl.update(TASK_APPROVALS)
                .set(TASK_APPROVALS.STATUS, decision.name())
                .set(TASK_APPROVALS.DECIDED_AT, SlashTime.now())
                .set(TASK_APPROVALS.DECIDED_BY, decidedBy)
                .set(TASK_APPROVALS.VERSION, TASK_APPROVALS.VERSION.plus(1))
                .where(TASK_APPROVALS.TASK_ID.eq(taskId))
                .and(TASK_APPROVALS.VERSION.eq(expectedVersion))
                .and(TASK_APPROVALS.STATUS.eq(ApprovalStatus.PENDING.name()))
                .returning()
                .fetchOptional();
    }

    /** 기한이 지나도록 답이 없는 요청. 스윕이 마감한다. */
    public List<TaskApprovalsRecord> findOverdue(OffsetDateTime now, int limit) {
        return dsl.selectFrom(TASK_APPROVALS)
                .where(TASK_APPROVALS.STATUS.eq(ApprovalStatus.PENDING.name()))
                .and(TASK_APPROVALS.EXPIRES_AT.le(now))
                .orderBy(TASK_APPROVALS.EXPIRES_AT.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * 기한이 지난 요청을 만료로 마감한다.
     *
     * <p>{@code decided_at} 을 채우지 않는다. {@code ck_task_approvals_decided_at} 이
     * 그것을 <b>사람이 결정한 것</b>에만 허용한다 — 만료는 아무도 답하지 않은 것이다.
     */
    public int expire(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return dsl.update(TASK_APPROVALS)
                .set(TASK_APPROVALS.STATUS, ApprovalStatus.EXPIRED.name())
                .set(TASK_APPROVALS.VERSION, TASK_APPROVALS.VERSION.plus(1))
                .where(TASK_APPROVALS.ID.in(ids))
                .and(TASK_APPROVALS.STATUS.eq(ApprovalStatus.PENDING.name()))
                .execute();
    }
}
