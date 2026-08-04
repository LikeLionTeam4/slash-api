package com.likelion.slash;

import static com.likelion.slash.jooq.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flyway 마이그레이션 -> jOOQ 코드 생성 -> 실제 쿼리 실행까지의 파이프라인 점검.
 *
 * <p>이 시험이 통과하면 스키마·생성 코드·런타임이 서로 맞물려 있다는 뜻이다.
 * 로컬 Docker Postgres 가 떠 있어야 한다. (docker compose up -d)
 */
@SpringBootTest
@Transactional
class JooqPipelineTest {

    @Autowired
    private DSLContext dsl;

    @Test
    void 생성된_jOOQ_코드로_users_를_읽고_쓸_수_있다() {
        String cognitoSub = "test-sub-" + UUID.randomUUID();

        var inserted = dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, cognitoSub)
                .set(USERS.EMAIL, "tester@example.com")
                .set(USERS.DISPLAY_NAME, "시험 사용자")
                .returning(USERS.ID, USERS.PUBLIC_ID, USERS.STATUS, USERS.CREATED_AT)
                .fetchOne();

        assertThat(inserted).isNotNull();
        // DB 기본값이 적용되는지 확인한다.
        assertThat(inserted.getPublicId()).isNotNull();      // gen_random_uuid()
        assertThat(inserted.getStatus()).isEqualTo("ACTIVE"); // 기본값
        assertThat(inserted.getCreatedAt()).isNotNull();      // timestamptz now()

        var found = dsl.selectFrom(USERS)
                .where(USERS.COGNITO_SUB.eq(cognitoSub))
                .fetchOne();

        assertThat(found).isNotNull();
        assertThat(found.getEmail()).isEqualTo("tester@example.com");
    }
}
