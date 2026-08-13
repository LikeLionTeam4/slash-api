package com.likelion.slash.device;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.DeviceStatus;
import java.time.Duration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DeviceOfflineSweeper} 확인. (WBS W1-03)
 *
 * <p><b>이 스윕이 없으면 꺼진 PC 가 영영 {@code READY} 로 남는다.</b> 연결이 끊긴 것을
 * 알려주는 주체가 없기 때문이다 — 사용자가 PC 를 그냥 끄거나 Pod 이 죽으면 상태를 되돌릴
 * 사람이 아무도 없다. 그 상태로 작업이 전달되면 응답이 오지 않고, 기기 목록도 거짓을 보여준다.
 *
 * <p>스케줄이 붙어 있는지가 아니라 <b>판정이 맞는지</b>를 본다. 실행 주기는 설정값이라
 * 여기서 확인할 것이 없다.
 */
@SpringBootTest
@Transactional
class DeviceOfflineSweeperTest {

    @Autowired
    private DeviceOfflineSweeper sweeper;

    @Autowired
    private DSLContext dsl;

    @Test
    @DisplayName("Heartbeat 가 끊긴 지 오래된 기기를 OFFLINE 으로 내린다")
    void 끊긴_기기를_내린다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        마지막_확인을(deviceId, Duration.ofMinutes(5));

        sweeper.markStaleDevicesOffline();

        assertThat(상태(deviceId)).isEqualTo(DeviceStatus.OFFLINE.name());
    }

    @Test
    @DisplayName("방금 Heartbeat 를 보낸 기기는 건드리지 않는다")
    void 살아있는_기기는_그대로_둔다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        마지막_확인을(deviceId, Duration.ofSeconds(10));

        sweeper.markStaleDevicesOffline();

        assertThat(상태(deviceId)).isEqualTo(DeviceStatus.READY.name());
    }

    @Test
    @DisplayName("한 번도 연결된 적 없는 기기도 내린다")
    void 연결된_적_없으면_내린다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        dsl.update(DEVICES)
                .setNull(DEVICES.LAST_SEEN_AT)
                .where(DEVICES.ID.eq(deviceId))
                .execute();

        // 등록 직후 READY 로 만들어 두고 Agent 가 붙지 않은 경우다. 켜져 있다고 볼 근거가 없다.
        sweeper.markStaleDevicesOffline();

        assertThat(상태(deviceId)).isEqualTo(DeviceStatus.OFFLINE.name());
    }

    @Test
    @DisplayName("해제한 기기를 되살리지 않는다")
    void 해제한_기기는_건드리지_않는다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        마지막_확인을(deviceId, Duration.ofMinutes(5));
        dsl.update(DEVICES)
                .set(DEVICES.STATUS, DeviceStatus.REVOKED.name())
                .set(DEVICES.REVOKED_AT, SlashTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .execute();

        sweeper.markStaleDevicesOffline();

        // OFFLINE 으로 바꾸면 ck_devices_revoked_at 을 어겨 스윕 전체가 실패한다.
        assertThat(상태(deviceId)).isEqualTo(DeviceStatus.REVOKED.name());
    }

    @Test
    @DisplayName("ETag(version)는 올리지 않는다 — 사용자가 일으킨 변경이 아니다")
    void 버전을_올리지_않는다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        마지막_확인을(deviceId, Duration.ofMinutes(5));
        int 이전 = 버전(deviceId);

        sweeper.markStaleDevicesOffline();

        // 배치가 version 을 올리면 사용자가 들고 있는 ETag 가 저절로 낡아 이름 수정이 412 로 막힌다.
        assertThat(버전(deviceId)).isEqualTo(이전);
    }

    private void 마지막_확인을(long deviceId, Duration 전) {
        dsl.update(DEVICES)
                .set(DEVICES.LAST_SEEN_AT, SlashTime.now().minus(전))
                .where(DEVICES.ID.eq(deviceId))
                .execute();
    }

    private String 상태(long deviceId) {
        return dsl.select(DEVICES.STATUS).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.STATUS);
    }

    private int 버전(long deviceId) {
        return dsl.select(DEVICES.VERSION).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.VERSION);
    }
}
