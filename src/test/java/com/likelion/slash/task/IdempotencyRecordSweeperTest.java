package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.IDEMPOTENCY_RECORDS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link IdempotencyRecordSweeper} 확인.
 *
 * <p>부르는 곳이 시험뿐이던 {@code deleteExpired} 에 배치가 생겼는지 본다. 조회가
 * {@code expires_at} 을 보지 않으므로 <b>지워졌는지가 곧 보존 기간이 지켜졌는지</b>다.
 *
 * <p><b>지운 건수로 단언하지 않는다.</b> {@code deleteExpired} 는 표 전체를 도므로 로컬에서
 * 앱을 띄운 뒤 시험을 돌리면 남의 자료까지 세어 건수가 달라진다. 내가 넣은 행이 어떻게
 * 됐는지만 본다. (#47 · #66 과 같은 결)
 */
@SpringBootTest
@Transactional
class IdempotencyRecordSweeperTest {

    private static final String 경로 = "/api/v1/requests";

    @Autowired
    private IdempotencyRecordSweeper sweeper;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private DSLContext dsl;

    /**
     * 지난 시각으로 만료되는 기록도 만들 수 있어야 한다.
     *
     * <p>{@code created_at} 을 함께 지정하는 이유 — {@code ck_idempotency_expires_after_created}
     * 가 {@code expires_at > created_at} 을 요구하는데, 그 열의 기본값은 PostgreSQL 의
     * {@code now()}(트랜잭션 시작 시각)다. 만료 시각만 과거로 넣으면 제약에 걸린다.
     * ({@code PairingRequestSweeperTest} 와 같은 이유)
     */
    private String 기록(long userId, long taskId, int 만료까지_시간) {
        String key = UUID.randomUUID().toString();
        OffsetDateTime 만료 = SlashTime.now().plusHours(만료까지_시간);

        dsl.insertInto(IDEMPOTENCY_RECORDS)
                .set(IDEMPOTENCY_RECORDS.USER_ID, userId)
                .set(IDEMPOTENCY_RECORDS.IDEMPOTENCY_KEY, key)
                .set(IDEMPOTENCY_RECORDS.REQUEST_PATH, 경로)
                .set(IDEMPOTENCY_RECORDS.REQUEST_HASH, "hash-a")
                .set(IDEMPOTENCY_RECORDS.TASK_ID, taskId)
                .set(IDEMPOTENCY_RECORDS.RESPONSE_STATUS, 202)
                .set(IDEMPOTENCY_RECORDS.CREATED_AT, 만료.minusHours(24))
                .set(IDEMPOTENCY_RECORDS.EXPIRES_AT, 만료)
                .execute();
        return key;
    }

    private boolean 남아있나(long userId, String key) {
        return repository.find(userId, key, 경로).isPresent();
    }

    @Test
    @DisplayName("보존 기간이 지난 기록을 지운다")
    void 만료된_기록을_지운다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, null, TaskStatus.CREATED.name());
        String 만료된_키 = 기록(userId, taskId, -1);

        sweeper.sweep();

        assertThat(남아있나(userId, 만료된_키)).isFalse();
    }

    @Test
    @DisplayName("아직 보존 기간 안에 있는 기록은 건드리지 않는다")
    void 살아있는_기록은_두다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, null, TaskStatus.CREATED.name());
        String 살아있는_키 = 기록(userId, taskId, 24);

        sweeper.sweep();

        assertThat(남아있나(userId, 살아있는_키)).isTrue();
    }

    @Test
    @DisplayName("지워진 뒤에는 같은 키를 다시 선점할 수 있다")
    void 지운_뒤에는_같은_키를_다시_쓴다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, null, TaskStatus.CREATED.name());
        long 다른_작업 = 작업(dsl, userId, null, TaskStatus.CREATED.name());
        String key = 기록(userId, taskId, -1);

        sweeper.sweep();

        // uk_idempotency_scope 가 막고 있던 행이 사라졌으므로 선점이 다시 통과한다.
        // 배치가 없던 동안에는 24시간이 지나도 이 선점이 영원히 막혔다.
        var 다시_선점 = repository.tryInsert(userId, key, 경로, "hash-b", 다른_작업, 202,
                SlashTime.now().plusHours(24));

        assertThat(다시_선점).isPresent();
        assertThat(다시_선점.get().getTaskId()).isEqualTo(다른_작업);
    }
}
