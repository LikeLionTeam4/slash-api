package com.likelion.slash.support;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.USERS;

import com.likelion.slash.common.enums.DeviceStatus;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Repository 시험이 공통으로 쓰는 최소 데이터.
 *
 * <p>FK 때문에 사용자 → 기기 → 작업 순서로만 만들 수 있다.
 * 각 시험은 {@code @Transactional} 로 되돌려지므로 정리 코드를 두지 않는다.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static long 사용자(DSLContext dsl) {
        return dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, "sub-" + UUID.randomUUID())
                .set(USERS.EMAIL, UUID.randomUUID() + "@example.com")
                .returning(USERS.ID)
                .fetchOne()
                .getId();
    }

    /** 작업을 받을 수 있는 상태의 기기. */
    public static long 준비된_기기(DSLContext dsl, long userId) {
        return dsl.insertInto(DEVICES)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.NAME, "시험용 PC")
                .set(DEVICES.PUBLIC_KEY, "key-" + UUID.randomUUID())
                .set(DEVICES.OS, "MACOS")
                .set(DEVICES.ARCHITECTURE, "ARM64")
                .set(DEVICES.STATUS, DeviceStatus.READY.name())
                .returning(DEVICES.ID)
                .fetchOne()
                .getId();
    }

    /**
     * 지정한 상태의 작업. 상태 전이를 거치지 않고 바로 만든다.
     *
     * <p>{@code deviceId} 를 넘기면 로컬 실행 작업이 된다.
     */
    public static long 작업(DSLContext dsl, long userId, Long deviceId, String status) {
        return dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.DEVICE_ID, deviceId)
                .set(TASKS.INPUT_TEXT, "오늘 서울 날씨 알려줘")
                .set(TASKS.STATUS, status)
                .returning(TASKS.ID)
                .fetchOne()
                .getId();
    }
}
