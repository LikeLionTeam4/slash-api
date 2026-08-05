package com.likelion.slash.task;

import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TaskEventRepository} 확인.
 *
 * <p>같은 밀리초에 여러 이벤트가 생겨도 순서를 잃지 않아야 한다.
 * 그래서 {@code occurred_at} 이 아니라 {@code sequence} 로 정렬한다.
 */
@SpringBootTest
@Transactional
class TaskEventRepositoryTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private TaskEventRepository taskEventRepository;

    @Test
    @DisplayName("순번은 1 부터 빠짐없이 올라간다")
    void 순번_채번() {
        long taskId = 작업(dsl, 사용자(dsl), null, TaskStatus.CREATED.name());

        taskEventRepository.append(taskId, null, TaskStatus.CREATED, null, "요청을 접수했습니다.");
        taskEventRepository.append(taskId, TaskStatus.CREATED, TaskStatus.ANALYZING, null, null);
        taskEventRepository.append(taskId, TaskStatus.ANALYZING, TaskStatus.QUEUED, null, null);

        assertThat(taskEventRepository.findAllByTaskId(taskId))
                .extracting(record -> record.getSequence())
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("작업마다 순번을 따로 센다")
    void 순번은_작업별로_센다() {
        long userId = 사용자(dsl);
        long 첫_작업 = 작업(dsl, userId, null, TaskStatus.CREATED.name());
        long 둘째_작업 = 작업(dsl, userId, null, TaskStatus.CREATED.name());

        taskEventRepository.append(첫_작업, null, TaskStatus.CREATED, null, null);
        taskEventRepository.append(첫_작업, TaskStatus.CREATED, TaskStatus.ANALYZING, null, null);
        var 둘째_첫_이벤트 = taskEventRepository.append(둘째_작업, null, TaskStatus.CREATED, null, null);

        assertThat(둘째_첫_이벤트.getSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("최초 생성 이벤트는 이전 상태가 없다")
    void 최초_이벤트는_이전_상태가_없다() {
        long taskId = 작업(dsl, 사용자(dsl), null, TaskStatus.CREATED.name());

        var 최초 = taskEventRepository.append(taskId, null, TaskStatus.CREATED, null, null);

        assertThat(최초.getFromStatus()).isNull();
        assertThat(최초.getToStatus()).isEqualTo(TaskStatus.CREATED.name());
    }

    @Test
    @DisplayName("실패 이벤트에는 사유 코드를 남긴다")
    void 실패_사유를_남긴다() {
        long taskId = 작업(dsl, 사용자(dsl), null, TaskStatus.CREATED.name());

        var 실패 = taskEventRepository.append(taskId, TaskStatus.ANALYZING, TaskStatus.FAILED,
                ErrorCode.NLU_UNAVAILABLE.name(), ErrorCode.NLU_UNAVAILABLE.defaultMessage());

        assertThat(실패.getReasonCode()).isEqualTo(ErrorCode.NLU_UNAVAILABLE.name());
        assertThat(실패.getMessage()).isEqualTo(ErrorCode.NLU_UNAVAILABLE.defaultMessage());
    }

    @Test
    @DisplayName("같은 상태로의 전이는 기록하지 않는다")
    void 같은_상태_전이는_거부한다() {
        long taskId = 작업(dsl, 사용자(dsl), null, TaskStatus.CREATED.name());

        assertThatThrownBy(() -> taskEventRepository.append(
                taskId, TaskStatus.RUNNING, TaskStatus.RUNNING, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("없는 작업에는 기록할 수 없다")
    void 없는_작업은_거부한다() {
        assertThatThrownBy(() -> taskEventRepository.append(
                -1L, null, TaskStatus.CREATED, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("놓친 구간만 순번 이후로 따라잡을 수 있다")
    void 놓친_구간을_따라잡는다() {
        long taskId = 작업(dsl, 사용자(dsl), null, TaskStatus.CREATED.name());
        taskEventRepository.append(taskId, null, TaskStatus.CREATED, null, null);
        taskEventRepository.append(taskId, TaskStatus.CREATED, TaskStatus.ANALYZING, null, null);
        taskEventRepository.append(taskId, TaskStatus.ANALYZING, TaskStatus.RUNNING, null, null);

        assertThat(taskEventRepository.findAfterSequence(taskId, 1, 20))
                .extracting(record -> record.getToStatus())
                .containsExactly(TaskStatus.ANALYZING.name(), TaskStatus.RUNNING.name());
    }
}
