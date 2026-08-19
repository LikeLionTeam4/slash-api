package com.likelion.slash.ws;

import static com.likelion.slash.jooq.Tables.DEVICES;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.TASK_EVENTS;
import static com.likelion.slash.jooq.Tables.USERS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.task.TaskStateWriter;
import com.likelion.slash.ws.dto.TaskStatusChangedEvent;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상태 전이가 브라우저 알림으로 이어지는지 확인. (WBS W1-06)
 *
 * <p>타임라인 기록과 알림은 짝이다. 기록만 남기고 알리지 않으면 화면은 새로고침해야 따라온다.
 *
 * <p><b>커밋 시점을 함께 본다.</b> 트랜잭션 안에서 그대로 발행하면 롤백된 전이가 화면에 뜬다.
 * 사용자는 진행되던 작업이 새로고침하면 되돌아가 있는 것을 보게 된다.
 */
@SpringBootTest
class UserEventPublisherTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private TaskStateWriter stateWriter;

    /** 실제 Valkey 로 내보내지 않고 발행 호출만 본다. */
    @MockitoBean
    private WsMessagePublisher publisher;

    @Test
    @DisplayName("상태를 옮기면 브라우저에 알린다")
    @Transactional
    void 전이를_알린다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, 준비된_기기(dsl, userId), TaskStatus.QUEUED.name());

        stateWriter.move(taskId, TaskStatus.QUEUED, TaskStatus.RUNNING, null, "시작");

        // 시험 트랜잭션은 롤백되므로 커밋 뒤 발행은 일어나지 않는다.
        // 바로 그것이 이 설계의 요점이다 — 아래 시험이 커밋된 경우를 본다.
        verify(publisher, never()).send(any(), anyLong(), any());
    }

    @Test
    @DisplayName("커밋된 뒤에 발행한다 — 롤백된 전이는 알리지 않는다")
    void 커밋_뒤에_알린다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, 준비된_기기(dsl, userId), TaskStatus.QUEUED.name());

        // @Transactional 이 없으므로 stateWriter 안의 트랜잭션이 실제로 커밋된다.
        stateWriter.move(taskId, TaskStatus.QUEUED, TaskStatus.RUNNING, null, "시작");

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(publisher).send(eq(WsTarget.USER), eq(userId), frame.capture());

        assertThat(frame.getValue()).isInstanceOf(TaskStatusChangedEvent.class);
        TaskStatusChangedEvent event = (TaskStatusChangedEvent) frame.getValue();
        assertThat(event.from()).isEqualTo(TaskStatus.QUEUED);
        assertThat(event.to()).isEqualTo(TaskStatus.RUNNING);
        assertThat(event.taskId()).isNotNull();

        정리(taskId, userId);
    }

    @Test
    @DisplayName("성공으로 마감하면 상태 변경과 결과 도착을 함께 알린다")
    void 마감을_두_번_알린다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, 준비된_기기(dsl, userId), TaskStatus.RUNNING.name());

        stateWriter.succeed(taskId, JSONB.valueOf("{\"cpu\":12}"), "완료");

        // 프론트는 상태로 진행 표시를 끄고, 결과 도착 신호로 REST 를 다시 조회한다.
        ArgumentCaptor<Object> frames = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(2))
                .send(eq(WsTarget.USER), eq(userId), frames.capture());

        assertThat(frames.getAllValues())
                .extracting(f -> f.getClass().getSimpleName())
                .containsExactly("TaskStatusChangedEvent", "TaskResultAvailableEvent");

        정리(taskId, userId);
    }

    @Test
    @DisplayName("실패로 마감해도 알린다")
    void 실패도_알린다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, 준비된_기기(dsl, userId), TaskStatus.RUNNING.name());

        stateWriter.failFromWorker(taskId, ErrorCode.POLICY_DENIED, "거부됨");

        verify(publisher, times(2)).send(eq(WsTarget.USER), eq(userId), any());

        정리(taskId, userId);
    }

    /** 이 시험들은 커밋을 봐야 해서 롤백되지 않는다. 만든 행을 직접 지운다. */
    private void 정리(long taskId, long userId) {
        dsl.deleteFrom(TASK_EVENTS).where(TASK_EVENTS.TASK_ID.eq(taskId)).execute();
        dsl.deleteFrom(TASKS).where(TASKS.ID.eq(taskId)).execute();
        dsl.deleteFrom(DEVICES).where(DEVICES.USER_ID.eq(userId)).execute();
        dsl.deleteFrom(USERS).where(USERS.ID.eq(userId)).execute();
    }
}
