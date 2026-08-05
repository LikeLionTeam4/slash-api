package com.likelion.slash;

import static com.likelion.slash.jooq.Tables.AUDIT_EVENTS;
import static com.likelion.slash.jooq.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** V007 의 제약이 실제로 동작하는지 확인한다. */
@SpringBootTest
@Transactional
class AuditEventSchemaTest {

    @Autowired
    private DSLContext dsl;

    private long 사용자를_만든다() {
        return dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, "audit-test-" + UUID.randomUUID())
                .set(USERS.EMAIL, UUID.randomUUID() + "@example.com")
                .returning(USERS.ID)
                .fetchOne()
                .getId();
    }

    @Test
    @DisplayName("감사 기록을 남길 수 있다")
    void 감사_기록을_남긴다() {
        long userId = 사용자를_만든다();
        UUID devicePublicId = UUID.randomUUID();

        var event = dsl.insertInto(AUDIT_EVENTS)
                .set(AUDIT_EVENTS.USER_ID, userId)
                .set(AUDIT_EVENTS.ACTOR_TYPE, "USER")
                .set(AUDIT_EVENTS.ACTION, "DEVICE_REVOKED")
                .set(AUDIT_EVENTS.TARGET_TYPE, "DEVICE")
                .set(AUDIT_EVENTS.TARGET_PUBLIC_ID, devicePublicId)
                .set(AUDIT_EVENTS.DETAIL, JSONB.valueOf("{\"reason\":\"분실\"}"))
                .set(AUDIT_EVENTS.IP_HASH, "a".repeat(64))
                .returning()
                .fetchOne();

        assertThat(event).isNotNull();
        assertThat(event.getPublicId()).isNotNull();
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("사용자를 지워도 감사 기록은 남는다")
    void 사용자_삭제_후에도_기록이_남는다() {
        long userId = 사용자를_만든다();

        dsl.insertInto(AUDIT_EVENTS)
                .set(AUDIT_EVENTS.USER_ID, userId)
                .set(AUDIT_EVENTS.ACTOR_TYPE, "USER")
                .set(AUDIT_EVENTS.ACTION, "USER_DELETED")
                .execute();

        dsl.deleteFrom(USERS).where(USERS.ID.eq(userId)).execute();

        var event = dsl.selectFrom(AUDIT_EVENTS)
                .where(AUDIT_EVENTS.ACTION.eq("USER_DELETED"))
                .fetchOne();

        assertThat(event).isNotNull();
        // 사용자 참조만 끊기고 기록 자체는 유지된다.
        assertThat(event.getUserId()).isNull();
    }

    @Test
    @DisplayName("정의되지 않은 행위 주체는 저장할 수 없다")
    void 허용되지_않은_actor_type_은_거부된다() {
        long userId = 사용자를_만든다();

        assertThatThrownBy(() -> dsl.insertInto(AUDIT_EVENTS)
                .set(AUDIT_EVENTS.USER_ID, userId)
                .set(AUDIT_EVENTS.ACTOR_TYPE, "ROBOT")
                .set(AUDIT_EVENTS.ACTION, "SOMETHING")
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("대상 종류만 있고 식별자가 없으면 저장할 수 없다")
    void 대상_종류와_식별자는_함께_있어야_한다() {
        long userId = 사용자를_만든다();

        assertThatThrownBy(() -> dsl.insertInto(AUDIT_EVENTS)
                .set(AUDIT_EVENTS.USER_ID, userId)
                .set(AUDIT_EVENTS.ACTOR_TYPE, "USER")
                .set(AUDIT_EVENTS.ACTION, "DEVICE_REVOKED")
                .set(AUDIT_EVENTS.TARGET_TYPE, "DEVICE")
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("대상 없는 사건도 기록할 수 있다")
    void 대상_없는_사건도_기록된다() {
        long userId = 사용자를_만든다();

        int inserted = dsl.insertInto(AUDIT_EVENTS)
                .set(AUDIT_EVENTS.USER_ID, userId)
                .set(AUDIT_EVENTS.ACTOR_TYPE, "SYSTEM")
                .set(AUDIT_EVENTS.ACTION, "MAINTENANCE_STARTED")
                .execute();

        assertThat(inserted).isEqualTo(1);
    }
}
