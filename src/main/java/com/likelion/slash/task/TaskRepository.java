package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.TASKS;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ProcessingRoute;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * {@code tasks} 접근.
 *
 * <p>사용자 요청의 최종 원장이다. 즉시 끝나는 요청도 이력을 일관되게 만들기 위해 Task 를 만든다. (문서 3.4.6)
 *
 * <p><b>상태 전이</b> — 모든 전이 메서드는 다음 두 가지를 함께 지킨다.
 * <ol>
 *   <li>{@link TaskStatus#canTransitionTo} 로 계약상 허용된 전이인지 Java 에서 먼저 확인한다</li>
 *   <li>{@code WHERE status = 기대값} 으로 DB 에서 한 번 더 비교한다 (compare-and-set)</li>
 * </ol>
 * 두 번째가 없으면 두 요청이 동시에 같은 Task 를 옮길 때 나중 것이 앞선 전이를 덮어쓴다.
 * 반환값이 비어 있으면 다른 곳에서 먼저 상태를 바꾼 것이므로 서비스는 409 로 응답한다. (문서 3.10)
 *
 * <p>전이 메서드는 성공 여부만이 아니라 <b>갱신된 행을 함께 돌려준다</b>({@code RETURNING}).
 * 부르는 쪽은 대개 바로 뒤에 {@code user_id}·{@code public_id}·{@code result} 가 필요한데,
 * 다시 조회하면 질의가 하나 늘 뿐 아니라 그 사이에 행이 또 바뀔 수 있다.
 *
 * <p>상태 전이와 {@code task_events} 기록은 반드시 같은 트랜잭션에서 수행한다.
 * {@link TaskEventRepository#append} 를 참고한다.
 *
 * <p>관련 문서: 3.4.6~3.4.8 · 3.10 · WBS W1-04
 */
@Repository
public class TaskRepository {

    /** 아직 결과가 확정되지 않은 상태. 기기당 동시 작업 1건 판정에 쓴다. */
    private static final List<String> ACTIVE_STATUSES = Arrays.stream(TaskStatus.values())
            .filter(status -> !status.isTerminal())
            .map(TaskStatus::name)
            .toList();

    private final DSLContext dsl;

    public TaskRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ------------------------------------------------------------------
    // 생성·조회
    // ------------------------------------------------------------------

    /**
     * 요청을 접수한다.
     *
     * <p>분석 전에도 요청을 추적할 수 있도록 원문을 먼저 저장한다.
     * 작업 유형과 처리 경로는 NLU 응답을 검증한 뒤 {@link #applyAnalysis} 로 채운다.
     */
    public TasksRecord create(long userId, String inputText, UUID correlationId) {
        return dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.INPUT_TEXT, inputText)
                .set(TASKS.CORRELATION_ID, correlationId)
                .returning()
                .fetchOne();
    }

    /** 소유권을 강제한 단건 조회. 남의 작업이면 비어 있다. (문서 3.2.3) */
    public Optional<TasksRecord> findByPublicIdAndUserId(UUID publicId, long userId) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.PUBLIC_ID.eq(publicId))
                .and(TASKS.USER_ID.eq(userId))
                .fetchOptional();
    }

    /** Agent·Worker 결과 반영처럼 사용자 맥락이 없는 경로에서 사용한다. */
    public Optional<TasksRecord> findById(long id) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.ID.eq(id))
                .fetchOptional();
    }

    /**
     * 최근 이력을 커서 방식으로 읽는다. ({@code idx_tasks_user_created})
     *
     * <p>OFFSET 을 쓰지 않으므로 목록을 넘기는 동안 새 작업이 생겨도 항목이 밀리거나 겹치지 않는다.
     * 다음 쪽을 요청할 때는 마지막 행의 {@code created_at} 과 {@code id} 를 그대로 넘긴다.
     *
     * @param cursorCreatedAt 첫 쪽이면 {@code null}
     * @param cursorId        첫 쪽이면 {@code null}
     */
    public List<TasksRecord> findRecent(long userId,
                                        OffsetDateTime cursorCreatedAt,
                                        Long cursorId,
                                        int limit) {
        Condition afterCursor = (cursorCreatedAt == null || cursorId == null)
                ? DSL.noCondition()
                : DSL.row(TASKS.CREATED_AT, TASKS.ID).lt(cursorCreatedAt, cursorId);

        return dsl.selectFrom(TASKS)
                .where(TASKS.USER_ID.eq(userId))
                .and(afterCursor)
                .orderBy(TASKS.CREATED_AT.desc(), TASKS.ID.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * 이 기기가 <b>지금 실제로 붙들려 있는지</b> 확인한다. (P0 는 기기당 동시 1건)
     *
     * <p>세는 대상은 {@code QUEUED}·{@code RUNNING} 둘뿐이다. {@code WAITING_FOR_DEVICE} 는
     * 아직 기기로 보내지도 않은 상태라 기기를 붙들고 있지 않다. 미완료 작업 전체를 세면
     * <b>PC 가 꺼져 있을 때 두 번째 요청이 DEVICE_BUSY 로 거부된다</b> — 꺼진 PC 는 아무것도
     * 실행 중이 아닌데 사실과 다르게 답하는 셈이고, 미리 접수해 두는 것 자체를 막는다.
     *
     * <p><b>최종 판정은 여기가 아니다.</b> 이 조회는 스냅샷이라 같은 순간에 들어온 두 요청을
     * 갈라내지 못한다. 판정은 {@code agent_dispatches} 의 {@code uk_dispatch_active_device} 가
     * 하고, 여기서는 요청을 받자마자 빠르게 되돌려 주기 위한 사전 확인으로만 쓴다.
     */
    public boolean isDeviceOccupied(long deviceId) {
        return dsl.fetchExists(
                dsl.selectFrom(TASKS)
                        .where(TASKS.DEVICE_ID.eq(deviceId))
                        .and(TASKS.STATUS.in(TaskStatus.QUEUED.name(), TaskStatus.RUNNING.name())));
    }

    /**
     * 이 기기를 기다리고 있는 작업. 오래 기다린 것부터 돌려준다. (WBS W1-04)
     *
     * <p>기기가 꺼져 있는 동안 접수된 작업은 전달을 만들지 않고 {@code WAITING_FOR_DEVICE} 로
     * 남는다. Agent 가 다시 붙어 READY 를 보고할 때 이 목록을 꺼내 내보낸다.
     *
     * <p>전달을 미리 만들어 두지 않는 이유는 {@code agent_dispatches.expires_at} 때문이다.
     * 기기가 언제 켜질지 모르는데 기한을 먼저 박으면 켜지기도 전에 만료된다.
     */
    public List<TasksRecord> findWaitingForDevice(long deviceId, int limit) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.DEVICE_ID.eq(deviceId))
                .and(TASKS.STATUS.eq(TaskStatus.WAITING_FOR_DEVICE.name()))
                .orderBy(TASKS.CREATED_AT.asc(), TASKS.ID.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * 작업 행을 잠근다. {@code task_events.sequence} 채번과 상태 전이의 직렬화 기준점이다.
     *
     * <p>잠금은 호출한 트랜잭션이 끝날 때 풀린다.
     */
    public Optional<TasksRecord> lockById(long id) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.ID.eq(id))
                .forUpdate()
                .fetchOptional();
    }

    // ------------------------------------------------------------------
    // 분석 결과 반영
    // ------------------------------------------------------------------

    /**
     * NLU 분석 결과와 slash-api 가 결정한 처리 경로를 반영한다.
     *
     * <p>처리 경로는 Tool 정책에 따라 slash-api 가 정한다. NLU 가 직접 정하지 않는다.
     * {@code ck_tasks_local_agent_requires_device} 가 있으므로 로컬 실행 작업에는
     * 반드시 대상 기기를 함께 넘겨야 한다.
     *
     * @return 반영 여부. 거짓이면 이미 다른 상태로 넘어간 작업이다.
     */
    public boolean applyAnalysis(long id,
                                 TaskType taskType,
                                 ProcessingRoute processingRoute,
                                 Long deviceId,
                                 JSONB parameters,
                                 String requestSummary) {
        if (processingRoute == ProcessingRoute.LOCAL_AGENT && deviceId == null) {
            throw new IllegalArgumentException("로컬 실행 작업에는 대상 기기가 필요합니다. taskId=" + id);
        }

        return dsl.update(TASKS)
                .set(TASKS.TASK_TYPE, taskType.name())
                .set(TASKS.PROCESSING_ROUTE, processingRoute.name())
                .set(TASKS.DEVICE_ID, deviceId)
                .set(TASKS.PARAMETERS, parameters)
                .set(TASKS.REQUEST_SUMMARY, requestSummary)
                .set(TASKS.VERSION, TASKS.VERSION.plus(1))
                .where(TASKS.ID.eq(id))
                .and(TASKS.STATUS.eq(TaskStatus.ANALYZING.name()))
                .execute() == 1;
    }

    // ------------------------------------------------------------------
    // 상태 전이
    // ------------------------------------------------------------------

    /**
     * 중간 상태로 옮긴다.
     *
     * <p>최종 상태는 결과나 오류 코드를 함께 써야 {@code ck_tasks_completed_at} 을 만족하므로
     * {@link #succeed} · {@link #finishWithError} 를 쓴다.
     *
     * @return 갱신된 행. 비어 있으면 현재 상태가 {@code expected} 가 아니다.
     */
    public Optional<TasksRecord> transition(long id, TaskStatus expected, TaskStatus next) {
        if (next.isTerminal()) {
            throw new IllegalArgumentException(
                    "최종 상태로의 전이는 succeed·finishWithError 를 사용합니다. next=" + next);
        }
        requireAllowed(expected, next);

        return dsl.update(TASKS)
                .set(TASKS.STATUS, next.name())
                .set(TASKS.VERSION, TASKS.VERSION.plus(1))
                .where(TASKS.ID.eq(id))
                .and(TASKS.STATUS.eq(expected.name()))
                .returning()
                .fetchOptional();
    }

    /**
     * 결과를 저장하고 성공으로 마감한다.
     *
     * <p>{@code result} 는 64KB 를 넘을 수 없다. ({@code ck_tasks_result_size})
     * Agent 가 이미 {@code limit}·{@code truncated} 로 줄여 보내지만 상한을 넘으면 DB 가 거부한다.
     *
     * @return 갱신된 행. 비어 있으면 현재 상태가 {@code expected} 가 아니다.
     */
    public Optional<TasksRecord> succeed(long id, TaskStatus expected, JSONB result) {
        requireAllowed(expected, TaskStatus.SUCCEEDED);

        return dsl.update(TASKS)
                .set(TASKS.STATUS, TaskStatus.SUCCEEDED.name())
                .set(TASKS.RESULT, result)
                .set(TASKS.COMPLETED_AT, SlashTime.now())
                .set(TASKS.VERSION, TASKS.VERSION.plus(1))
                .where(TASKS.ID.eq(id))
                .and(TASKS.STATUS.eq(expected.name()))
                .returning()
                .fetchOptional();
    }

    /**
     * 실패 또는 기한 만료로 마감한다.
     *
     * <p>{@code ck_tasks_result_or_error} 때문에 성공 결과와 오류 코드는 함께 있을 수 없다.
     * 부분 결과가 남아 있어도 실패로 마감할 때는 지운다.
     *
     * @param terminal {@link TaskStatus#FAILED} 또는 {@link TaskStatus#EXPIRED}
     * @return 갱신된 행. 비어 있으면 현재 상태가 {@code expected} 가 아니다.
     */
    public Optional<TasksRecord> finishWithError(long id,
                                                 TaskStatus expected,
                                                 TaskStatus terminal,
                                                 ErrorCode errorCode) {
        if (terminal != TaskStatus.FAILED && terminal != TaskStatus.EXPIRED) {
            throw new IllegalArgumentException("실패 마감은 FAILED·EXPIRED 만 가능합니다. terminal=" + terminal);
        }
        requireAllowed(expected, terminal);

        return dsl.update(TASKS)
                .set(TASKS.STATUS, terminal.name())
                .set(TASKS.ERROR_CODE, errorCode.name())
                .set(TASKS.RESULT, (JSONB) null)
                .set(TASKS.COMPLETED_AT, SlashTime.now())
                .set(TASKS.VERSION, TASKS.VERSION.plus(1))
                .where(TASKS.ID.eq(id))
                .and(TASKS.STATUS.eq(expected.name()))
                .returning()
                .fetchOptional();
    }

    /**
     * 기한이 지나도록 끝나지 않은 작업을 찾는다. 만료 배치가 쓴다.
     *
     * <p><b>왜 마감까지 한 문장으로 하지 않는가</b> — 대량 UPDATE 는 두 가지를 함께 잃는다.
     * <ol>
     *   <li><b>이전 상태</b>. {@code RETURNING} 이 돌려주는 것은 이미 바뀐 뒤의 행이라
     *       {@code task_events} 에 적을 {@code from} 이 남지 않는다. 타임라인이 곧 화면의
     *       진행 표시라, 마지막 칸이 비면 어디서 멈췄는지 보이지 않는다</li>
     *   <li><b>알림</b>. 다른 마감은 모두 {@link TaskStateWriter} 를 거쳐
     *       {@code TASK_STATUS_CHANGED} 와 {@code TASK_RESULT_AVAILABLE} 을 함께 내보낸다.
     *       대량 UPDATE 는 그 경로를 타지 않아 <b>만료된 작업의 화면이 새로고침 전까지 영영
     *       "진행 중"에 머문다</b></li>
     * </ol>
     * 그래서 대상만 찾아 주고, 마감은 한 건씩 {@link TaskStateWriter#expire} 가 한다.
     * 여러 Pod 이 같은 작업을 집어도 그쪽 compare-and-set 이 한 번만 통과시킨다.
     *
     * @param createdBefore 이 시각 이전에 만들어진 미완료 작업을 대상으로 한다
     * @param limit         한 회차에서 처리할 최대 건수
     */
    public List<TasksRecord> findOverdue(OffsetDateTime createdBefore, int limit) {
        return dsl.selectFrom(TASKS)
                .where(TASKS.STATUS.in(ACTIVE_STATUSES))
                .and(TASKS.CREATED_AT.lt(createdBefore))
                .orderBy(TASKS.CREATED_AT.asc())
                .limit(limit)
                .fetch();
    }

    private static void requireAllowed(TaskStatus from, TaskStatus to) {
        if (!from.canTransitionTo(to)) {
            throw new IllegalArgumentException("허용되지 않은 상태 전이입니다. " + from + " -> " + to);
        }
    }
}
