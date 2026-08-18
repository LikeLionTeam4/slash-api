package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.TASK_EVENTS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import java.time.Duration;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link StaleTaskSweeper} 확인. (WBS W1-04)
 *
 * <p>전달이 붙은 작업은 {@code DispatchExpirySweeper} 가 그 기한에 맞춰 정리한다. 여기서 보는 것은
 * <b>전달이 애초에 만들어지지 않은 작업</b>이다 — PC 를 켜지 않아 기다리거나, 되묻는 말에
 * 답하지 않고 창을 닫은 경우다. 그물이 없으면 그런 작업이 화면에서 영영 "진행 중"으로 남는다.
 */
@SpringBootTest
@Transactional
class StaleTaskSweeperTest {

    @Autowired
    private StaleTaskSweeper sweeper;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private DSLContext dsl;

    @Test
    @DisplayName("PC 를 켜지 않아 계속 기다리던 작업을 만료로 마감한다")
    void 기다리다_만료된다() {
        long taskId = 오래된_작업(TaskStatus.WAITING_FOR_DEVICE);

        sweeper.expireStaleTasks();

        TasksRecord 작업 = taskRepository.findById(taskId).orElseThrow();
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.EXPIRED.name());
        assertThat(작업.getErrorCode()).isEqualTo(ErrorCode.TASK_EXPIRED.name());
        assertThat(작업.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("왜 끝났는지 알 수 있게 상태에 맞는 안내를 남긴다")
    void 상태에_맞는_안내를_남긴다() {
        long 기다리던_작업 = 오래된_작업(TaskStatus.WAITING_FOR_DEVICE);
        long 되묻던_작업 = 오래된_작업(TaskStatus.NEEDS_CLARIFICATION);

        sweeper.expireStaleTasks();

        // "기한이 지났습니다" 하나로 뭉뚱그리면 PC 를 켜야 했던 것인지 답을 해야 했던 것인지
        // 사용자가 알 수 없다.
        assertThat(마지막_안내(기다리던_작업)).contains("PC");
        assertThat(마지막_안내(되묻던_작업)).contains("답");
    }

    @Test
    @DisplayName("방금 접수한 작업은 건드리지 않는다")
    void 방금_접수한_작업은_그대로_둔다() {
        long taskId = 작업(dsl, 사용자(dsl), null, TaskStatus.WAITING_FOR_DEVICE.name());

        sweeper.expireStaleTasks();

        // 사람을 기다리는 상태다. 짧게 잡으면 멀쩡히 기다리던 작업을 서버가 먼저 지운다.
        assertThat(taskRepository.findById(taskId).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.WAITING_FOR_DEVICE.name());
    }

    @Test
    @DisplayName("이미 끝난 작업은 오래돼도 대상이 아니다")
    void 끝난_작업은_대상이_아니다() {
        long taskId = 오래된_작업(TaskStatus.RUNNING);
        taskRepository.succeed(taskId, TaskStatus.RUNNING, JSONB.valueOf("{\"cpu\":12}"));

        sweeper.expireStaleTasks();

        TasksRecord 작업 = taskRepository.findById(taskId).orElseThrow();
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.SUCCEEDED.name());
        assertThat(작업.getResult()).isNotNull();
    }

    /** 기한(기본 30분)을 넉넉히 넘긴 작업. */
    private long 오래된_작업(TaskStatus status) {
        long taskId = 작업(dsl, 사용자(dsl), null, status.name());
        dsl.update(TASKS)
                .set(TASKS.CREATED_AT, SlashTime.now().minus(Duration.ofHours(2)))
                .where(TASKS.ID.eq(taskId))
                .execute();
        return taskId;
    }

    private String 마지막_안내(long taskId) {
        return dsl.selectFrom(TASK_EVENTS)
                .where(TASK_EVENTS.TASK_ID.eq(taskId))
                .orderBy(TASK_EVENTS.SEQUENCE.desc())
                .limit(1)
                .fetchOne(TASK_EVENTS.MESSAGE);
    }
}
