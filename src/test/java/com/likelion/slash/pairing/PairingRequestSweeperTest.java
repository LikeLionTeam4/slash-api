package com.likelion.slash.pairing;

import static com.likelion.slash.jooq.Tables.DEVICE_PAIRING_REQUESTS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.PairingStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PairingRequestSweeper} 확인. (이슈 #33)
 *
 * <p>기능이 고장 나 있던 것은 아니다. 조회가 {@code expires_at} 을 직접 보므로 지난 코드로
 * 페어링이 되지는 않았다. 여기서 보는 것은 <b>표가 사실과 맞게 유지되고 무한히 자라지 않는가</b> 다.
 */
@SpringBootTest
@Transactional
class PairingRequestSweeperTest {

    @Autowired
    private PairingRequestSweeper sweeper;

    @Autowired
    private PairingRequestRepository repository;

    @Autowired
    private DSLContext dsl;

    /**
     * 지난 시각으로 만료되는 요청도 만들 수 있어야 한다.
     *
     * <p>{@code created_at} 을 함께 지정하는 이유 — {@code ck_pairing_expires_after_created} 가
     * {@code expires_at > created_at} 을 요구하는데, 그 열의 기본값은 PostgreSQL 의
     * {@code now()}(트랜잭션 시작 시각)다. 만료 시각만 과거로 넣으면 제약에 걸린다.
     */
    private long 요청(long userId, PairingStatus status, Duration 만료까지) {
        OffsetDateTime 만료 = SlashTime.now().plus(만료까지);

        return dsl.insertInto(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.USER_ID, userId)
                .set(DEVICE_PAIRING_REQUESTS.CODE_HASH, "hash-" + System.nanoTime())
                .set(DEVICE_PAIRING_REQUESTS.STATUS, status.name())
                .set(DEVICE_PAIRING_REQUESTS.CREATED_AT, 만료.minusMinutes(5))
                .set(DEVICE_PAIRING_REQUESTS.EXPIRES_AT, 만료)
                // ck_pairing_consumed — 사용 완료 상태에는 사용 시각이 있어야 한다.
                .set(DEVICE_PAIRING_REQUESTS.CONSUMED_AT,
                        status == PairingStatus.COMPLETED ? 만료.minusMinutes(1) : null)
                .returning(DEVICE_PAIRING_REQUESTS.ID)
                .fetchOne()
                .getId();
    }

    private String 상태(long id) {
        return dsl.select(DEVICE_PAIRING_REQUESTS.STATUS)
                .from(DEVICE_PAIRING_REQUESTS)
                .where(DEVICE_PAIRING_REQUESTS.ID.eq(id))
                .fetchOne(DEVICE_PAIRING_REQUESTS.STATUS);
    }

    @Test
    @DisplayName("기한이 지난 요청을 EXPIRED 로 바꾸고 아직 남은 것은 건드리지 않는다")
    void 기한이_지난_것만_마감한다() {
        // 사용자당 활성 코드는 한 건이라(부분 UNIQUE) PENDING 을 둘 만들려면 사용자를 나눈다.
        long 지난것 = 요청(사용자(dsl), PairingStatus.PENDING, Duration.ofMinutes(-1));
        long 남은것 = 요청(사용자(dsl), PairingStatus.PENDING, Duration.ofMinutes(5));

        sweeper.sweep();

        assertThat(상태(지난것)).isEqualTo(PairingStatus.EXPIRED.name());
        assertThat(상태(남은것)).isEqualTo(PairingStatus.PENDING.name());
    }

    @Test
    @DisplayName("만료된 지 오래된 행은 지운다 — 표가 무한히 자라지 않는다")
    void 오래된_행은_지운다() {
        long userId = 사용자(dsl);
        long 오래된것 = 요청(userId, PairingStatus.EXPIRED, Duration.ofHours(-48));
        long 방금것 = 요청(userId, PairingStatus.EXPIRED, Duration.ofMinutes(-1));

        sweeper.sweep();

        assertThat(상태(오래된것)).isNull();
        // 곧바로 지우지 않는다. 없어진 행을 두고 원인을 따지게 되는 일을 막을 만큼은 남긴다.
        assertThat(상태(방금것)).isEqualTo(PairingStatus.EXPIRED.name());
    }

    @Test
    @DisplayName("등록에 쓰인 요청은 오래돼도 남긴다 — 어느 기기가 어느 코드로 등록됐는지의 기록이다")
    void 완료된_요청은_남긴다() {
        long userId = 사용자(dsl);
        long 완료된것 = 요청(userId, PairingStatus.COMPLETED, Duration.ofHours(-48));

        sweeper.sweep();

        assertThat(상태(완료된것)).isEqualTo(PairingStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("두 번 돌려도 결과가 같다 — 여러 Pod 이 함께 돌아도 안전하다")
    void 여러_번_돌려도_같다() {
        long userId = 사용자(dsl);
        long 지난것 = 요청(userId, PairingStatus.PENDING, Duration.ofMinutes(-1));

        sweeper.sweep();
        sweeper.sweep();

        assertThat(상태(지난것)).isEqualTo(PairingStatus.EXPIRED.name());
    }
}
