package com.likelion.slash.dispatch;

import static com.likelion.slash.jooq.Tables.AGENT_DISPATCHES;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AgentDispatchStatus;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * {@code agent_dispatches} 접근.
 *
 * <p>Pod 가 바뀌거나 WSS 가 끊겨도 미완료 전달을 다시 보낼 수 있도록 전달 시도를 원장으로 남긴다.
 *
 * <p><b>동시 실행 제한은 DB 가 강제한다.</b> 부분 UNIQUE 인덱스
 * {@code uk_dispatch_active_task}(Task 당 활성 1건)와
 * {@code uk_dispatch_active_device}(기기당 활성 1건)가 걸려 있어,
 * {@link #create} 가 중복을 만들면 {@link org.springframework.dao.DuplicateKeyException} 이 난다.
 * 서비스는 이 예외를 {@code DEVICE_BUSY} 로 옮겨 응답한다. 삼키면 안 된다.
 *
 * <p><b>만료는 저절로 일어나지 않는다.</b> 활성 전달이 만료된 채 남으면 그 Task 와 기기가 영구히 막히므로
 * {@link #expireOverdue} 를 배치가 주기적으로 호출해야 한다.
 *
 * <p>관련 문서: 3.6.2 · WBS W1-04 · W2-03
 */
@Repository
public class AgentDispatchRepository {

    /**
     * 부분 UNIQUE 인덱스가 한 건으로 제한하는 상태 목록.
     *
     * <p>목록을 여기서 따로 적지 않고 {@link AgentDispatchStatus#isActive()} 에서 끌어온다.
     * 두 곳에 적으면 상태를 추가할 때 한쪽만 고쳐도 컴파일이 통과해 조용히 어긋난다.
     */
    private static final List<String> ACTIVE_STATUSES = Arrays.stream(AgentDispatchStatus.values())
            .filter(AgentDispatchStatus::isActive)
            .map(Enum::name)
            .toList();

    private final DSLContext dsl;

    public AgentDispatchRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ------------------------------------------------------------------
    // 생성·조회
    // ------------------------------------------------------------------

    /**
     * 전달 기록을 만든다. WSS 로 실제 프레임을 보내기 전에 먼저 저장한다.
     *
     * <p>{@code fk_dispatch_task_device} 복합 FK 때문에 {@code deviceId} 는 반드시
     * {@code tasks.device_id} 와 같아야 한다. 다르면 FK 위반으로 거부된다.
     * 다른 사용자의 PC 로 작업이 새는 것을 DB 가 막는 장치다. (문서 DV-04)
     */
    public AgentDispatchesRecord create(long taskId, long deviceId, OffsetDateTime expiresAt) {
        return dsl.insertInto(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.TASK_ID, taskId)
                .set(AGENT_DISPATCHES.DEVICE_ID, deviceId)
                .set(AGENT_DISPATCHES.EXPIRES_AT, expiresAt)
                .returning()
                .fetchOne();
    }

    /** ACK·RESULT 프레임이 들고 오는 {@code dispatchId} 로 찾는다. */
    public Optional<AgentDispatchesRecord> findByPublicId(UUID publicId) {
        return dsl.selectFrom(AGENT_DISPATCHES)
                .where(AGENT_DISPATCHES.PUBLIC_ID.eq(publicId))
                .fetchOptional();
    }

    /** Task 의 진행 중인 전달. 부분 UNIQUE 때문에 최대 한 건이다. */
    public Optional<AgentDispatchesRecord> findActiveByTaskId(long taskId) {
        return dsl.selectFrom(AGENT_DISPATCHES)
                .where(AGENT_DISPATCHES.TASK_ID.eq(taskId))
                .and(AGENT_DISPATCHES.STATUS.in(ACTIVE_STATUSES))
                .fetchOptional();
    }

    /**
     * 재연결한 Agent 에게 다시 보낼 전달을 찾는다. ({@code idx_dispatch_device_status})
     *
     * <p>기기당 활성 전달은 한 건이지만, 목록으로 두어 나중에 동시 실행을 늘려도
     * 호출부를 고치지 않아도 되게 한다.
     */
    public List<AgentDispatchesRecord> findActiveByDeviceId(long deviceId) {
        return dsl.selectFrom(AGENT_DISPATCHES)
                .where(AGENT_DISPATCHES.DEVICE_ID.eq(deviceId))
                .and(AGENT_DISPATCHES.STATUS.in(ACTIVE_STATUSES))
                .orderBy(AGENT_DISPATCHES.CREATED_AT.asc())
                .fetch();
    }

    /**
     * 아직 아무 Pod 도 내보내지 못한 전달을 찾는다. (스윕 재발행 대상)
     *
     * <p>PENDING 은 "발행은 했지만 소켓으로 나가지는 않았다"는 뜻이다. Pub/Sub 이 유실했거나
     * 대상 Pod 이 재시작 중이었던 경우다. 실제로 나가면 DISPATCHED 가 되어 여기서 빠진다.
     *
     * <p>기한이 지난 전달은 다시 보내도 Agent 가 거부하므로 제외한다.
     *
     * @param createdBefore 이 시각 이전에 만들어진 것만. 방금 발행한 전달을 곧바로 다시 보내지 않는다.
     * @param now           만료 판정 기준 시각
     */
    public List<AgentDispatchesRecord> findPendingForResend(
            OffsetDateTime createdBefore, OffsetDateTime now, int limit) {
        return dsl.selectFrom(AGENT_DISPATCHES)
                .where(AGENT_DISPATCHES.STATUS.eq(AgentDispatchStatus.PENDING.name()))
                .and(AGENT_DISPATCHES.CREATED_AT.le(createdBefore))
                .and(AGENT_DISPATCHES.EXPIRES_AT.gt(now))
                .orderBy(AGENT_DISPATCHES.CREATED_AT.asc())
                .limit(limit)
                .fetch();
    }

    // ------------------------------------------------------------------
    // 전달 진행
    // ------------------------------------------------------------------

    /**
     * WSS 로 TASK 프레임을 보낸 시점을 기록한다.
     *
     * <p>재연결 후 다시 보낼 때도 이 메서드를 쓴다. {@code attempt_count} 가 올라가므로
     * 몇 번 만에 전달됐는지 그대로 남는다.
     */
    public boolean markDispatched(long id) {
        return dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.STATUS, AgentDispatchStatus.DISPATCHED.name())
                .set(AGENT_DISPATCHES.DISPATCHED_AT, SlashTime.now())
                .set(AGENT_DISPATCHES.ATTEMPT_COUNT, AGENT_DISPATCHES.ATTEMPT_COUNT.plus(1))
                .where(AGENT_DISPATCHES.ID.eq(id))
                .and(AGENT_DISPATCHES.STATUS.in(
                        AgentDispatchStatus.PENDING.name(),
                        AgentDispatchStatus.DISPATCHED.name()))
                .execute() == 1;
    }

    /**
     * Agent 가 작업을 받아들였다. 같은 ACK 를 두 번 받아도 한 번만 반영된다.
     *
     * <p><b>PENDING 상태의 ACK 도 받는다.</b> 전달은 "소켓에 쓴 뒤" DISPATCHED 로 기록하는데,
     * Agent 의 ACK 가 그 기록보다 먼저 도착할 수 있다. 실제로 로컬 확인에서 매번 그랬다.
     * DISPATCHED 만 받으면 그 ACK 가 조용히 버려져 {@code acknowledged_at} 이 영영 비어 있게 된다.
     * ACK 가 왔다는 것은 프레임이 나갔다는 뜻이므로 {@code dispatched_at} 도 여기서 채운다.
     *
     * <p><b>기한을 실행 기준으로 다시 잡는다.</b> {@code slash.dispatch.ttl}(60초)은 <b>전달이
     * 도달하는 데</b> 주는 시간이다 — 기기가 켜져 있는 것을 확인한 뒤에만 전달을 만들기 때문에
     * 짧아도 된다. 그 값을 실행에까지 쓰면 60초 넘게 걸리는 작업이 실행 도중에 만료된다.
     * 만료 스윕이 전달과 작업을 마감해 버리므로, PC 가 일을 마치고 보낸 RESULT 는
     * {@code isActive} 에서 걸려 버려진다. <b>사용자는 "기한이 지났습니다"를 보는데 PC 는
     * 작업을 끝낸 상태</b>가 된다.
     *
     * <p>Agent 계약과 어긋나지 않는다. TASK 프레임의 {@code expiresAt} 은 "이 시각을 지나면
     * 실행하지 않는다"는 <b>실행 시작</b> 판정이고({@link AgentTaskFrame}), ACK 는 이미 실행하기로
     * 했다는 뜻이다. 그 뒤의 기한은 서버가 관리한다.
     *
     * @param executionDeadline 실행에 주는 기한. 이 시각까지 RESULT 가 없으면 만료 스윕이 마감한다
     */
    public boolean acknowledge(long id, OffsetDateTime executionDeadline) {
        OffsetDateTime now = SlashTime.now();

        return dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.STATUS, AgentDispatchStatus.ACKNOWLEDGED.name())
                .set(AGENT_DISPATCHES.ACKNOWLEDGED_AT, now)
                .set(AGENT_DISPATCHES.DISPATCHED_AT,
                        DSL.coalesce(AGENT_DISPATCHES.DISPATCHED_AT, DSL.val(now)))
                .set(AGENT_DISPATCHES.EXPIRES_AT, executionDeadline)
                .where(AGENT_DISPATCHES.ID.eq(id))
                .and(AGENT_DISPATCHES.STATUS.in(
                        AgentDispatchStatus.PENDING.name(),
                        AgentDispatchStatus.DISPATCHED.name()))
                .execute() == 1;
    }

    /**
     * 결과를 반영하고 전달을 마감한다.
     *
     * <p>활성 상태에서만 반영되므로 RESULT 프레임을 두 번 받아도 결과가 두 번 쓰이지 않는다.
     * 마감해야 부분 UNIQUE 에서 빠져 그 기기가 다음 작업을 받을 수 있다.
     */
    public boolean complete(long id) {
        return dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.STATUS, AgentDispatchStatus.COMPLETED.name())
                .set(AGENT_DISPATCHES.COMPLETED_AT, SlashTime.now())
                .where(AGENT_DISPATCHES.ID.eq(id))
                .and(AGENT_DISPATCHES.STATUS.in(ACTIVE_STATUSES))
                .execute() == 1;
    }

    /**
     * Agent 가 거부했거나 실행에 실패했다.
     *
     * @param reasonCode ACK 의 {@code reasonCode} 또는 RESULT 의 {@code error.code}
     */
    public boolean fail(long id, String reasonCode) {
        return dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.STATUS, AgentDispatchStatus.FAILED.name())
                .set(AGENT_DISPATCHES.REASON_CODE, reasonCode)
                .set(AGENT_DISPATCHES.COMPLETED_AT, SlashTime.now())
                .where(AGENT_DISPATCHES.ID.eq(id))
                .and(AGENT_DISPATCHES.STATUS.in(ACTIVE_STATUSES))
                .execute() == 1;
    }

    /**
     * 기한이 지난 활성 전달을 만료로 마감한다. ({@code idx_dispatch_active_expires})
     *
     * <p>이 배치가 돌지 않으면 그 Task 와 기기가 영구히 막힌다.
     *
     * <p><b>마감한 행을 돌려주는 이유</b> — 전달을 푸는 것만으로는 끝나지 않는다. 그 전달에
     * 매달린 Task 도 함께 마감하고 브라우저에 알려야 하는데, 부르는 쪽이 대상을 알 방법이
     * 이 반환값밖에 없다. 건수만 돌려주면 어느 작업이 풀렸는지 다시 찾아야 하고, 그 사이에
     * 다른 Pod 이 같은 행을 또 집을 수 있다.
     *
     * <p><b>여러 Pod 이 동시에 돌아도 한 곳만 받는다.</b> 만료 대상을 고르는 조건을 서브질의
     * 안에만 두면 두 Pod 이 같은 행을 함께 받아 알림이 두 번 나간다. Postgres 가 행 잠금을 푼
     * 뒤 다시 보는 것은 바깥 {@code WHERE} 뿐이라, 활성 조건을 바깥에도 남겨 둔다.
     *
     * @param limit 한 회차에서 마감할 최대 건수. 밀린 양이 많아도 회차를 길게 잡지 않는다.
     * @return 마감한 전달. 각 행의 {@code task_id}·{@code device_id} 로 뒷정리를 이어 간다
     */
    public List<AgentDispatchesRecord> expireOverdue(OffsetDateTime now, String reasonCode, int limit) {
        return dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.STATUS, AgentDispatchStatus.EXPIRED.name())
                .set(AGENT_DISPATCHES.REASON_CODE, reasonCode)
                .set(AGENT_DISPATCHES.COMPLETED_AT, SlashTime.now())
                .where(AGENT_DISPATCHES.STATUS.in(ACTIVE_STATUSES))
                .and(AGENT_DISPATCHES.ID.in(
                        DSL.select(AGENT_DISPATCHES.ID)
                                .from(AGENT_DISPATCHES)
                                .where(AGENT_DISPATCHES.STATUS.in(ACTIVE_STATUSES))
                                .and(AGENT_DISPATCHES.EXPIRES_AT.le(now))
                                .orderBy(AGENT_DISPATCHES.EXPIRES_AT.asc())
                                .limit(limit)))
                .returning()
                .fetch();
    }
}
