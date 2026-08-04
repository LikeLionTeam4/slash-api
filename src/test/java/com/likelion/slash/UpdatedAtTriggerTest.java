package com.likelion.slash;

import static com.likelion.slash.jooq.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * updated_at 자동 갱신 트리거 확인. (V002)
 *
 * <p>애플리케이션이 updated_at 을 명시하지 않아도 DB 가 갱신해야 한다.
 */
@SpringBootTest
@Transactional
class UpdatedAtTriggerTest {

    @Autowired
    private DSLContext dsl;

    @Test
    @DisplayName("UPDATE 하면 updated_at 이 자동으로 갱신된다")
    void 값이_바뀌면_updated_at_이_갱신된다() {
        var inserted = dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, "trigger-test-" + UUID.randomUUID())
                .set(USERS.EMAIL, "trigger@example.com")
                .returning(USERS.ID, USERS.UPDATED_AT)
                .fetchOne();

        assertThat(inserted).isNotNull();
        OffsetDateTime before = inserted.getUpdatedAt();

        // updated_at 을 지정하지 않고 다른 열만 수정한다.
        OffsetDateTime after = dsl.update(USERS)
                .set(USERS.DISPLAY_NAME, "수정된 이름")
                .where(USERS.ID.eq(inserted.getId()))
                .returningResult(USERS.UPDATED_AT)
                .fetchOne(USERS.UPDATED_AT);

        assertThat(after).isNotNull();
        assertThat(after).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("값이 그대로면 updated_at 을 건드리지 않는다")
    void 변경이_없으면_updated_at_이_유지된다() {
        var inserted = dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, "trigger-noop-" + UUID.randomUUID())
                .set(USERS.EMAIL, "noop@example.com")
                .set(USERS.DISPLAY_NAME, "그대로")
                .returning(USERS.ID, USERS.UPDATED_AT)
                .fetchOne();

        assertThat(inserted).isNotNull();
        OffsetDateTime before = inserted.getUpdatedAt();

        // 같은 값으로 UPDATE 한다.
        OffsetDateTime after = dsl.update(USERS)
                .set(USERS.DISPLAY_NAME, "그대로")
                .where(USERS.ID.eq(inserted.getId()))
                .returningResult(USERS.UPDATED_AT)
                .fetchOne(USERS.UPDATED_AT);

        assertThat(after).isEqualTo(before);
    }
}
