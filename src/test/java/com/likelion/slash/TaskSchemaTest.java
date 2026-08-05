package com.likelion.slash;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.IDEMPOTENCY_RECORDS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.TASK_EVENTS;
import static com.likelion.slash.jooq.Tables.USERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/**
 * V004 의 제약이 실제로 동작하는지 확인한다.
 *
 * <p>상태·완료 시각·결과 크기처럼 애플리케이션에서 빠뜨리기 쉬운 규칙을
 * DB 가 막아주는지 보는 것이 목적이다.
 */
@SpringBootTest
@Transactional
class TaskSchemaTest {

    @Autowired
    private DSLContext dsl;

    private long 사용자를_만든다() {
        return dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, "task-test-" + UUID.randomUUID())
                .set(USERS.EMAIL, UUID.randomUUID() + "@example.com")
                .returning(USERS.ID)
                .fetchOne()
                .getId();
    }

    private long 기기를_만든다(long userId) {
        return dsl.insertInto(DEVICES)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.NAME, "작업 대상 PC")
                .set(DEVICES.PUBLIC_KEY, "key-" + UUID.randomUUID())
                .set(DEVICES.OS, "MACOS")
                .set(DEVICES.ARCHITECTURE, "ARM64")
                .returning(DEVICES.ID)
                .fetchOne()
                .getId();
    }

    private long 작업을_만든다(long userId) {
        return dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.INPUT_TEXT, "오늘 서울 날씨 알려줘")
                .returning(TASKS.ID)
                .fetchOne()
                .getId();
    }

    // -----------------------------------------------------------------------
    // tasks
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("요청을 접수하면 CREATED 상태로 저장된다")
    void 접수_직후에는_created_상태다() {
        long userId = 사용자를_만든다();

        var task = dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.INPUT_TEXT, "내 문서에서 회의록 찾아줘")
                .returning()
                .fetchOne();

        assertThat(task).isNotNull();
        assertThat(task.getPublicId()).isNotNull();
        assertThat(task.getStatus()).isEqualTo("CREATED");
        // 분석 전이므로 작업 유형과 처리 경로는 아직 비어 있다.
        assertThat(task.getTaskType()).isNull();
        assertThat(task.getProcessingRoute()).isNull();
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("로컬 실행 작업은 대상 기기 없이 저장할 수 없다")
    void 로컬_작업은_기기가_필요하다() {
        long userId = 사용자를_만든다();

        assertThatThrownBy(() -> dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.INPUT_TEXT, "회의록 찾아줘")
                .set(TASKS.TASK_TYPE, "FILE_SEARCH")
                .set(TASKS.PROCESSING_ROUTE, "LOCAL_AGENT")
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("서버 처리 작업은 기기 없이도 저장된다")
    void 서버_작업은_기기가_필요없다() {
        long userId = 사용자를_만든다();

        int inserted = dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.INPUT_TEXT, "오늘 서울 날씨")
                .set(TASKS.TASK_TYPE, "WEATHER_LOOKUP")
                .set(TASKS.PROCESSING_ROUTE, "BACKEND_SERVICE")
                .execute();

        assertThat(inserted).isEqualTo(1);
    }

    @Test
    @DisplayName("완료 시각 없이 최종 상태로 바꿀 수 없다")
    void 최종_상태에는_완료_시각이_필요하다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        assertThatThrownBy(() -> dsl.update(TASKS)
                .set(TASKS.STATUS, "SUCCEEDED")
                .where(TASKS.ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("진행 중 상태에는 완료 시각을 넣을 수 없다")
    void 진행_중에는_완료_시각이_없어야_한다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        assertThatThrownBy(() -> dsl.update(TASKS)
                .set(TASKS.STATUS, "RUNNING")
                .set(TASKS.COMPLETED_AT, OffsetDateTime.now())
                .where(TASKS.ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("성공하면 결과와 완료 시각이 함께 저장된다")
    void 성공_결과를_저장한다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        int updated = dsl.update(TASKS)
                .set(TASKS.STATUS, "SUCCEEDED")
                .set(TASKS.RESULT, JSONB.valueOf("{\"location\":\"서울\",\"temperatureCelsius\":29.0}"))
                .set(TASKS.COMPLETED_AT, OffsetDateTime.now())
                .where(TASKS.ID.eq(taskId))
                .execute();

        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("오류 코드는 실패·만료 상태에서만 저장된다")
    void 오류_코드는_실패_상태에서만_저장된다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        assertThatThrownBy(() -> dsl.update(TASKS)
                .set(TASKS.STATUS, "RUNNING")
                .set(TASKS.ERROR_CODE, "NLU_UNAVAILABLE")
                .where(TASKS.ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("결과와 오류 코드가 동시에 존재할 수 없다")
    void 결과와_오류는_공존할_수_없다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        assertThatThrownBy(() -> dsl.update(TASKS)
                .set(TASKS.STATUS, "FAILED")
                .set(TASKS.RESULT, JSONB.valueOf("{\"summary\":\"ok\"}"))
                .set(TASKS.ERROR_CODE, "INTERNAL_ERROR")
                .set(TASKS.COMPLETED_AT, OffsetDateTime.now())
                .where(TASKS.ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("64KB 를 넘는 결과는 저장할 수 없다")
    void 결과_크기_상한을_넘으면_거부된다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        String oversized = "{\"items\":\"" + "가".repeat(70_000) + "\"}";

        assertThatThrownBy(() -> dsl.update(TASKS)
                .set(TASKS.STATUS, "SUCCEEDED")
                .set(TASKS.RESULT, JSONB.valueOf(oversized))
                .set(TASKS.COMPLETED_AT, OffsetDateTime.now())
                .where(TASKS.ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("작업 이력이 있는 기기는 하드 삭제할 수 없다")
    void 작업이_있는_기기는_삭제되지_않는다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);

        dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.DEVICE_ID, deviceId)
                .set(TASKS.INPUT_TEXT, "회의록 찾아줘")
                .set(TASKS.TASK_TYPE, "FILE_SEARCH")
                .set(TASKS.PROCESSING_ROUTE, "LOCAL_AGENT")
                .execute();

        // 등록 해제는 행 삭제가 아니라 devices.status = 'REVOKED' 로 처리한다.
        // 작업 이력을 보존하기 위해 하드 삭제는 FK RESTRICT 로 막는다.
        assertThatThrownBy(() -> dsl.deleteFrom(DEVICES)
                .where(DEVICES.ID.eq(deviceId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("작업 이력이 있어도 등록 해제(REVOKED)는 가능하다")
    void 작업이_있어도_등록_해제는_가능하다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);

        dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.DEVICE_ID, deviceId)
                .set(TASKS.INPUT_TEXT, "회의록 찾아줘")
                .set(TASKS.TASK_TYPE, "FILE_SEARCH")
                .set(TASKS.PROCESSING_ROUTE, "LOCAL_AGENT")
                .execute();

        int updated = dsl.update(DEVICES)
                .set(DEVICES.STATUS, "REVOKED")
                .set(DEVICES.REVOKED_AT, OffsetDateTime.now())
                .where(DEVICES.ID.eq(deviceId))
                .execute();

        assertThat(updated).isEqualTo(1);
    }

    @Test
    @DisplayName("정의되지 않은 상태값은 저장할 수 없다")
    void 허용되지_않은_상태는_거부된다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        assertThatThrownBy(() -> dsl.update(TASKS)
                .set(TASKS.STATUS, "PENDING")
                .where(TASKS.ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // -----------------------------------------------------------------------
    // task_events
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("같은 작업에 같은 순번을 두 번 기록할 수 없다")
    void 이벤트_순번은_중복될_수_없다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        dsl.insertInto(TASK_EVENTS)
                .set(TASK_EVENTS.TASK_ID, taskId)
                .set(TASK_EVENTS.SEQUENCE, 1)
                .set(TASK_EVENTS.TO_STATUS, "CREATED")
                .execute();

        assertThatThrownBy(() -> dsl.insertInto(TASK_EVENTS)
                .set(TASK_EVENTS.TASK_ID, taskId)
                .set(TASK_EVENTS.SEQUENCE, 1)
                .set(TASK_EVENTS.TO_STATUS, "ANALYZING")
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 상태로의 전이는 기록할 수 없다")
    void 동일_상태_전이는_거부된다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        assertThatThrownBy(() -> dsl.insertInto(TASK_EVENTS)
                .set(TASK_EVENTS.TASK_ID, taskId)
                .set(TASK_EVENTS.SEQUENCE, 1)
                .set(TASK_EVENTS.FROM_STATUS, "RUNNING")
                .set(TASK_EVENTS.TO_STATUS, "RUNNING")
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("순번은 1 이상이어야 한다")
    void 순번은_1_이상이다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        assertThatThrownBy(() -> dsl.insertInto(TASK_EVENTS)
                .set(TASK_EVENTS.TASK_ID, taskId)
                .set(TASK_EVENTS.SEQUENCE, 0)
                .set(TASK_EVENTS.TO_STATUS, "CREATED")
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("작업을 지우면 이벤트도 함께 지워진다")
    void 작업_삭제_시_이벤트도_삭제된다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);

        dsl.insertInto(TASK_EVENTS)
                .set(TASK_EVENTS.TASK_ID, taskId)
                .set(TASK_EVENTS.SEQUENCE, 1)
                .set(TASK_EVENTS.TO_STATUS, "CREATED")
                .execute();

        dsl.deleteFrom(TASKS).where(TASKS.ID.eq(taskId)).execute();

        int remaining = dsl.fetchCount(TASK_EVENTS, TASK_EVENTS.TASK_ID.eq(taskId));
        assertThat(remaining).isZero();
    }

    // -----------------------------------------------------------------------
    // idempotency_records
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("같은 사용자·키·경로 조합은 한 번만 저장된다")
    void 멱등키는_사용자_경로별로_유일하다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);
        String key = UUID.randomUUID().toString();

        dsl.insertInto(IDEMPOTENCY_RECORDS)
                .set(IDEMPOTENCY_RECORDS.USER_ID, userId)
                .set(IDEMPOTENCY_RECORDS.IDEMPOTENCY_KEY, key)
                .set(IDEMPOTENCY_RECORDS.REQUEST_PATH, "/api/v1/requests")
                .set(IDEMPOTENCY_RECORDS.REQUEST_HASH, "hash-1")
                .set(IDEMPOTENCY_RECORDS.TASK_ID, taskId)
                .set(IDEMPOTENCY_RECORDS.EXPIRES_AT, OffsetDateTime.now().plusHours(24))
                .execute();

        assertThatThrownBy(() -> dsl.insertInto(IDEMPOTENCY_RECORDS)
                .set(IDEMPOTENCY_RECORDS.USER_ID, userId)
                .set(IDEMPOTENCY_RECORDS.IDEMPOTENCY_KEY, key)
                .set(IDEMPOTENCY_RECORDS.REQUEST_PATH, "/api/v1/requests")
                .set(IDEMPOTENCY_RECORDS.REQUEST_HASH, "hash-2")
                .set(IDEMPOTENCY_RECORDS.TASK_ID, taskId)
                .set(IDEMPOTENCY_RECORDS.EXPIRES_AT, OffsetDateTime.now().plusHours(24))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("경로가 다르면 같은 키를 쓸 수 있다")
    void 경로가_다르면_같은_키를_쓸_수_있다() {
        long userId = 사용자를_만든다();
        long taskId = 작업을_만든다(userId);
        String key = UUID.randomUUID().toString();

        dsl.insertInto(IDEMPOTENCY_RECORDS)
                .set(IDEMPOTENCY_RECORDS.USER_ID, userId)
                .set(IDEMPOTENCY_RECORDS.IDEMPOTENCY_KEY, key)
                .set(IDEMPOTENCY_RECORDS.REQUEST_PATH, "/api/v1/requests")
                .set(IDEMPOTENCY_RECORDS.REQUEST_HASH, "hash-1")
                .set(IDEMPOTENCY_RECORDS.TASK_ID, taskId)
                .set(IDEMPOTENCY_RECORDS.EXPIRES_AT, OffsetDateTime.now().plusHours(24))
                .execute();

        int inserted = dsl.insertInto(IDEMPOTENCY_RECORDS)
                .set(IDEMPOTENCY_RECORDS.USER_ID, userId)
                .set(IDEMPOTENCY_RECORDS.IDEMPOTENCY_KEY, key)
                .set(IDEMPOTENCY_RECORDS.REQUEST_PATH, "/api/v1/other")
                .set(IDEMPOTENCY_RECORDS.REQUEST_HASH, "hash-1")
                .set(IDEMPOTENCY_RECORDS.TASK_ID, taskId)
                .set(IDEMPOTENCY_RECORDS.EXPIRES_AT, OffsetDateTime.now().plusHours(24))
                .execute();

        assertThat(inserted).isEqualTo(1);
    }
}
