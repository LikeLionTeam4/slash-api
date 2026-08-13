package com.likelion.slash.device;

import static com.likelion.slash.jooq.Tables.DEVICES;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.DeviceArchitecture;
import com.likelion.slash.common.enums.DeviceOs;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.jooq.tables.records.DevicesRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * {@code devices} 접근.
 *
 * <p><b>소유권</b> — 조회·수정은 모두 {@code userId} 를 조건에 포함한다.
 * 다른 사용자의 기기를 식별자로 찍어도 결과가 비어 있으므로 서비스는 404 로 응답한다. (문서 DV-04 · 3.2.3)
 *
 * <p><b>version</b> — 조회 응답의 ETag 로 노출하는 낙관적 잠금 값이다. (문서 3.4.4)
 * 사용자가 일으킨 변경(이름 수정·연결 해제)만 이 값을 올린다.
 * Heartbeat 로 인한 {@code status}·{@code last_seen_at} 갱신은 30초마다 일어나므로
 * 여기서도 version 을 올리면 사용자가 들고 있는 ETag 가 계속 낡아져 이름 수정이 헛되이 412 로 막힌다.
 *
 * <p>{@code updated_at} 은 {@code trg_devices_set_updated_at} 이 갱신하므로 직접 쓰지 않는다.
 *
 * <p>관련 문서: 3.4.4 · WBS W1-03
 */
@Repository
public class DeviceRepository {

    /** 연결이 살아 있다고 보는 상태. Heartbeat 만료 판정 대상이다. */
    private static final List<String> CONNECTED_STATUSES = List.of(
            DeviceStatus.ONLINE.name(), DeviceStatus.READY.name(), DeviceStatus.BUSY.name());

    private final DSLContext dsl;

    public DeviceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    /** 내 PC 목록. 해제한 기기도 이력으로 함께 반환하므로 화면에서 걸러 쓴다. */
    public List<DevicesRecord> findAllByUserId(long userId) {
        return dsl.selectFrom(DEVICES)
                .where(DEVICES.USER_ID.eq(userId))
                .orderBy(DEVICES.CREATED_AT.desc(), DEVICES.ID.desc())
                .fetch();
    }

    /**
     * 화면에 보여줄 내 PC 목록. 해제한 기기는 뺀다.
     *
     * <p>해제한 기기는 사용자가 이미 지운 것이라 목록에 남을 이유가 없다. 게다가 등록 화면이
     * 목록 길이로 등록 한도를 판단하므로, 섞여 있으면 더 등록할 수 있는데도 막히게 된다.
     *
     * <p>행 자체는 지우지 않고 {@code REVOKED} 로 남겨 둔다. 작업 이력이 기기를 참조하기
     * 때문이다. 이력 조회가 필요하면 {@link #findAllByUserId} 를 쓴다.
     */
    public List<DevicesRecord> findActiveByUserId(long userId) {
        return dsl.selectFrom(DEVICES)
                .where(DEVICES.USER_ID.eq(userId))
                .and(DEVICES.STATUS.ne(DeviceStatus.REVOKED.name()))
                .orderBy(DEVICES.CREATED_AT.desc(), DEVICES.ID.desc())
                .fetch();
    }

    /** 소유권을 강제한 단건 조회. 남의 기기면 비어 있다. */
    public Optional<DevicesRecord> findByPublicIdAndUserId(UUID publicId, long userId) {
        return dsl.selectFrom(DEVICES)
                .where(DEVICES.PUBLIC_ID.eq(publicId))
                .and(DEVICES.USER_ID.eq(userId))
                .fetchOptional();
    }

    /**
     * 사용자 맥락 없이 공개 식별자로 조회한다. Agent WSS 인증처럼 아직 누구인지 모르는 경로에서 쓴다.
     *
     * <p>이 메서드만으로는 소유권을 확인할 수 없다. 호출부가 서명 검증 같은 별도 수단으로
     * 요청자가 그 기기임을 증명한 뒤에 사용해야 한다. (문서 DV-04)
     */
    public Optional<DevicesRecord> findByPublicId(UUID publicId) {
        return dsl.selectFrom(DEVICES)
                .where(DEVICES.PUBLIC_ID.eq(publicId))
                .fetchOptional();
    }

    /** Agent 인증처럼 사용자 맥락이 없는 경로에서 사용한다. 호출부가 소유권을 따로 확인해야 한다. */
    public Optional<DevicesRecord> findById(long id) {
        return dsl.selectFrom(DEVICES)
                .where(DEVICES.ID.eq(id))
                .fetchOptional();
    }

    /**
     * 공개키로 조회한다.
     *
     * <p>{@code uk_devices_public_key} 때문에 결과는 최대 한 건이다.
     * 이미 등록된 공개키로 다시 등록을 시도하는지 판별하는 데 쓴다.
     */
    public Optional<DevicesRecord> findByPublicKey(String publicKey) {
        return dsl.selectFrom(DEVICES)
                .where(DEVICES.PUBLIC_KEY.eq(publicKey))
                .fetchOptional();
    }

    // ------------------------------------------------------------------
    // 등록·수정
    // ------------------------------------------------------------------

    /**
     * 페어링이 끝난 PC 를 등록한다.
     *
     * <p>같은 공개키가 이미 있으면 {@code uk_devices_public_key} 위반으로 예외가 난다.
     * 위장 등록을 DB 가 막는 것이므로 호출부에서 삼키지 말고 오류로 응답한다.
     */
    public DevicesRecord insert(long userId,
                                String name,
                                String publicKey,
                                DeviceOs os,
                                DeviceArchitecture architecture,
                                String osVersion,
                                String agentVersion) {
        return dsl.insertInto(DEVICES)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.NAME, name)
                .set(DEVICES.PUBLIC_KEY, publicKey)
                .set(DEVICES.OS, os.name())
                .set(DEVICES.ARCHITECTURE, architecture.name())
                .set(DEVICES.OS_VERSION, osVersion)
                .set(DEVICES.AGENT_VERSION, agentVersion)
                .returning()
                .fetchOne();
    }

    /**
     * 이미 등록된 적 있는 PC 를 같은 주인이 다시 등록한다. (재등록)
     *
     * <p>{@code uk_devices_public_key} 때문에 같은 공개키로 행을 새로 만들 수 없다.
     * 그 제약은 <b>남이 위장 등록하는 것</b>을 막기 위한 것이므로, 주인이 같다면 기존 행을
     * 되살리는 것이 맞다. 이 경로가 없으면 연결을 해제한 PC 를 영영 다시 등록할 수 없다.
     *
     * <p>해제 상태를 풀고 보고된 정보를 갱신한다. {@code ck_devices_revoked_at} 이
     * 상태와 해제 시각을 함께 요구하므로 두 열을 같이 되돌린다.
     *
     * <p>사용자가 일으킨 변경이므로 version 을 올린다. 낡은 화면이 이 기기를 덮어쓰지 못한다.
     */
    public Optional<DevicesRecord> reclaim(long deviceId,
                                           long userId,
                                           String name,
                                           DeviceOs os,
                                           DeviceArchitecture architecture,
                                           String osVersion,
                                           String agentVersion) {
        return dsl.update(DEVICES)
                .set(DEVICES.NAME, name)
                .set(DEVICES.OS, os.name())
                .set(DEVICES.ARCHITECTURE, architecture.name())
                .set(DEVICES.OS_VERSION, osVersion)
                .set(DEVICES.AGENT_VERSION, agentVersion)
                .set(DEVICES.STATUS, DeviceStatus.OFFLINE.name())
                .set(DEVICES.REVOKED_AT, (OffsetDateTime) null)
                // 재등록 시점에는 아직 소유가 증명되지 않았다. 이전 Token 을 지워 둔다.
                .set(DEVICES.DEVICE_TOKEN_HASH, (String) null)
                .set(DEVICES.DEVICE_TOKEN_EXPIRES_AT, (OffsetDateTime) null)
                .set(DEVICES.VERSION, DEVICES.VERSION.plus(1))
                .where(DEVICES.ID.eq(deviceId))
                .and(DEVICES.USER_ID.eq(userId))
                .returning()
                .fetchOptional();
    }

    /**
     * 기기 이름을 바꾼다. {@code If-Match} 로 받은 version 이 현재 값과 같을 때만 반영된다.
     *
     * @return 갱신된 기기. 비어 있으면 대상이 없거나 version 이 어긋난 것이다.
     *         두 경우의 구분은 {@link #findByPublicIdAndUserId} 결과로 판단해
     *         각각 404 · 412 로 응답한다.
     */
    public Optional<DevicesRecord> rename(UUID publicId, long userId, String name, int expectedVersion) {
        return dsl.update(DEVICES)
                .set(DEVICES.NAME, name)
                .set(DEVICES.VERSION, DEVICES.VERSION.plus(1))
                .where(DEVICES.PUBLIC_ID.eq(publicId))
                .and(DEVICES.USER_ID.eq(userId))
                .and(DEVICES.VERSION.eq(expectedVersion))
                .and(DEVICES.STATUS.ne(DeviceStatus.REVOKED.name()))
                .returning()
                .fetchOptional();
    }

    /**
     * 연결을 해제한다. 행을 지우지 않고 {@code REVOKED} 로 남겨 작업 이력의 FK 를 보존한다.
     *
     * <p>{@code ck_devices_revoked_at} 때문에 해제 시각을 반드시 함께 채운다.
     * 이미 해제된 기기에는 다시 적용하지 않는다.
     */
    public Optional<DevicesRecord> revoke(UUID publicId, long userId, int expectedVersion) {
        return dsl.update(DEVICES)
                .set(DEVICES.STATUS, DeviceStatus.REVOKED.name())
                .set(DEVICES.REVOKED_AT, SlashTime.now())
                .set(DEVICES.VERSION, DEVICES.VERSION.plus(1))
                .where(DEVICES.PUBLIC_ID.eq(publicId))
                .and(DEVICES.USER_ID.eq(userId))
                .and(DEVICES.VERSION.eq(expectedVersion))
                .and(DEVICES.STATUS.ne(DeviceStatus.REVOKED.name()))
                .returning()
                .fetchOptional();
    }

    // ------------------------------------------------------------------
    // 기기 Token (W1-02)
    // ------------------------------------------------------------------

    /**
     * 기기 Token 을 발급하거나 새것으로 바꾼다. 원문이 아니라 해시를 저장한다.
     *
     * <p>재발급하면 이전 Token 은 즉시 못 쓰게 된다. 열이 하나뿐이라 자연히 그렇게 된다.
     * 훔친 Token 이 오래 살아 있지 않게 하는 편이 좋으므로 의도한 동작이다.
     *
     * <p>해제된 기기에는 발급하지 않는다. 발급하면 해제가 무력화된다.
     */
    public boolean issueToken(long deviceId, String tokenHash, OffsetDateTime expiresAt) {
        return dsl.update(DEVICES)
                .set(DEVICES.DEVICE_TOKEN_HASH, tokenHash)
                .set(DEVICES.DEVICE_TOKEN_EXPIRES_AT, expiresAt)
                .where(DEVICES.ID.eq(deviceId))
                .and(DEVICES.STATUS.ne(DeviceStatus.REVOKED.name()))
                .execute() == 1;
    }

    /**
     * Token 해시로 기기를 찾는다. WSS 접속마다 부른다. ({@code uk_devices_token_hash})
     *
     * <p>만료된 Token 과 해제된 기기는 조회되지 않는다. 호출부가 상태를 다시 보지 않아도
     * 되도록 조건을 여기에 모은다. 빠뜨리기 쉬운 확인이라 한곳에 둔다.
     */
    public Optional<DevicesRecord> findByActiveTokenHash(String tokenHash, OffsetDateTime now) {
        return dsl.selectFrom(DEVICES)
                .where(DEVICES.DEVICE_TOKEN_HASH.eq(tokenHash))
                .and(DEVICES.DEVICE_TOKEN_EXPIRES_AT.gt(now))
                .and(DEVICES.STATUS.ne(DeviceStatus.REVOKED.name()))
                .fetchOptional();
    }

    // ------------------------------------------------------------------
    // 연결 상태
    // ------------------------------------------------------------------

    /**
     * WSS 프레임 처리에 따른 상태 전이와 Heartbeat 시각 기록.
     *
     * <p>해제된 기기는 다시 살아나면 안 되므로 대상에서 제외한다.
     * version 은 올리지 않는다. (클래스 주석 참고)
     */
    public boolean updateConnectionState(long deviceId, DeviceStatus status) {
        return dsl.update(DEVICES)
                .set(DEVICES.STATUS, status.name())
                .set(DEVICES.LAST_SEEN_AT, SlashTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .and(DEVICES.STATUS.ne(DeviceStatus.REVOKED.name()))
                .execute() == 1;
    }

    /** Heartbeat 수신. 상태는 그대로 두고 마지막 확인 시각만 갱신한다. */
    public boolean touchLastSeen(long deviceId) {
        return dsl.update(DEVICES)
                .set(DEVICES.LAST_SEEN_AT, SlashTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .and(DEVICES.STATUS.ne(DeviceStatus.REVOKED.name()))
                .execute() == 1;
    }

    /**
     * Heartbeat 가 끊긴 기기를 OFFLINE 으로 내린다. (90초 미수신, 문서 DV-05)
     *
     * <p>연결이 끊겨도 Agent 가 알려줄 수 없으므로 배치가 주기적으로 호출해야 한다.
     * 호출하지 않으면 꺼진 PC 가 READY 로 남아 작업이 그리로 전달된다.
     *
     * @param threshold 이 시각보다 오래된 Heartbeat 를 만료로 본다
     * @return 내린 기기 수
     */
    public int markOfflineWhenHeartbeatStale(OffsetDateTime threshold) {
        return dsl.update(DEVICES)
                .set(DEVICES.STATUS, DeviceStatus.OFFLINE.name())
                .where(DEVICES.STATUS.in(CONNECTED_STATUSES))
                .and(DEVICES.LAST_SEEN_AT.isNull().or(DEVICES.LAST_SEEN_AT.lt(threshold)))
                .execute();
    }
}
