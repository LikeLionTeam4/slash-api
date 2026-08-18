package com.likelion.slash.dispatch;

import static com.likelion.slash.jooq.Tables.AGENT_DISPATCHES;
import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.TASK_EVENTS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.TaskRepository;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DispatchExpirySweeper} 확인. (WBS W1-04)
 *
 * <p><b>이 스윕이 없으면 PC 가 영구히 막힌다.</b> 작업을 받은 PC 가 꺼지면 ACK 도 RESULT 도
 * 오지 않아 전달이 활성인 채로 남고, {@code uk_dispatch_active_device} 때문에 그 기기는
 * 다시 켜도 새 작업을 받지 못한다. 사용자가 손쓸 방법이 없는 상태다.
 *
 * <p>그래서 여기서 보는 것은 두 가지다 — <b>기기가 풀리는가</b>, 그리고 <b>그 작업이 화면에서
 * 끝나는가</b>. 하나만 되면 다른 쪽이 어긋난다.
 */
@SpringBootTest
@Transactional
class DispatchExpirySweeperTest {

    @Autowired
    private DispatchExpirySweeper sweeper;

    @Autowired
    private AgentDispatchRepository agentDispatchRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private DSLContext dsl;

    @Test
    @DisplayName("기한이 지난 전달을 마감해 막힌 기기를 푼다")
    void 막힌_기기를_푼다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        꺼진_PC_에_매달린_작업(deviceId);

        sweeper.expireOverdueDispatches();

        // 활성 전달이 없어야 그 기기가 다음 작업을 받을 수 있다.
        assertThat(agentDispatchRepository.findActiveByDeviceId(deviceId)).isEmpty();
        assertThat(taskRepository.isDeviceOccupied(deviceId)).isFalse();
    }

    @Test
    @DisplayName("전달에 매달려 있던 작업도 함께 마감한다")
    void 작업도_함께_마감한다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        long taskId = 꺼진_PC_에_매달린_작업(deviceId);

        sweeper.expireOverdueDispatches();

        // 전달만 풀고 작업을 두면 기기는 자유로워지지만 화면의 진행 표시가 영영 돌아간다.
        TasksRecord 작업 = taskRepository.findById(taskId).orElseThrow();
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.EXPIRED.name());
        assertThat(작업.getErrorCode()).isEqualTo(ErrorCode.TASK_EXPIRED.name());
        assertThat(작업.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("어디서 멈췄는지 타임라인에 남긴다")
    void 타임라인에_남긴다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        long taskId = 꺼진_PC_에_매달린_작업(deviceId);

        sweeper.expireOverdueDispatches();

        // 대량 UPDATE 로 마감하면 이전 상태를 잃어 이 기록이 남지 않는다. 그러면 화면의
        // 진행 표시는 마지막 칸이 빈 채로 멈춘다.
        assertThat(dsl.fetch(TASK_EVENTS, TASK_EVENTS.TASK_ID.eq(taskId)))
                .extracting(record -> record.getFromStatus(), record -> record.getToStatus())
                .containsExactly(tuple(TaskStatus.QUEUED.name(), TaskStatus.EXPIRED.name()));
    }

    @Test
    @DisplayName("기한이 남은 전달은 건드리지 않는다")
    void 기한이_남으면_그대로_둔다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        long taskId = 작업(dsl, 기기의_주인(deviceId), deviceId, TaskStatus.QUEUED.name());
        agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));

        sweeper.expireOverdueDispatches();

        assertThat(agentDispatchRepository.findActiveByTaskId(taskId)).isPresent();
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.QUEUED.name());
    }

    @Test
    @DisplayName("ACK 를 받고 실행 중인 작업은 전달 기한이 지나도 죽이지 않는다")
    void 실행_중인_작업은_살려둔다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        long taskId = 작업(dsl, 기기의_주인(deviceId), deviceId, TaskStatus.RUNNING.name());

        // 전달 기한 60초로 만든 뒤, Agent 가 ACK 를 보내 실행을 시작한 상황이다.
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusSeconds(60));
        agentDispatchRepository.markDispatched(dispatch.getId());
        agentDispatchRepository.acknowledge(dispatch.getId(), SlashTime.now().plusMinutes(5));
        접수를_2분_전으로(dispatch.getId());

        sweeper.expireOverdueDispatches();

        // 여기서 죽이면 PC 가 일을 마치고 보낸 RESULT 가 버려진다. 사용자는 "기한이 지났습니다"를
        // 보는데 PC 는 작업을 끝낸 상태가 된다.
        assertThat(agentDispatchRepository.findActiveByTaskId(taskId)).isPresent();
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.RUNNING.name());
    }

    @Test
    @DisplayName("결과가 먼저 도착해 끝난 작업을 만료로 덮어쓰지 않는다")
    void 이미_끝난_작업은_덮어쓰지_않는다() {
        long deviceId = 준비된_기기(dsl, 사용자(dsl));
        long taskId = 작업(dsl, 기기의_주인(deviceId), deviceId, TaskStatus.RUNNING.name());
        기한을_넘긴다(agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(1)).getId());
        taskRepository.succeed(taskId, TaskStatus.RUNNING, JSONB.valueOf("{\"cpu\":12}"));

        sweeper.expireOverdueDispatches();

        // 전달은 마감해 기기를 풀되, 이미 사용자에게 보여준 결과는 지우지 않는다.
        assertThat(agentDispatchRepository.findActiveByDeviceId(deviceId)).isEmpty();
        TasksRecord 작업 = taskRepository.findById(taskId).orElseThrow();
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.SUCCEEDED.name());
        assertThat(작업.getResult()).isNotNull();
    }

    /** PC 가 작업을 받은 뒤 꺼진 상태. ACK 도 RESULT 도 오지 않아 전달이 활성인 채로 기한을 넘겼다. */
    private long 꺼진_PC_에_매달린_작업(long deviceId) {
        long taskId = 작업(dsl, 기기의_주인(deviceId), deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(1));
        agentDispatchRepository.markDispatched(dispatch.getId());
        기한을_넘긴다(dispatch.getId());
        return taskId;
    }

    /**
     * 전달을 기한이 지난 상태로 만든다.
     *
     * <p>{@code ck_dispatch_expires_after_created} 때문에 처음부터 과거 기한으로는 만들 수 없다.
     * 두 시각을 함께 뒤로 밀어 제약을 지키면서 만료 상태를 재현한다.
     */
    private void 기한을_넘긴다(long dispatchId) {
        OffsetDateTime 접수 = SlashTime.now().minusMinutes(10);
        dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.CREATED_AT, 접수)
                .set(AGENT_DISPATCHES.EXPIRES_AT, 접수.plusMinutes(1))
                .where(AGENT_DISPATCHES.ID.eq(dispatchId))
                .execute();
    }

    /**
     * 전달을 만든 지 오래된 상태로 만든다. 원래 전달 기한(60초)은 진작 지났다.
     *
     * <p>{@code expires_at} 은 건드리지 않는다 — ACK 가 실행 기준으로 다시 잡아 둔 값이라,
     * 그 값이 스윕을 막는지 보는 것이 이 시험의 목적이다.
     */
    private void 접수를_2분_전으로(long dispatchId) {
        dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.CREATED_AT, SlashTime.now().minusMinutes(2))
                .where(AGENT_DISPATCHES.ID.eq(dispatchId))
                .execute();
    }

    /** {@code fk_dispatch_task_device} 때문에 작업과 기기의 주인이 같아야 한다. */
    private long 기기의_주인(long deviceId) {
        return dsl.select(DEVICES.USER_ID).from(DEVICES)
                .where(DEVICES.ID.eq(deviceId)).fetchOne(DEVICES.USER_ID);
    }
}
