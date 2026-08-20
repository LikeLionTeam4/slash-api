package com.likelion.slash.pairing;

import static com.likelion.slash.jooq.Tables.DEVICE_PAIRING_REQUESTS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.SlashTime;
import java.time.OffsetDateTime;
import com.likelion.slash.common.enums.PairingStatus;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PairingRequestRepository} 확인.
 *
 * <p>{@code uk_pairing_active_per_user} 가 사용자별 활성 코드를 한 건으로 제한하므로,
 * 코드를 다시 발급할 때 기존 코드를 먼저 무효화하지 않으면 아예 발급이 실패한다.
 * 그 처리가 {@link PairingRequestRepository#issue} 안에 들어 있는지가 핵심이다.
 */
@SpringBootTest
@Transactional
class PairingRequestRepositoryTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private PairingRequestRepository pairingRequestRepository;

    private String 코드해시() {
        return "hash-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("발급하면 PENDING 으로 시작한다")
    void 발급_기본값() {
        long userId = 사용자(dsl);

        var issued = pairingRequestRepository.issue(userId, 코드해시(), SlashTime.now().plusMinutes(5));

        assertThat(issued.getStatus()).isEqualTo(PairingStatus.PENDING.name());
        assertThat(issued.getPublicId()).isNotNull();
        assertThat(issued.getConsumedAt()).isNull();
    }

    @Test
    @DisplayName("다시 발급하면 이전 코드가 무효가 된다")
    void 재발급은_이전_코드를_무효화한다() {
        long userId = 사용자(dsl);
        String 이전해시 = 코드해시();
        pairingRequestRepository.issue(userId, 이전해시, SlashTime.now().plusMinutes(5));

        var 새코드 = pairingRequestRepository.issue(userId, 코드해시(), SlashTime.now().plusMinutes(5));

        assertThat(pairingRequestRepository.findUsableByCodeHash(이전해시)).isEmpty();
        assertThat(새코드.getStatus()).isEqualTo(PairingStatus.PENDING.name());
    }

    @Test
    @DisplayName("만료 시각이 지난 코드는 PENDING 이어도 쓸 수 없다")
    void 만료된_코드는_사용할_수_없다() {
        long userId = 사용자(dsl);
        String 해시 = 코드해시();

        // 발급한 뒤 기한을 지난 시각으로 옮긴다. 발급 시점에 곧바로 만료되는 값을 넣으면
        // ck_pairing_expires_after_created(expires_at > created_at)에 걸린다 —
        // created_at 의 기본값은 PostgreSQL 의 now(), 즉 트랜잭션 시작 시각이라
        // 자바에서 계산한 시각과 순서가 뒤집힐 수 있다. 간헐 실패의 원인이었다.
        pairingRequestRepository.issue(userId, 해시, SlashTime.now().plusMinutes(5));
        기한을_지나게_한다(해시);

        assertThat(pairingRequestRepository.findUsableByCodeHash(해시)).isEmpty();
    }

    /** 발급된 코드의 생성·만료 시각을 함께 과거로 옮긴다. 제약을 지키면서 만료 상태를 만든다. */
    private void 기한을_지나게_한다(String 코드해시) {
        OffsetDateTime 만료 = SlashTime.now().minusMinutes(10);

        dsl.update(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.CREATED_AT, 만료.minusMinutes(5))
                .set(DEVICE_PAIRING_REQUESTS.EXPIRES_AT, 만료)
                .where(DEVICE_PAIRING_REQUESTS.CODE_HASH.eq(코드해시))
                .execute();
    }

    @Test
    @DisplayName("같은 코드를 두 번 사용할 수 없다")
    void 코드는_한_번만_쓴다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        var issued = pairingRequestRepository.issue(userId, 코드해시(), SlashTime.now().plusMinutes(5));

        assertThat(pairingRequestRepository.complete(issued.getId(), deviceId)).isTrue();
        assertThat(pairingRequestRepository.complete(issued.getId(), deviceId)).isFalse();

        var 완료된_코드 = pairingRequestRepository
                .findByPublicIdAndUserId(issued.getPublicId(), userId)
                .orElseThrow();
        assertThat(완료된_코드.getStatus()).isEqualTo(PairingStatus.COMPLETED.name());
        assertThat(완료된_코드.getConsumedAt()).isNotNull();
        assertThat(완료된_코드.getConsumedDeviceId()).isEqualTo(deviceId);
    }

    @Test
    @DisplayName("남의 등록 코드는 조회되지 않는다")
    void 남의_코드는_조회되지_않는다() {
        long 주인 = 사용자(dsl);
        long 남 = 사용자(dsl);
        var issued = pairingRequestRepository.issue(주인, 코드해시(), SlashTime.now().plusMinutes(5));

        assertThat(pairingRequestRepository.findByPublicIdAndUserId(issued.getPublicId(), 남)).isEmpty();
    }

    @Test
    @DisplayName("배치가 기한이 지난 코드를 정리한다")
    void 배치가_만료를_정리한다() {
        long userId = 사용자(dsl);
        var issued = pairingRequestRepository.issue(userId, 코드해시(), SlashTime.now().plusMinutes(5));

        int 정리한_건수 = pairingRequestRepository.expireOverdue(SlashTime.now().plusMinutes(10));

        assertThat(정리한_건수).isEqualTo(1);
        assertThat(pairingRequestRepository.findByPublicIdAndUserId(issued.getPublicId(), userId))
                .get()
                .extracting(record -> record.getStatus())
                .isEqualTo(PairingStatus.EXPIRED.name());
    }
}
