package com.likelion.slash;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.DEVICE_CAPABILITIES;
import static com.likelion.slash.jooq.Tables.DEVICE_PAIRING_REQUESTS;
import static com.likelion.slash.jooq.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * V003 의 제약이 실제로 동작하는지 확인한다.
 *
 * <p>애플리케이션 검증을 빠뜨려도 DB 가 막아주는지 보는 것이 목적이다.
 *
 * <p>Spring Boot 의 jOOQ 자동 구성이 jOOQ 예외를 Spring 예외 계층으로 번역하므로
 * 제약 위반은 {@link DataIntegrityViolationException} 으로 올라온다.
 * 서비스 계층에서 처리할 때도 이 타입을 기준으로 한다.
 */
@SpringBootTest
@Transactional
class DeviceSchemaTest {

    @Autowired
    private DSLContext dsl;

    private long 사용자를_만든다() {
        return dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, "device-test-" + UUID.randomUUID())
                .set(USERS.EMAIL, UUID.randomUUID() + "@example.com")
                .returning(USERS.ID)
                .fetchOne()
                .getId();
    }

    private long 기기를_만든다(long userId, String name) {
        return dsl.insertInto(DEVICES)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.NAME, name)
                .set(DEVICES.PUBLIC_KEY, "key-" + UUID.randomUUID())
                .set(DEVICES.OS, "MACOS")
                .returning(DEVICES.ID)
                .fetchOne()
                .getId();
    }

    @Test
    @DisplayName("기기를 등록하면 기본값이 채워진다")
    void 기기_기본값이_적용된다() {
        long userId = 사용자를_만든다();

        var device = dsl.insertInto(DEVICES)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.NAME, "개발용 MacBook")
                .set(DEVICES.PUBLIC_KEY, "base64-key-" + UUID.randomUUID())
                .set(DEVICES.OS, "MACOS")
                .returning()
                .fetchOne();

        assertThat(device).isNotNull();
        assertThat(device.getPublicId()).isNotNull();
        // 등록 직후에는 아직 WSS 연결 전이므로 OFFLINE 이다.
        assertThat(device.getStatus()).isEqualTo("OFFLINE");
        assertThat(device.getVersion()).isZero();
    }

    @Test
    @DisplayName("같은 공개키로 다른 기기를 등록할 수 없다")
    void 공개키는_중복될_수_없다() {
        long userId = 사용자를_만든다();
        String sharedKey = "duplicated-key-" + UUID.randomUUID();

        dsl.insertInto(DEVICES)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.NAME, "첫 번째 PC")
                .set(DEVICES.PUBLIC_KEY, sharedKey)
                .set(DEVICES.OS, "WINDOWS")
                .execute();

        assertThatThrownBy(() -> dsl.insertInto(DEVICES)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.NAME, "위장 PC")
                .set(DEVICES.PUBLIC_KEY, sharedKey)
                .set(DEVICES.OS, "WINDOWS")
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("정의되지 않은 OS 는 저장할 수 없다")
    void 허용되지_않은_os_는_거부된다() {
        long userId = 사용자를_만든다();

        assertThatThrownBy(() -> dsl.insertInto(DEVICES)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.NAME, "리눅스 PC")
                .set(DEVICES.PUBLIC_KEY, "key-" + UUID.randomUUID())
                .set(DEVICES.OS, "LINUX")
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("해제 시각 없이 REVOKED 로 바꿀 수 없다")
    void 해제_시각_없이_revoked_로_바꿀_수_없다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId, "해제 대상 PC");

        assertThatThrownBy(() -> dsl.update(DEVICES)
                .set(DEVICES.STATUS, "REVOKED")
                .where(DEVICES.ID.eq(deviceId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);

        // PostgreSQL 은 제약 위반이 나면 트랜잭션 전체를 중단시킨다.
        // 이후 문장은 SAVEPOINT 없이는 실행되지 않으므로 성공 경로는 별도 시험으로 분리한다.
    }

    @Test
    @DisplayName("해제 시각과 함께면 REVOKED 로 바꿀 수 있다")
    void 해제_시각과_함께면_revoked_로_바꿀_수_있다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId, "해제 대상 PC");

        int updated = dsl.update(DEVICES)
                .set(DEVICES.STATUS, "REVOKED")
                .set(DEVICES.REVOKED_AT, OffsetDateTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .execute();

        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("한 기기의 같은 기능은 한 번만 기록된다")
    void 기능_보고는_기기_기능당_한_건이다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId, "기능 보고 PC");

        dsl.insertInto(DEVICE_CAPABILITIES)
                .set(DEVICE_CAPABILITIES.DEVICE_ID, deviceId)
                .set(DEVICE_CAPABILITIES.CAPABILITY_CODE, "FILE_SEARCH")
                .execute();

        assertThatThrownBy(() -> dsl.insertInto(DEVICE_CAPABILITIES)
                .set(DEVICE_CAPABILITIES.DEVICE_ID, deviceId)
                .set(DEVICE_CAPABILITIES.CAPABILITY_CODE, "FILE_SEARCH")
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("사용자당 활성 등록 코드는 한 건만 존재할 수 있다")
    void 활성_등록_코드는_사용자당_하나다() {
        long userId = 사용자를_만든다();

        dsl.insertInto(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.USER_ID, userId)
                .set(DEVICE_PAIRING_REQUESTS.CODE_HASH, "hash-1")
                .set(DEVICE_PAIRING_REQUESTS.EXPIRES_AT, OffsetDateTime.now().plusMinutes(5))
                .execute();

        assertThatThrownBy(() -> dsl.insertInto(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.USER_ID, userId)
                .set(DEVICE_PAIRING_REQUESTS.CODE_HASH, "hash-2")
                .set(DEVICE_PAIRING_REQUESTS.EXPIRES_AT, OffsetDateTime.now().plusMinutes(5))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("만료 시각이 생성 시각보다 앞설 수 없다")
    void 만료_시각은_생성_시각보다_뒤여야_한다() {
        long userId = 사용자를_만든다();

        assertThatThrownBy(() -> dsl.insertInto(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.USER_ID, userId)
                .set(DEVICE_PAIRING_REQUESTS.CODE_HASH, "hash-past")
                .set(DEVICE_PAIRING_REQUESTS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("기존 활성 코드를 무효화하면 새 코드를 발급할 수 있다")
    void 기존_코드를_무효화하면_재발급이_가능하다() {
        long userId = 사용자를_만든다();

        dsl.insertInto(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.USER_ID, userId)
                .set(DEVICE_PAIRING_REQUESTS.CODE_HASH, "old-code")
                .set(DEVICE_PAIRING_REQUESTS.EXPIRES_AT, OffsetDateTime.now().plusMinutes(5))
                .execute();

        // 발급 서비스가 반드시 수행해야 하는 선행 단계.
        // 이 UPDATE 를 빠뜨리면 만료된 코드가 남아 새 발급이 영구히 막힌다.
        dsl.update(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.STATUS, "EXPIRED")
                .where(DEVICE_PAIRING_REQUESTS.USER_ID.eq(userId))
                .and(DEVICE_PAIRING_REQUESTS.STATUS.eq("PENDING"))
                .execute();

        int inserted = dsl.insertInto(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.USER_ID, userId)
                .set(DEVICE_PAIRING_REQUESTS.CODE_HASH, "new-code")
                .set(DEVICE_PAIRING_REQUESTS.EXPIRES_AT, OffsetDateTime.now().plusMinutes(5))
                .execute();

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("등록에 사용된 기기를 삭제해도 페어링 이력이 남는다")
    void 기기를_삭제해도_페어링_이력이_유지된다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId, "해제할 PC");

        dsl.insertInto(DEVICE_PAIRING_REQUESTS)
                .set(DEVICE_PAIRING_REQUESTS.USER_ID, userId)
                .set(DEVICE_PAIRING_REQUESTS.CODE_HASH, "used-code")
                .set(DEVICE_PAIRING_REQUESTS.STATUS, "COMPLETED")
                .set(DEVICE_PAIRING_REQUESTS.EXPIRES_AT, OffsetDateTime.now().plusMinutes(5))
                .set(DEVICE_PAIRING_REQUESTS.CONSUMED_AT, OffsetDateTime.now())
                .set(DEVICE_PAIRING_REQUESTS.CONSUMED_DEVICE_ID, deviceId)
                .execute();

        // 분실·미사용 PC 접근 차단을 위해 기기 삭제가 반드시 가능해야 한다. (문서 DV-05)
        int deleted = dsl.deleteFrom(DEVICES)
                .where(DEVICES.ID.eq(deviceId))
                .execute();

        assertThat(deleted).isEqualTo(1);

        // 이력은 남되 기기 참조만 끊긴다.
        var pairing = dsl.selectFrom(DEVICE_PAIRING_REQUESTS)
                .where(DEVICE_PAIRING_REQUESTS.CODE_HASH.eq("used-code"))
                .fetchOne();

        assertThat(pairing).isNotNull();
        assertThat(pairing.getStatus()).isEqualTo("COMPLETED");
        assertThat(pairing.getConsumedAt()).isNotNull();
        assertThat(pairing.getConsumedDeviceId()).isNull();
    }
}
