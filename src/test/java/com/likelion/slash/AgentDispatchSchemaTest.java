package com.likelion.slash;

import static com.likelion.slash.jooq.Tables.AGENT_DISPATCHES;
import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.TASKS;
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
 * V005 의 제약이 실제로 동작하는지 확인한다.
 *
 * <p>특히 "Task 당 활성 전달 한 건", "기기당 동시 작업 한 건" 이 DB 에서 강제되는지 본다.
 */
@SpringBootTest
@Transactional
class AgentDispatchSchemaTest {

    @Autowired
    private DSLContext dsl;

    private long 사용자를_만든다() {
        return dsl.insertInto(USERS)
                .set(USERS.COGNITO_SUB, "dispatch-test-" + UUID.randomUUID())
                .set(USERS.EMAIL, UUID.randomUUID() + "@example.com")
                .returning(USERS.ID)
                .fetchOne()
                .getId();
    }

    private long 기기를_만든다(long userId) {
        return dsl.insertInto(DEVICES)
                .set(DEVICES.USER_ID, userId)
                .set(DEVICES.NAME, "전달 대상 PC")
                .set(DEVICES.PUBLIC_KEY, "key-" + UUID.randomUUID())
                .set(DEVICES.OS, "MACOS")
                .set(DEVICES.ARCHITECTURE, "ARM64")
                .returning(DEVICES.ID)
                .fetchOne()
                .getId();
    }

    private long 작업을_만든다(long userId, long deviceId) {
        return dsl.insertInto(TASKS)
                .set(TASKS.USER_ID, userId)
                .set(TASKS.DEVICE_ID, deviceId)
                .set(TASKS.INPUT_TEXT, "회의록 찾아줘")
                .set(TASKS.TASK_TYPE, "FILE_SEARCH")
                .set(TASKS.PROCESSING_ROUTE, "LOCAL_AGENT")
                .returning(TASKS.ID)
                .fetchOne()
                .getId();
    }

    private int 전달을_만든다(long taskId, long deviceId) {
        return dsl.insertInto(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.TASK_ID, taskId)
                .set(AGENT_DISPATCHES.DEVICE_ID, deviceId)
                .set(AGENT_DISPATCHES.EXPIRES_AT, OffsetDateTime.now().plusMinutes(2))
                .execute();
    }

    @Test
    @DisplayName("전달을 만들면 PENDING 상태로 시작한다")
    void 전달은_pending_으로_시작한다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long taskId = 작업을_만든다(userId, deviceId);

        var dispatch = dsl.insertInto(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.TASK_ID, taskId)
                .set(AGENT_DISPATCHES.DEVICE_ID, deviceId)
                .set(AGENT_DISPATCHES.EXPIRES_AT, OffsetDateTime.now().plusMinutes(2))
                .returning()
                .fetchOne();

        assertThat(dispatch).isNotNull();
        assertThat(dispatch.getPublicId()).isNotNull();
        assertThat(dispatch.getStatus()).isEqualTo("PENDING");
        assertThat(dispatch.getAttemptCount()).isZero();
        assertThat(dispatch.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("같은 작업에 활성 전달을 두 건 만들 수 없다")
    void 작업당_활성_전달은_한_건이다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long taskId = 작업을_만든다(userId, deviceId);

        전달을_만든다(taskId, deviceId);

        assertThatThrownBy(() -> 전달을_만든다(taskId, deviceId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("한 기기에 동시에 두 작업을 전달할 수 없다")
    void 기기당_동시_작업은_한_건이다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long firstTask = 작업을_만든다(userId, deviceId);
        long secondTask = 작업을_만든다(userId, deviceId);

        전달을_만든다(firstTask, deviceId);

        // 두 번째 작업은 다른 Task 지만 같은 기기라 DEVICE_BUSY 로 막혀야 한다.
        assertThatThrownBy(() -> 전달을_만든다(secondTask, deviceId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("앞선 전달이 끝나면 같은 기기에 다시 전달할 수 있다")
    void 전달이_끝나면_다음_작업을_보낼_수_있다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long firstTask = 작업을_만든다(userId, deviceId);
        long secondTask = 작업을_만든다(userId, deviceId);

        전달을_만든다(firstTask, deviceId);

        dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.STATUS, "COMPLETED")
                .set(AGENT_DISPATCHES.COMPLETED_AT, OffsetDateTime.now())
                .where(AGENT_DISPATCHES.TASK_ID.eq(firstTask))
                .execute();

        assertThat(전달을_만든다(secondTask, deviceId)).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 처리하지 않은 전달은 다음 작업을 막는다")
    void 만료_처리를_빠뜨리면_기기가_막힌다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long firstTask = 작업을_만든다(userId, deviceId);
        long secondTask = 작업을_만든다(userId, deviceId);

        // 10분 전에 만들어져 5분 전에 기한이 끝났지만, 배치가 아직 status 를 바꾸지 않은 상황.
        // expires_at 만 과거로 바꿀 수는 없다(expires_at > created_at CHECK).
        dsl.insertInto(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.TASK_ID, firstTask)
                .set(AGENT_DISPATCHES.DEVICE_ID, deviceId)
                .set(AGENT_DISPATCHES.CREATED_AT, OffsetDateTime.now().minusMinutes(10))
                .set(AGENT_DISPATCHES.EXPIRES_AT, OffsetDateTime.now().minusMinutes(5))
                .execute();

        assertThatThrownBy(() -> 전달을_만든다(secondTask, deviceId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 만료 배치가 EXPIRED 로 바꿔야 비로소 다음 작업을 보낼 수 있다.
        // 이 UPDATE 를 빠뜨리면 해당 기기는 영구히 작업을 받지 못한다.
    }

    @Test
    @DisplayName("만료 처리하면 다음 작업을 보낼 수 있다")
    void 만료_처리하면_기기가_풀린다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long firstTask = 작업을_만든다(userId, deviceId);
        long secondTask = 작업을_만든다(userId, deviceId);

        전달을_만든다(firstTask, deviceId);

        dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.STATUS, "EXPIRED")
                .set(AGENT_DISPATCHES.COMPLETED_AT, OffsetDateTime.now())
                .set(AGENT_DISPATCHES.REASON_CODE, "TASK_EXPIRED")
                .where(AGENT_DISPATCHES.TASK_ID.eq(firstTask))
                .execute();

        assertThat(전달을_만든다(secondTask, deviceId)).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 사용자의 PC로는 작업을 전달할 수 없다")
    void 타_사용자_기기로는_전달할_수_없다() {
        long ownerId = 사용자를_만든다();
        long ownerDeviceId = 기기를_만든다(ownerId);
        long taskId = 작업을_만든다(ownerId, ownerDeviceId);

        long otherUserId = 사용자를_만든다();
        long otherDeviceId = 기기를_만든다(otherUserId);

        // task_id 와 device_id 를 따로 참조하면 서비스 코드의 실수로
        // 남의 PC 에서 내 작업이 실행될 수 있다. 복합 FK 가 이를 막는다.
        assertThatThrownBy(() -> 전달을_만든다(taskId, otherDeviceId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("작업의 대상 기기와 다른 기기로는 전달할 수 없다")
    void 작업의_대상_기기와_달라도_거부된다() {
        long userId = 사용자를_만든다();
        long targetDeviceId = 기기를_만든다(userId);
        long anotherDeviceId = 기기를_만든다(userId);
        long taskId = 작업을_만든다(userId, targetDeviceId);

        // 같은 사용자의 다른 PC 라도 작업이 지정한 대상이 아니면 전달할 수 없다.
        assertThatThrownBy(() -> 전달을_만든다(taskId, anotherDeviceId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("완료 시각 없이 최종 상태로 바꿀 수 없다")
    void 최종_상태에는_완료_시각이_필요하다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long taskId = 작업을_만든다(userId, deviceId);
        전달을_만든다(taskId, deviceId);

        assertThatThrownBy(() -> dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.STATUS, "COMPLETED")
                .where(AGENT_DISPATCHES.TASK_ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("사유 코드는 실패·만료 상태에서만 저장된다")
    void 사유_코드는_실패_상태에서만_저장된다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long taskId = 작업을_만든다(userId, deviceId);
        전달을_만든다(taskId, deviceId);

        assertThatThrownBy(() -> dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.STATUS, "ACKNOWLEDGED")
                .set(AGENT_DISPATCHES.REASON_CODE, "DEVICE_BUSY")
                .where(AGENT_DISPATCHES.TASK_ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("재전송 횟수는 음수가 될 수 없다")
    void 재전송_횟수는_음수가_될_수_없다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long taskId = 작업을_만든다(userId, deviceId);
        전달을_만든다(taskId, deviceId);

        assertThatThrownBy(() -> dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.ATTEMPT_COUNT, -1)
                .where(AGENT_DISPATCHES.TASK_ID.eq(taskId))
                .execute())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("작업을 지우면 전달 기록도 함께 지워진다")
    void 작업_삭제_시_전달도_삭제된다() {
        long userId = 사용자를_만든다();
        long deviceId = 기기를_만든다(userId);
        long taskId = 작업을_만든다(userId, deviceId);
        전달을_만든다(taskId, deviceId);

        dsl.deleteFrom(TASKS).where(TASKS.ID.eq(taskId)).execute();

        assertThat(dsl.fetchCount(AGENT_DISPATCHES, AGENT_DISPATCHES.TASK_ID.eq(taskId))).isZero();
    }
}
