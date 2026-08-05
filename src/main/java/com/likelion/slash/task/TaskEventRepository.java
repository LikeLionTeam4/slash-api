package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.TASK_EVENTS;

import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.jooq.tables.records.TaskEventsRecord;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * {@code task_events} 접근.
 *
 * <p>상태 전이 타임라인. WSS 가 끊겨도 REST 로 진행 상황을 복구하는 근거가 된다. (문서 7.3.1)
 *
 * <p><b>반드시 상태 전이와 같은 트랜잭션에서 기록한다.</b> (문서 3.10)
 * {@link #append} 는 채번을 위해 {@code tasks} 행을 잠그는데, 트랜잭션 밖에서 부르면
 * 문장 단위로 자동 반영되어 잠금이 즉시 풀리고 채번이 어긋난다.
 *
 * <p>관련 문서: 3.4.7 · 3.10 · WBS W1-04
 */
@Repository
public class TaskEventRepository {

    private final DSLContext dsl;

    public TaskEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * 상태 전이를 타임라인에 남긴다.
     *
     * <p>같은 밀리초에 여러 이벤트가 생길 수 있어 {@code occurred_at} 만으로는 순서를 정할 수 없다.
     * 그래서 {@code sequence} 를 따로 채번하는데, 두 트랜잭션이 같은 번호를 읽으면
     * {@code uk_task_events_sequence} 위반으로 한 쪽이 실패한다.
     * 이를 막기 위해 {@code tasks} 행을 먼저 잠가 채번을 직렬화한다.
     *
     * @param from 최초 생성 이벤트면 {@code null}
     * @param reasonCode 실패·대기·거부의 고정 사유 코드. 없으면 {@code null}
     * @param message 사용자에게 보여줄 설명. 민감정보를 넣지 않는다.
     */
    public TaskEventsRecord append(long taskId,
                                   TaskStatus from,
                                   TaskStatus to,
                                   String reasonCode,
                                   String message) {
        if (from == to) {
            throw new IllegalArgumentException("같은 상태로의 전이는 기록하지 않습니다. status=" + to);
        }

        Long lockedTaskId = dsl.select(TASKS.ID)
                .from(TASKS)
                .where(TASKS.ID.eq(taskId))
                .forUpdate()
                .fetchOne(TASKS.ID);

        if (lockedTaskId == null) {
            throw new IllegalStateException("존재하지 않는 작업입니다. taskId=" + taskId);
        }

        Integer nextSequence = dsl.select(DSL.coalesce(DSL.max(TASK_EVENTS.SEQUENCE), 0).plus(1))
                .from(TASK_EVENTS)
                .where(TASK_EVENTS.TASK_ID.eq(taskId))
                .fetchOne(0, Integer.class);

        return dsl.insertInto(TASK_EVENTS)
                .set(TASK_EVENTS.TASK_ID, taskId)
                .set(TASK_EVENTS.SEQUENCE, nextSequence)
                .set(TASK_EVENTS.FROM_STATUS, from == null ? null : from.name())
                .set(TASK_EVENTS.TO_STATUS, to.name())
                .set(TASK_EVENTS.REASON_CODE, reasonCode)
                .set(TASK_EVENTS.MESSAGE, message)
                .returning()
                .fetchOne();
    }

    /** 작업 하나의 타임라인 전체. 발생 순서대로 반환한다. */
    public List<TaskEventsRecord> findAllByTaskId(long taskId) {
        return dsl.selectFrom(TASK_EVENTS)
                .where(TASK_EVENTS.TASK_ID.eq(taskId))
                .orderBy(TASK_EVENTS.SEQUENCE.asc())
                .fetch();
    }

    /**
     * 마지막으로 받은 순번 이후의 이벤트만 읽는다.
     *
     * <p>WSS 가 끊겼다가 다시 붙었을 때 놓친 구간만 REST 로 따라잡는 용도다.
     * {@code sequence} 는 API 로 노출하지 않으므로 화면에는 서버가 준 커서를 그대로 돌려받는다.
     */
    public List<TaskEventsRecord> findAfterSequence(long taskId, int afterSequence, int limit) {
        return dsl.selectFrom(TASK_EVENTS)
                .where(TASK_EVENTS.TASK_ID.eq(taskId))
                .and(TASK_EVENTS.SEQUENCE.gt(afterSequence))
                .orderBy(TASK_EVENTS.SEQUENCE.asc())
                .limit(limit)
                .fetch();
    }
}
