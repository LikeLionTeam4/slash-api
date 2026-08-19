package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.TASK_EVENTS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TaskStateWriter} 의 Agent 결과 반영 확인. (WBS W1-04)
 *
 * <p>여기가 종단 경로의 마지막 칸이다. 접수 → 전달까지는 {@link TaskServiceTest} 가 보고,
 * 이 시험은 <b>Agent 가 돌려준 결과가 실제로 {@code tasks} 에 남는가</b>를 본다.
 * 이 구간이 비어 있으면 작업은 {@code QUEUED} 에서 영영 못 벗어나고, 사용자에게는
 * 끝나지 않는 진행 표시로 보인다.
 */
@SpringBootTest
@Transactional
class TaskStateWriterTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private TaskStateWriter stateWriter;

    @Test
    @DisplayName("실행 중이던 작업에 결과를 담아 성공으로 마감한다")
    void 결과를_담아_마감한다() {
        long taskId = 실행중인_작업();

        assertThat(stateWriter.succeed(taskId, JSONB.valueOf("{\"cpu\":12}"), "작업을 마쳤습니다.")).isTrue();

        TasksRecord 작업 = 조회(taskId);
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.SUCCEEDED.name());
        assertThat(작업.getResult().data()).contains("\"cpu\"");
        assertThat(작업.getCompletedAt()).isNotNull();
        assertThat(작업.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("ACK 를 못 받아 QUEUED 인 채로 결과가 와도 마감한다")
    void ACK_없이_온_결과도_마감한다() {
        long taskId = 전달된_작업();

        // 전이 규칙에 QUEUED → SUCCEEDED 는 없다. 그래서 RUNNING 을 한 칸 지나가야 한다.
        // 이걸 안 하면 ACK 프레임 하나 유실됐을 뿐인데 작업이 영영 안 끝난다.
        assertThat(stateWriter.succeed(taskId, JSONB.valueOf("{\"cpu\":12}"), "작업을 마쳤습니다.")).isTrue();

        assertThat(조회(taskId).getStatus()).isEqualTo(TaskStatus.SUCCEEDED.name());
    }

    @Test
    @DisplayName("지나간 RUNNING 도 타임라인에 남긴다 — 실행 없이 성공한 것처럼 보이면 안 된다")
    void 지나간_칸도_기록한다() {
        long taskId = 전달된_작업();

        stateWriter.succeed(taskId, JSONB.valueOf("{}"), "작업을 마쳤습니다.");

        assertThat(dsl.fetch(TASK_EVENTS, TASK_EVENTS.TASK_ID.eq(taskId)))
                .extracting(record -> record.getToStatus())
                .containsExactly(TaskStatus.RUNNING.name(), TaskStatus.SUCCEEDED.name());
    }

    @Test
    @DisplayName("이미 마감된 작업에는 결과를 덮어쓰지 않는다")
    void 마감된_작업은_건드리지_않는다() {
        long taskId = 실행중인_작업();
        stateWriter.failFromWorker(taskId, ErrorCode.POLICY_DENIED, "거부됨");

        // 기한 만료 스윕이 먼저 닿은 뒤 결과가 늦게 도착하는 경우다. 늦게 온 쪽이 물러난다.
        assertThat(stateWriter.succeed(taskId, JSONB.valueOf("{\"cpu\":12}"), "작업을 마쳤습니다.")).isFalse();

        TasksRecord 작업 = 조회(taskId);
        assertThat(작업.getStatus()).isEqualTo(TaskStatus.FAILED.name());
        assertThat(작업.getErrorCode()).isEqualTo(ErrorCode.POLICY_DENIED.name());
    }

    @Test
    @DisplayName("상한을 넘는 결과는 DataAccessException 으로 올라온다")
    void 너무_큰_결과는_DB_가_거부한다() {
        long taskId = 실행중인_작업();
        String 큰_값 = "x".repeat(70_000);

        // AgentWebSocketHandler 가 이 예외를 잡아 실패로 마감한다. 거기 시험은 예외를 mock 으로
        // 던지므로 "정말 이 타입이 오르는가"는 여기서만 확인된다. 타입이 어긋나면 잡지 못해
        // 연결이 끊기고 작업이 마감되지 않은 채 남는다. (ck_tasks_result_size)
        assertThatThrownBy(() ->
                stateWriter.succeed(taskId, JSONB.valueOf("{\"big\":\"" + 큰_값 + "\"}"), "작업을 마쳤습니다."))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("실패는 QUEUED 든 RUNNING 이든 부르는 쪽이 몰라도 마감된다")
    void 실패는_두_상태에서_모두_마감된다() {
        long 전달만_된_작업 = 전달된_작업();
        long 실행중_작업 = 실행중인_작업();

        // ACK 거부는 QUEUED 에서, RESULT 실패는 RUNNING 에서 온다.
        assertThat(stateWriter.failFromWorker(전달만_된_작업, ErrorCode.TASK_TYPE_NOT_SUPPORTED, "미지원")).isTrue();
        assertThat(stateWriter.failFromWorker(실행중_작업, ErrorCode.POLICY_DENIED, "거부됨")).isTrue();

        assertThat(조회(전달만_된_작업).getErrorCode()).isEqualTo(ErrorCode.TASK_TYPE_NOT_SUPPORTED.name());
        assertThat(조회(실행중_작업).getErrorCode()).isEqualTo(ErrorCode.POLICY_DENIED.name());
    }

    // ------------------------------------------------------------------

    /** 기기로 내보냈지만 아직 ACK 를 받지 못한 작업. */
    private long 전달된_작업() {
        long userId = 사용자(dsl);
        return 작업(dsl, userId, 준비된_기기(dsl, userId), TaskStatus.QUEUED.name());
    }

    /** ACK 를 받아 실행 중인 작업. */
    private long 실행중인_작업() {
        long userId = 사용자(dsl);
        return 작업(dsl, userId, 준비된_기기(dsl, userId), TaskStatus.RUNNING.name());
    }

    private TasksRecord 조회(long taskId) {
        return dsl.selectFrom(TASKS).where(TASKS.ID.eq(taskId)).fetchOne();
    }
}
