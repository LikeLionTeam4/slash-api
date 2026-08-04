package com.likelion.slash.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 문서 3.10 의 상태 전이 규칙이 코드와 일치하는지 확인한다.
 * 허용되지 않은 전이는 409 Conflict 로 거부해야 한다.
 */
class TaskStatusTest {

    @Test
    @DisplayName("정상 흐름: CREATED -> ANALYZING -> QUEUED -> RUNNING -> SUCCEEDED")
    void 정상_흐름_전이가_허용된다() {
        assertThat(TaskStatus.CREATED.canTransitionTo(TaskStatus.ANALYZING)).isTrue();
        assertThat(TaskStatus.ANALYZING.canTransitionTo(TaskStatus.QUEUED)).isTrue();
        assertThat(TaskStatus.QUEUED.canTransitionTo(TaskStatus.RUNNING)).isTrue();
        assertThat(TaskStatus.RUNNING.canTransitionTo(TaskStatus.SUCCEEDED)).isTrue();
    }

    @Test
    @DisplayName("필수 인자가 부족하면 사용자 보완 후 다시 분석할 수 있다")
    void 확인_질문_후_재분석이_가능하다() {
        assertThat(TaskStatus.ANALYZING.canTransitionTo(TaskStatus.NEEDS_CLARIFICATION)).isTrue();
        assertThat(TaskStatus.NEEDS_CLARIFICATION.canTransitionTo(TaskStatus.ANALYZING)).isTrue();
    }

    @Test
    @DisplayName("PC 준비를 기다린 뒤 대기열로 넘어간다")
    void 기기_대기_후_큐잉이_가능하다() {
        assertThat(TaskStatus.ANALYZING.canTransitionTo(TaskStatus.WAITING_FOR_DEVICE)).isTrue();
        assertThat(TaskStatus.WAITING_FOR_DEVICE.canTransitionTo(TaskStatus.QUEUED)).isTrue();
    }

    @Test
    @DisplayName("단계를 건너뛰는 전이는 거부한다")
    void 건너뛰는_전이는_거부된다() {
        assertThat(TaskStatus.CREATED.canTransitionTo(TaskStatus.SUCCEEDED)).isFalse();
        assertThat(TaskStatus.CREATED.canTransitionTo(TaskStatus.RUNNING)).isFalse();
        assertThat(TaskStatus.QUEUED.canTransitionTo(TaskStatus.SUCCEEDED)).isFalse();
    }

    @Test
    @DisplayName("최종 상태에서는 더 이상 전이할 수 없다")
    void 최종_상태는_전이가_불가능하다() {
        for (TaskStatus terminal : new TaskStatus[]{
                TaskStatus.SUCCEEDED, TaskStatus.FAILED, TaskStatus.EXPIRED}) {

            assertThat(terminal.isTerminal()).isTrue();
            for (TaskStatus next : TaskStatus.values()) {
                assertThat(terminal.canTransitionTo(next))
                        .as("%s -> %s 는 허용되지 않아야 한다", terminal, next)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("실패와 만료는 진행 중인 어느 단계에서든 발생할 수 있다")
    void 진행_중_상태는_언제든_실패하거나_만료될_수_있다() {
        for (TaskStatus status : TaskStatus.values()) {
            if (status.isTerminal()) {
                continue;
            }
            assertThat(status.canTransitionTo(TaskStatus.FAILED)).isTrue();
            assertThat(status.canTransitionTo(TaskStatus.EXPIRED)).isTrue();
        }
    }
}
