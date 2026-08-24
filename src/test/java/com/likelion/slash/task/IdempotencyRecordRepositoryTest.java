package com.likelion.slash.task;

import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link IdempotencyRecordRepository} 확인.
 *
 * <p>중복 클릭이나 재전송으로 작업이 두 번 만들어지지 않는지가 핵심이다.
 */
@SpringBootTest
@Transactional
class IdempotencyRecordRepositoryTest {

    private static final String 경로 = "/api/v1/requests";

    @Autowired
    private DSLContext dsl;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Test
    @DisplayName("처음 온 멱등키는 선점된다")
    void 첫_요청은_선점한다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, null, TaskStatus.CREATED.name());
        String key = UUID.randomUUID().toString();

        var 선점 = idempotencyRecordRepository.tryInsert(userId, key, 경로, "hash-a", taskId, 202,
                SlashTime.now().plusHours(24));

        assertThat(선점).isPresent();
        assertThat(선점.get().getTaskId()).isEqualTo(taskId);
    }

    @Test
    @DisplayName("같은 멱등키로 다시 오면 선점하지 못한다")
    void 재전송은_선점하지_못한다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, null, TaskStatus.CREATED.name());
        long 다른_작업 = 작업(dsl, userId, null, TaskStatus.CREATED.name());
        String key = UUID.randomUUID().toString();
        idempotencyRecordRepository.tryInsert(userId, key, 경로, "hash-a", taskId, 202,
                SlashTime.now().plusHours(24));

        var 두번째 = idempotencyRecordRepository.tryInsert(userId, key, 경로, "hash-a", 다른_작업, 202,
                SlashTime.now().plusHours(24));

        assertThat(두번째).isEmpty();
        // 기존 기록이 그대로 남아 있어야 같은 Task 를 되돌려줄 수 있다.
        assertThat(idempotencyRecordRepository.find(userId, key, 경로))
                .get()
                .extracting(record -> record.getTaskId())
                .isEqualTo(taskId);
    }

    @Test
    @DisplayName("같은 키에 다른 본문이 오면 해시로 구분할 수 있다")
    void 다른_본문은_해시로_구분한다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, null, TaskStatus.CREATED.name());
        String key = UUID.randomUUID().toString();
        idempotencyRecordRepository.tryInsert(userId, key, 경로, "hash-a", taskId, 202,
                SlashTime.now().plusHours(24));

        var 기존 = idempotencyRecordRepository.find(userId, key, 경로).orElseThrow();

        // 해시가 다르면 서비스가 IDEMPOTENCY_CONFLICT 로 거부한다.
        assertThat(기존.getRequestHash()).isNotEqualTo("hash-b");
    }

    @Test
    @DisplayName("사용자가 다르면 같은 키를 써도 서로 막지 않는다")
    void 사용자별로_분리된다() {
        long 사용자A = 사용자(dsl);
        long 사용자B = 사용자(dsl);
        String key = UUID.randomUUID().toString();
        idempotencyRecordRepository.tryInsert(사용자A, key, 경로, "hash-a",
                작업(dsl, 사용자A, null, TaskStatus.CREATED.name()), 202, SlashTime.now().plusHours(24));

        var B의_선점 = idempotencyRecordRepository.tryInsert(사용자B, key, 경로, "hash-a",
                작업(dsl, 사용자B, null, TaskStatus.CREATED.name()), 202, SlashTime.now().plusHours(24));

        assertThat(B의_선점).isPresent();
    }

    @Test
    @DisplayName("배치가 보존 기간이 지난 기록을 지운다")
    void 배치가_만료를_지운다() {
        long userId = 사용자(dsl);
        String key = UUID.randomUUID().toString();
        idempotencyRecordRepository.tryInsert(userId, key, 경로, "hash-a",
                작업(dsl, userId, null, TaskStatus.CREATED.name()), 202, SlashTime.now().plusHours(24));

        idempotencyRecordRepository.deleteExpired(SlashTime.now().plusHours(25));

        // 지워진 건수를 세지 않는다. 이 배치는 표 전체를 도는 것이라, 로컬에서 앱을 띄워
        // 남긴 기록이 있으면 그 수가 달라진다 — 시험이 개발용 자료에 흔들린다. (#47)
        assertThat(idempotencyRecordRepository.find(userId, key, 경로)).isEmpty();
    }
}
