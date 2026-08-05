package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.IDEMPOTENCY_RECORDS;

import com.likelion.slash.jooq.tables.records.IdempotencyRecordsRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * {@code idempotency_records} 접근.
 *
 * <p>중복 클릭이나 HTTP 재전송으로 같은 작업이 두 번 만들어지는 것을 막는다.
 * 보존 기간은 24시간이다.
 *
 * <p><b>판정 규칙</b> (문서 3.4.6)
 * <ul>
 *   <li>같은 키 + 같은 본문 → 기존 Task 를 그대로 반환</li>
 *   <li>같은 키 + 다른 본문 → {@code IDEMPOTENCY_CONFLICT} 로 거부</li>
 * </ul>
 * 두 요청이 같은 순간에 도착해도 {@code uk_idempotency_scope} 가 한 건만 남기므로,
 * 경쟁에서 진 쪽은 {@link #tryInsert} 가 비어 있는 결과를 돌려준다.
 *
 * <p>관련 문서: 3.4.6 · WBS W1-04
 */
@Repository
public class IdempotencyRecordRepository {

    private final DSLContext dsl;

    public IdempotencyRecordRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** 사용자·키·경로 조합으로 기존 처리 결과를 찾는다. */
    public Optional<IdempotencyRecordsRecord> find(long userId, String idempotencyKey, String requestPath) {
        return dsl.selectFrom(IDEMPOTENCY_RECORDS)
                .where(IDEMPOTENCY_RECORDS.USER_ID.eq(userId))
                .and(IDEMPOTENCY_RECORDS.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .and(IDEMPOTENCY_RECORDS.REQUEST_PATH.eq(requestPath))
                .fetchOptional();
    }

    /**
     * 멱등 기록을 선점한다.
     *
     * <p>이미 같은 조합이 있으면 아무것도 바꾸지 않고 비어 있는 결과를 돌려준다.
     * 호출부는 그때 {@link #find} 로 기존 기록을 읽어, 본문 해시가 같으면 기존 Task 를 반환하고
     * 다르면 {@code IDEMPOTENCY_CONFLICT} 로 거부한다.
     *
     * @param requestHash 요청 본문의 해시. 같은 키에 다른 본문이 왔는지 판별한다.
     * @return 새로 선점했으면 그 기록, 이미 있으면 비어 있음
     */
    public Optional<IdempotencyRecordsRecord> tryInsert(long userId,
                                                        String idempotencyKey,
                                                        String requestPath,
                                                        String requestHash,
                                                        long taskId,
                                                        Integer responseStatus,
                                                        OffsetDateTime expiresAt) {
        return dsl.insertInto(IDEMPOTENCY_RECORDS)
                .set(IDEMPOTENCY_RECORDS.USER_ID, userId)
                .set(IDEMPOTENCY_RECORDS.IDEMPOTENCY_KEY, idempotencyKey)
                .set(IDEMPOTENCY_RECORDS.REQUEST_PATH, requestPath)
                .set(IDEMPOTENCY_RECORDS.REQUEST_HASH, requestHash)
                .set(IDEMPOTENCY_RECORDS.TASK_ID, taskId)
                .set(IDEMPOTENCY_RECORDS.RESPONSE_STATUS, responseStatus)
                .set(IDEMPOTENCY_RECORDS.EXPIRES_AT, expiresAt)
                .onConflict(IDEMPOTENCY_RECORDS.USER_ID,
                        IDEMPOTENCY_RECORDS.IDEMPOTENCY_KEY,
                        IDEMPOTENCY_RECORDS.REQUEST_PATH)
                .doNothing()
                .returning()
                .fetchOptional();
    }

    /**
     * 보존 기간이 지난 기록을 지운다. 배치가 주기적으로 호출한다. ({@code idx_idempotency_expires})
     *
     * @return 지운 건수
     */
    public int deleteExpired(OffsetDateTime now) {
        return dsl.deleteFrom(IDEMPOTENCY_RECORDS)
                .where(IDEMPOTENCY_RECORDS.EXPIRES_AT.le(now))
                .execute();
    }
}
