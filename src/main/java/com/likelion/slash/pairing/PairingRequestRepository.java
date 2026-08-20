package com.likelion.slash.pairing;

import static com.likelion.slash.jooq.Tables.DEVICE_PAIRING_REQUESTS;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.PairingStatus;
import com.likelion.slash.jooq.tables.records.DevicePairingRequestsRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * {@code device_pairing_requests} 접근.
 *
 * <p>5분·1회용 PC 등록 코드. 코드 원문은 어떤 경우에도 저장하지 않고 해시만 다룬다. (문서 DV-01)
 *
 * <p><b>만료는 저절로 일어나지 않는다.</b> {@code uk_pairing_active_per_user} 가
 * 사용자별 {@code PENDING} 을 한 건으로 제한하므로, 시간이 지난 코드가 그대로 남아 있으면
 * 새 코드를 아예 발급할 수 없다. 그래서
 * <ul>
 *   <li>발급은 {@link #issue} 하나로 처리한다 — 같은 트랜잭션에서 기존 활성 코드를 먼저 무효화한다</li>
 *   <li>{@link #expireOverdue} 를 배치가 주기적으로 호출한다</li>
 * </ul>
 *
 * <p>관련 문서: 3.4.3 · WBS W1-02
 */
@Repository
public class PairingRequestRepository {

    private final DSLContext dsl;

    public PairingRequestRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * 새 등록 코드를 발급한다.
     *
     * <p>기존 활성 코드를 먼저 {@code EXPIRED} 로 바꾸므로 사용자는 항상 마지막 코드만 쓸 수 있다.
     * 두 단계가 한 트랜잭션 안에 있어야 부분 UNIQUE 제약에 걸리지 않는다.
     *
     * @param codeHash 코드 원문의 해시. 원문을 넘기지 않는다.
     */
    public DevicePairingRequestsRecord issue(long userId, String codeHash, OffsetDateTime expiresAt) {
        return dsl.transactionResult(configuration -> {
            DSLContext tx = DSL.using(configuration);

            tx.update(DEVICE_PAIRING_REQUESTS)
                    .set(DEVICE_PAIRING_REQUESTS.STATUS, PairingStatus.EXPIRED.name())
                    .where(DEVICE_PAIRING_REQUESTS.USER_ID.eq(userId))
                    .and(DEVICE_PAIRING_REQUESTS.STATUS.eq(PairingStatus.PENDING.name()))
                    .execute();

            return tx.insertInto(DEVICE_PAIRING_REQUESTS)
                    .set(DEVICE_PAIRING_REQUESTS.USER_ID, userId)
                    .set(DEVICE_PAIRING_REQUESTS.CODE_HASH, codeHash)
                    .set(DEVICE_PAIRING_REQUESTS.EXPIRES_AT, expiresAt)
                    .returning()
                    .fetchOne();
        });
    }

    /**
     * Agent 가 제출한 코드에 대응하는 사용 가능한 발급 건을 찾는다.
     *
     * <p>같은 코드로 두 Agent 가 동시에 등록을 시도해도 한 쪽만 성공하도록 행을 잠근다.
     * 잠금은 호출한 트랜잭션이 끝날 때까지 유지되므로 {@link #complete} 까지 한 트랜잭션에서 처리한다.
     *
     * <p>만료 시각이 지난 건은 {@code PENDING} 으로 남아 있어도 사용할 수 없다.
     */
    public Optional<DevicePairingRequestsRecord> findUsableByCodeHash(String codeHash) {
        return dsl.selectFrom(DEVICE_PAIRING_REQUESTS)
                .where(DEVICE_PAIRING_REQUESTS.CODE_HASH.eq(codeHash))
                .and(DEVICE_PAIRING_REQUESTS.STATUS.eq(PairingStatus.PENDING.name()))
                .and(DEVICE_PAIRING_REQUESTS.EXPIRES_AT.gt(SlashTime.now()))
                .forUpdate()
                .fetchOptional();
    }

    /** 사용자가 보는 등록 진행 상태. 소유권을 강제한다. */
    public Optional<DevicePairingRequestsRecord> findByPublicIdAndUserId(UUID publicId, long userId) {
        return dsl.selectFrom(DEVICE_PAIRING_REQUESTS)
                .where(DEVICE_PAIRING_REQUESTS.PUBLIC_ID.eq(publicId))
                .and(DEVICE_PAIRING_REQUESTS.USER_ID.eq(userId))
                .fetchOptional();
    }

    /**
     * 등록 완료 처리.
     *
     * <p>{@code PENDING} 인 동안만 반영되므로, 같은 코드를 두 번 써도 두 번째는 거짓을 돌려준다.
     * {@code ck_pairing_consumed} 가 사용 시각을 요구하므로 함께 채운다.
     *
     * @return 반영 여부. 거짓이면 이미 소비되었거나 만료된 코드다.
     */
    public boolean complete(long id, long consumedDeviceId) {
        return dsl.update(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.STATUS, PairingStatus.COMPLETED.name())
                .set(DEVICE_PAIRING_REQUESTS.CONSUMED_AT, SlashTime.now())
                .set(DEVICE_PAIRING_REQUESTS.CONSUMED_DEVICE_ID, consumedDeviceId)
                .where(DEVICE_PAIRING_REQUESTS.ID.eq(id))
                .and(DEVICE_PAIRING_REQUESTS.STATUS.eq(PairingStatus.PENDING.name()))
                .execute() == 1;
    }

    /**
     * 만료 시각이 지난 미사용 코드를 정리한다. 배치가 주기적으로 호출한다.
     *
     * @return 정리한 건수
     */
    public int expireOverdue(OffsetDateTime now) {
        return dsl.update(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.STATUS, PairingStatus.EXPIRED.name())
                .where(DEVICE_PAIRING_REQUESTS.STATUS.eq(PairingStatus.PENDING.name()))
                .and(DEVICE_PAIRING_REQUESTS.EXPIRES_AT.le(now))
                .execute();
    }

    /**
     * 만료된 지 오래된 요청 행을 지운다. (이슈 #33)
     *
     * <p><b>{@link #expireOverdue} 만으로는 표가 계속 자란다.</b> 그쪽은 상태를
     * {@code EXPIRED} 로 바꿀 뿐 행을 없애지 않는다. 등록 코드는 5분·1회용이라 시도할 때마다
     * 행이 하나씩 남는데, 다시 볼 이유가 없는 자료다.
     *
     * <p><b>{@code COMPLETED} 는 남긴다.</b> {@code consumed_device_id} 로 어느 기기가 어느
     * 코드로 등록됐는지를 가리키는 등록 이력이라, 만료된 요청과 성격이 다르다.
     *
     * <p>곧바로 지우지 않고 기한을 두는 이유는 없어진 행을 두고 원인을 따지게 되는 일을 막기
     * 위해서다. 조회는 이미 {@code expires_at} 을 직접 보므로 남아 있어도 쓰이지 않는다.
     * ({@code idempotency_records} 의 24시간 보존과 같은 판단)
     *
     * @param before 이 시각보다 전에 만료된 행을 지운다
     */
    public int deleteExpiredBefore(OffsetDateTime before) {
        return dsl.deleteFrom(DEVICE_PAIRING_REQUESTS)
                .where(DEVICE_PAIRING_REQUESTS.STATUS.eq(PairingStatus.EXPIRED.name()))
                .and(DEVICE_PAIRING_REQUESTS.EXPIRES_AT.lt(before))
                .execute();
    }
}
