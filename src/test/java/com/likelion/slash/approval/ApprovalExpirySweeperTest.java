package com.likelion.slash.approval;

import static com.likelion.slash.jooq.Tables.TASK_APPROVALS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ApprovalStatus;
import com.likelion.slash.common.enums.TaskStatus;
import java.time.Duration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 답이 없는 승인 요청을 마감하는지 확인. (P0-C)
 *
 * <p><b>{@code StaleTaskSweeper} 가 있는데도 이 스윕을 두는 이유</b>가 여기서 드러난다.
 * 그쪽은 접수 후 30분이 지나야 잡는데 승인 기한은 그보다 짧다. 그것만 믿으면 기한이 지난
 * 뒤에도 한참을 승인할 수 있는 것처럼 보이고, <b>사용자가 잊은 요청이 20분 뒤에 실행된다.</b>
 */
@SpringBootTest
@Transactional
class ApprovalExpirySweeperTest {

    @Autowired
    private ApprovalExpirySweeper sweeper;

    @Autowired
    private TaskApprovalRepository approvalRepository;

    @Autowired
    private DSLContext dsl;

    private long taskId;

    @BeforeEach
    void setUp() {
        taskId = 작업(dsl, 사용자(dsl), null, TaskStatus.WAITING_FOR_APPROVAL.name());
    }

    private long 승인요청을_만든다(Duration 기한) {
        return approvalRepository.create(taskId, "해시", SlashTime.now().plus(기한)).getId();
    }

    /**
     * 기한이 지난 상태로 만든다.
     *
     * <p>{@code ck_task_approvals_expires_after_created} 때문에 기한만 과거로 미룰 수 없다.
     * 만든 시각도 함께 민다 — 실제로도 기한이 지난 요청은 그만큼 오래전에 만들어진 것이다.
     */
    private void 기한이_지나게_만든다(long approvalId) {
        dsl.update(TASK_APPROVALS)
                .set(TASK_APPROVALS.CREATED_AT, SlashTime.now().minus(Duration.ofHours(2)))
                .set(TASK_APPROVALS.EXPIRES_AT, SlashTime.now().minus(Duration.ofHours(1)))
                .where(TASK_APPROVALS.ID.eq(approvalId))
                .execute();
    }

    private String 승인상태(long approvalId) {
        return dsl.selectFrom(TASK_APPROVALS).where(TASK_APPROVALS.ID.eq(approvalId))
                .fetchOne().getStatus();
    }

    private String 작업상태() {
        return dsl.selectFrom(TASKS).where(TASKS.ID.eq(taskId)).fetchOne().getStatus();
    }

    @Test
    @DisplayName("기한이 지나면 승인과 작업을 함께 마감한다")
    void 기한이_지나면_마감한다() {
        long approvalId = 승인요청을_만든다(Duration.ofMinutes(10));
        기한이_지나게_만든다(approvalId);

        sweeper.expireOverdue();

        assertThat(승인상태(approvalId)).isEqualTo(ApprovalStatus.EXPIRED.name());

        // 승인만 닫으면 화면에는 끝나지 않는 확인 요청이 남는다.
        assertThat(작업상태()).isEqualTo(TaskStatus.EXPIRED.name());
    }

    @Test
    @DisplayName("아직 기한이 남은 요청은 건드리지 않는다")
    void 남은_것은_두고_본다() {
        long approvalId = 승인요청을_만든다(Duration.ofMinutes(10));

        sweeper.expireOverdue();

        assertThat(승인상태(approvalId)).isEqualTo(ApprovalStatus.PENDING.name());
        assertThat(작업상태()).isEqualTo(TaskStatus.WAITING_FOR_APPROVAL.name());
    }
}
