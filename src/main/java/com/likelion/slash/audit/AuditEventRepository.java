package com.likelion.slash.audit;

import static com.likelion.slash.jooq.Tables.AUDIT_EVENTS;

import com.likelion.slash.common.enums.AuditActorType;
import com.likelion.slash.common.enums.AuditTargetType;
import com.likelion.slash.jooq.tables.records.AuditEventsRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

/**
 * {@code audit_events} 접근.
 *
 * <p>기록은 남기기만 하고 고치거나 지우지 않는다. 그래서 갱신·삭제 메서드를 두지 않는다.
 *
 * <p>{@code detail} 에 비밀값·전체 파일 경로를 넣지 않는다. IP 는 원문 대신 해시만 남긴다.
 *
 * <p>{@code ck_audit_events_target} 때문에 대상 종류와 식별자는 함께 있거나 함께 없어야 한다.
 * {@link #record} 가 넘기기 전에 확인한다.
 *
 * <p>관련 문서: 0.7 · WBS W1-05
 */
@Repository
public class AuditEventRepository {

    private final DSLContext dsl;

    public AuditEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * 감사 기록을 남긴다.
     *
     * @param userId         시스템 배치처럼 사용자 맥락이 없으면 {@code null}
     * @param action         무슨 일이 일어났는지 (예: {@code DEVICE_REGISTERED})
     * @param targetType     대상이 없으면 {@code null}
     * @param targetPublicId 대상이 없으면 {@code null}. 내부 PK 가 아니라 공개 식별자를 넘긴다.
     * @param detail         부가 정보. 비밀값·전체 경로를 넣지 않는다.
     * @param ipHash         원문 IP 가 아닌 해시
     */
    public AuditEventsRecord record(Long userId,
                                    AuditActorType actorType,
                                    String action,
                                    AuditTargetType targetType,
                                    UUID targetPublicId,
                                    JSONB detail,
                                    String ipHash) {
        if ((targetType == null) != (targetPublicId == null)) {
            throw new IllegalArgumentException(
                    "대상 종류와 식별자는 함께 있거나 함께 없어야 합니다. action=" + action);
        }

        return dsl.insertInto(AUDIT_EVENTS)
                .set(AUDIT_EVENTS.USER_ID, userId)
                .set(AUDIT_EVENTS.ACTOR_TYPE, actorType.name())
                .set(AUDIT_EVENTS.ACTION, action)
                .set(AUDIT_EVENTS.TARGET_TYPE, targetType == null ? null : targetType.name())
                .set(AUDIT_EVENTS.TARGET_PUBLIC_ID, targetPublicId)
                .set(AUDIT_EVENTS.DETAIL, detail)
                .set(AUDIT_EVENTS.IP_HASH, ipHash)
                .returning()
                .fetchOne();
    }

    /** 사용자별 최근 감사 기록. ({@code idx_audit_user_time}) */
    public List<AuditEventsRecord> findRecentByUserId(long userId, int limit) {
        return dsl.selectFrom(AUDIT_EVENTS)
                .where(AUDIT_EVENTS.USER_ID.eq(userId))
                .orderBy(AUDIT_EVENTS.OCCURRED_AT.desc(), AUDIT_EVENTS.ID.desc())
                .limit(limit)
                .fetch();
    }

    /**
     * 특정 자원의 사건 이력. (예: 이 PC 의 등록·해제 내역, {@code idx_audit_target})
     *
     * <p>사용자를 지워 {@code user_id} 가 비어도 이 조회는 그대로 동작한다.
     */
    public List<AuditEventsRecord> findRecentByTarget(AuditTargetType targetType, UUID targetPublicId, int limit) {
        return dsl.selectFrom(AUDIT_EVENTS)
                .where(AUDIT_EVENTS.TARGET_TYPE.eq(targetType.name()))
                .and(AUDIT_EVENTS.TARGET_PUBLIC_ID.eq(targetPublicId))
                .orderBy(AUDIT_EVENTS.OCCURRED_AT.desc(), AUDIT_EVENTS.ID.desc())
                .limit(limit)
                .fetch();
    }
}
