package com.likelion.slash.job;

import static com.likelion.slash.jooq.Tables.OUTBOX_EVENTS;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.OutboxAggregateType;
import com.likelion.slash.jooq.tables.records.OutboxEventsRecord;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/**
 * {@code outbox_events} 접근.
 *
 * <p>DB 저장과 SQS 발행 사이의 유실을 막는다. 사건은 업무 트랜잭션 안에서 함께 저장하고,
 * 발행은 트랜잭션 밖의 전달기가 미발행 건을 읽어 처리한다. (문서 2.8.2)
 *
 * <p>slash-api 는 최소 2 Pod 로 운영되므로 여러 전달기가 같은 행을 집으면 같은 메시지가 두 번 나간다.
 * {@link #pollUnpublished} 가 {@code FOR UPDATE SKIP LOCKED} 를 쓰는 이유다.
 * 한 Pod 가 집은 행은 다른 Pod 가 건너뛴다.
 *
 * <p>관련 문서: 2.8.2 · 3.8 · WBS W3-02
 */
@Repository
public class OutboxEventRepository {

    private final DSLContext dsl;

    public OutboxEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * 발행할 사건을 남긴다. 업무 트랜잭션 안에서 호출한다.
     *
     * <p>여러 원장을 가리키므로 FK 가 없다. {@code aggregateId} 가 실제로 존재하는지는
     * 서비스 계층이 보장한다.
     */
    public OutboxEventsRecord append(OutboxAggregateType aggregateType,
                                     long aggregateId,
                                     String eventType,
                                     JSONB payload) {
        return dsl.insertInto(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.AGGREGATE_TYPE, aggregateType.name())
                .set(OUTBOX_EVENTS.AGGREGATE_ID, aggregateId)
                .set(OUTBOX_EVENTS.EVENT_TYPE, eventType)
                .set(OUTBOX_EVENTS.PAYLOAD, payload)
                .returning()
                .fetchOne();
    }

    /**
     * 발행 대상을 집어 온다. ({@code idx_outbox_unpublished})
     *
     * <p>집은 행은 이 트랜잭션이 끝날 때까지 다른 Pod 가 보지 못한다.
     * 따라서 SQS 발행과 {@link #markPublished} 까지 같은 트랜잭션에서 끝내야 한다.
     * 반대로 트랜잭션을 너무 오래 잡으면 다른 Pod 가 놀게 되므로 {@code limit} 는 작게 둔다.
     *
     * <p>{@code available_at} 이 아직 오지 않은 행은 재시도 대기 중이므로 건너뛴다.
     */
    public List<OutboxEventsRecord> pollUnpublished(int limit) {
        return dsl.selectFrom(OUTBOX_EVENTS)
                .where(OUTBOX_EVENTS.PUBLISHED_AT.isNull())
                .and(OUTBOX_EVENTS.AVAILABLE_AT.le(SlashTime.now()))
                .orderBy(OUTBOX_EVENTS.AVAILABLE_AT.asc(), OUTBOX_EVENTS.ID.asc())
                .limit(limit)
                .forUpdate()
                .skipLocked()
                .fetch();
    }

    /** 발행 성공. 이 행은 부분 인덱스에서 빠져 다음 조회 대상이 되지 않는다. */
    public boolean markPublished(long id) {
        return dsl.update(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.PUBLISHED_AT, SlashTime.now())
                .where(OUTBOX_EVENTS.ID.eq(id))
                .and(OUTBOX_EVENTS.PUBLISHED_AT.isNull())
                .execute() == 1;
    }

    /**
     * 발행에 실패해 재시도를 미룬다.
     *
     * <p>{@code available_at} 을 뒤로 밀지 않으면 실패한 행을 곧바로 다시 집어
     * 같은 실패를 반복하며 다른 사건의 발행까지 막는다.
     *
     * @param availableAt 다음 시도 시각 (백오프)
     */
    public boolean scheduleRetry(long id, OffsetDateTime availableAt) {
        return dsl.update(OUTBOX_EVENTS)
                .set(OUTBOX_EVENTS.ATTEMPT_COUNT, OUTBOX_EVENTS.ATTEMPT_COUNT.plus(1))
                .set(OUTBOX_EVENTS.AVAILABLE_AT, availableAt)
                .where(OUTBOX_EVENTS.ID.eq(id))
                .and(OUTBOX_EVENTS.PUBLISHED_AT.isNull())
                .execute() == 1;
    }

    /**
     * 발행이 끝난 오래된 행을 지운다. 배치가 주기적으로 호출한다.
     *
     * @return 지운 건수
     */
    public int deletePublishedBefore(OffsetDateTime threshold) {
        return dsl.deleteFrom(OUTBOX_EVENTS)
                .where(OUTBOX_EVENTS.PUBLISHED_AT.isNotNull())
                .and(OUTBOX_EVENTS.PUBLISHED_AT.lt(threshold))
                .execute();
    }
}
