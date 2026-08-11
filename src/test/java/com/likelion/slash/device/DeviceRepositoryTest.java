package com.likelion.slash.device;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.DeviceArchitecture;
import com.likelion.slash.common.enums.DeviceOs;
import com.likelion.slash.common.enums.DeviceStatus;
import com.likelion.slash.common.enums.TaskType;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DeviceRepository} · {@link DeviceCapabilityRepository} 확인.
 *
 * <p>소유권 격리(DV-04)와 낙관적 잠금(3.4.4)이 실제로 동작하는지가 핵심이다.
 */
@SpringBootTest
@Transactional
class DeviceRepositoryTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private DeviceCapabilityRepository capabilityRepository;

    // ------------------------------------------------------------------
    // 소유권
    // ------------------------------------------------------------------

    @Test
    @DisplayName("남의 기기는 식별자를 알아도 조회되지 않는다")
    void 남의_기기는_조회되지_않는다() {
        long 주인 = 사용자(dsl);
        long 남 = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, 주인);
        UUID publicId = dsl.select(DEVICES.PUBLIC_ID).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.PUBLIC_ID);

        assertThat(deviceRepository.findByPublicIdAndUserId(publicId, 주인)).isPresent();
        assertThat(deviceRepository.findByPublicIdAndUserId(publicId, 남)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 등록·수정
    // ------------------------------------------------------------------

    @Test
    @DisplayName("등록하면 OFFLINE 과 version 0 으로 시작한다")
    void 등록_기본값() {
        long userId = 사용자(dsl);

        var device = deviceRepository.insert(userId, "개발용 MacBook", "key-" + UUID.randomUUID(),
                DeviceOs.MACOS, DeviceArchitecture.ARM64, "15.0", "0.1.0");

        assertThat(device.getStatus()).isEqualTo(DeviceStatus.OFFLINE.name());
        assertThat(device.getVersion()).isZero();
        assertThat(device.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("이름을 바꾸면 version 이 올라간다")
    void 이름_수정은_version_을_올린다() {
        long userId = 사용자(dsl);
        var device = deviceRepository.insert(userId, "이전 이름", "key-" + UUID.randomUUID(),
                DeviceOs.WINDOWS, DeviceArchitecture.X86_64, null, null);

        var renamed = deviceRepository.rename(device.getPublicId(), userId, "새 이름", device.getVersion());

        assertThat(renamed).isPresent();
        assertThat(renamed.get().getName()).isEqualTo("새 이름");
        assertThat(renamed.get().getVersion()).isEqualTo(device.getVersion() + 1);
    }

    @Test
    @DisplayName("낡은 ETag 로는 이름을 바꿀 수 없다")
    void 낡은_version_은_거부한다() {
        long userId = 사용자(dsl);
        var device = deviceRepository.insert(userId, "이전 이름", "key-" + UUID.randomUUID(),
                DeviceOs.MACOS, DeviceArchitecture.ARM64, null, null);
        // 다른 화면이 먼저 수정해 version 이 1 이 된 상황
        deviceRepository.rename(device.getPublicId(), userId, "먼저 바꾼 이름", 0);

        var stale = deviceRepository.rename(device.getPublicId(), userId, "나중 이름", 0);

        assertThat(stale).isEmpty();
        assertThat(deviceRepository.findById(device.getId()))
                .get()
                .extracting(record -> record.getName())
                .isEqualTo("먼저 바꾼 이름");
    }

    @Test
    @DisplayName("해제하면 행이 남고 해제 시각이 채워진다")
    void 해제는_행을_남긴다() {
        long userId = 사용자(dsl);
        var device = deviceRepository.insert(userId, "낡은 PC", "key-" + UUID.randomUUID(),
                DeviceOs.MACOS, DeviceArchitecture.ARM64, null, null);

        var revoked = deviceRepository.revoke(device.getPublicId(), userId, device.getVersion());

        assertThat(revoked).isPresent();
        assertThat(revoked.get().getStatus()).isEqualTo(DeviceStatus.REVOKED.name());
        assertThat(revoked.get().getRevokedAt()).isNotNull();
        assertThat(deviceRepository.findById(device.getId())).isPresent();
    }

    @Test
    @DisplayName("해제한 기기는 다시 이름을 바꾸거나 살아나지 않는다")
    void 해제한_기기는_되살아나지_않는다() {
        long userId = 사용자(dsl);
        var device = deviceRepository.insert(userId, "낡은 PC", "key-" + UUID.randomUUID(),
                DeviceOs.MACOS, DeviceArchitecture.ARM64, null, null);
        var revoked = deviceRepository.revoke(device.getPublicId(), userId, 0).orElseThrow();

        assertThat(deviceRepository.rename(device.getPublicId(), userId, "새 이름", revoked.getVersion()))
                .isEmpty();
        assertThat(deviceRepository.updateConnectionState(device.getId(), DeviceStatus.READY))
                .isFalse();
    }

    // ------------------------------------------------------------------
    // 연결 상태
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Heartbeat 로 상태를 바꿔도 ETag(version)는 그대로다")
    void 연결_상태_변경은_version_을_올리지_않는다() {
        long userId = 사용자(dsl);
        var device = deviceRepository.insert(userId, "PC", "key-" + UUID.randomUUID(),
                DeviceOs.MACOS, DeviceArchitecture.ARM64, null, null);

        assertThat(deviceRepository.updateConnectionState(device.getId(), DeviceStatus.READY)).isTrue();

        var updated = deviceRepository.findById(device.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(DeviceStatus.READY.name());
        assertThat(updated.getLastSeenAt()).isNotNull();
        assertThat(updated.getVersion()).isEqualTo(device.getVersion());
    }

    @Test
    @DisplayName("Heartbeat 가 끊긴 기기만 OFFLINE 으로 내린다")
    void 끊긴_기기를_내린다() {
        long userId = 사용자(dsl);
        long 살아있는_기기 = 준비된_기기(dsl, userId);
        long 끊긴_기기 = 준비된_기기(dsl, userId);
        deviceRepository.touchLastSeen(살아있는_기기);
        dsl.update(DEVICES)
                .set(DEVICES.LAST_SEEN_AT, SlashTime.now().minusMinutes(10))
                .where(DEVICES.ID.eq(끊긴_기기))
                .execute();

        deviceRepository.markOfflineWhenHeartbeatStale(SlashTime.now().minusSeconds(90));

        // 반환값(영향 건수)으로 판정하지 않는다. 표 전체를 쓸어담는 배치라 이 시험 밖에서
        // 커밋된 행 하나에도 흔들린다 — bootRun 으로 손 확인한 흔적이 로컬 DB 에 남아 있으면
        // 그대로 깨진다. "끊긴 것만" 은 이 시험이 만든 두 기기를 직접 보면 그대로 확인된다.
        assertThat(deviceRepository.findById(끊긴_기기).orElseThrow().getStatus())
                .isEqualTo(DeviceStatus.OFFLINE.name());
        assertThat(deviceRepository.findById(살아있는_기기).orElseThrow().getStatus())
                .isEqualTo(DeviceStatus.READY.name());
    }

    // ------------------------------------------------------------------
    // 지원 기능
    // ------------------------------------------------------------------

    @Test
    @DisplayName("READY 보고는 지원 목록을 통째로 바꾼다")
    void 지원_목록은_최신_보고로_덮어쓴다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        capabilityRepository.replaceAll(deviceId, List.of(TaskType.FILE_SEARCH, TaskType.SYSTEM_STATUS));

        capabilityRepository.replaceAll(deviceId, List.of(TaskType.FILE_SEARCH));

        assertThat(capabilityRepository.supports(deviceId, TaskType.FILE_SEARCH)).isTrue();
        assertThat(capabilityRepository.supports(deviceId, TaskType.SYSTEM_STATUS)).isFalse();
        assertThat(capabilityRepository.findAllByDeviceId(deviceId)).hasSize(1);
    }

    @Test
    @DisplayName("로컬 실행이 아닌 작업 유형은 지원 목록에 넣지 않는다")
    void 로컬_실행이_아닌_유형은_걸러진다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));

        // WEATHER_LOOKUP·TEXT_SUMMARY 는 서버·LLM 이 처리하므로 CHECK 제약이 거부한다.
        capabilityRepository.replaceAll(deviceId,
                List.of(TaskType.WEATHER_LOOKUP, TaskType.TEXT_SUMMARY, TaskType.FILE_SEARCH));

        assertThat(capabilityRepository.findAllByDeviceId(deviceId))
                .extracting(record -> record.getTaskType())
                .containsExactly(TaskType.FILE_SEARCH.name());
    }

    @Test
    @DisplayName("보고가 없으면 지원하지 않는 것으로 본다")
    void 보고가_없으면_지원하지_않는다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));

        assertThat(capabilityRepository.supports(deviceId, TaskType.FILE_SEARCH)).isFalse();
    }
}
