package com.likelion.slash.audit;

import static com.likelion.slash.jooq.Tables.USERS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.likelion.slash.common.enums.AuditActorType;
import com.likelion.slash.common.enums.AuditTargetType;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AuditEventRepository} 확인.
 *
 * <p>감사 기록은 사용자가 사라져도 남아야 한다는 점이 다른 표와 다르다.
 */
@SpringBootTest
@Transactional
class AuditEventRepositoryTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Test
    @DisplayName("기기 등록 사건을 남긴다")
    void 사건을_남긴다() {
        long userId = 사용자(dsl);
        UUID devicePublicId = UUID.randomUUID();

        var event = auditEventRepository.record(userId, AuditActorType.USER, "DEVICE_REGISTERED",
                AuditTargetType.DEVICE, devicePublicId,
                JSONB.valueOf("{\"os\":\"MACOS\"}"), "ip-hash");

        assertThat(event.getPublicId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
        assertThat(event.getAction()).isEqualTo("DEVICE_REGISTERED");
    }

    @Test
    @DisplayName("사용자 맥락이 없는 배치 사건도 남길 수 있다")
    void 배치_사건도_남긴다() {
        var event = auditEventRepository.record(null, AuditActorType.SYSTEM, "PAIRING_CODE_EXPIRED",
                null, null, null, null);

        assertThat(event.getUserId()).isNull();
        assertThat(event.getTargetType()).isNull();
    }

    @Test
    @DisplayName("대상 종류만 있고 식별자가 없으면 DB 에 닿기 전에 막는다")
    void 대상은_짝을_이뤄야_한다() {
        long userId = 사용자(dsl);

        assertThatThrownBy(() -> auditEventRepository.record(userId, AuditActorType.USER,
                "DEVICE_REVOKED", AuditTargetType.DEVICE, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("사용자를 지워도 기록은 남는다")
    void 사용자를_지워도_기록은_남는다() {
        long userId = 사용자(dsl);
        UUID devicePublicId = UUID.randomUUID();
        auditEventRepository.record(userId, AuditActorType.USER, "DEVICE_REVOKED",
                AuditTargetType.DEVICE, devicePublicId, null, null);

        dsl.deleteFrom(USERS).where(USERS.ID.eq(userId)).execute();

        assertThat(auditEventRepository.findRecentByTarget(AuditTargetType.DEVICE, devicePublicId, 20))
                .singleElement()
                .extracting(record -> record.getUserId())
                .isNull();
    }

    @Test
    @DisplayName("한 자원의 이력을 최신순으로 추적한다")
    void 자원별_추적() {
        long userId = 사용자(dsl);
        UUID devicePublicId = UUID.randomUUID();
        auditEventRepository.record(userId, AuditActorType.USER, "DEVICE_REGISTERED",
                AuditTargetType.DEVICE, devicePublicId, null, null);
        auditEventRepository.record(userId, AuditActorType.USER, "DEVICE_REVOKED",
                AuditTargetType.DEVICE, devicePublicId, null, null);

        assertThat(auditEventRepository.findRecentByTarget(AuditTargetType.DEVICE, devicePublicId, 20))
                .extracting(record -> record.getAction())
                .containsExactly("DEVICE_REVOKED", "DEVICE_REGISTERED");
    }

    @Test
    @DisplayName("사용자별 이력에 남의 기록이 섞이지 않는다")
    void 사용자별_조회() {
        long 나 = 사용자(dsl);
        long 남 = 사용자(dsl);
        auditEventRepository.record(남, AuditActorType.USER, "DEVICE_REGISTERED", null, null, null, null);
        auditEventRepository.record(나, AuditActorType.USER, "DEVICE_REVOKED", null, null, null, null);

        assertThat(auditEventRepository.findRecentByUserId(나, 20))
                .extracting(record -> record.getAction())
                .containsExactly("DEVICE_REVOKED");
    }
}
