package com.likelion.slash.job;

import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.OutboxAggregateType;
import com.likelion.slash.common.enums.TaskStatus;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OutboxEventRepository} 확인.
 *
 * <p>발행에 실패한 사건이 큐 앞을 막지 않고, 발행이 끝난 사건은 다시 집히지 않아야 한다.
 */
@SpringBootTest
@Transactional
class OutboxEventRepositoryTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private long 작업_하나() {
        return 작업(dsl, 사용자(dsl), null, TaskStatus.QUEUED.name());
    }

    @Test
    @DisplayName("남긴 사건은 곧바로 발행 대상이 된다")
    void 발행_대상이_된다() {
        long taskId = 작업_하나();

        var event = outboxEventRepository.append(OutboxAggregateType.TASK, taskId,
                "TASK_QUEUED", JSONB.valueOf("{\"taskId\":\"x\"}"));

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttemptCount()).isZero();
        assertThat(outboxEventRepository.pollUnpublished(10))
                .extracting(record -> record.getId())
                .contains(event.getId());
    }

    @Test
    @DisplayName("발행이 끝난 사건은 다시 집히지 않는다")
    void 발행된_사건은_다시_집지_않는다() {
        var event = outboxEventRepository.append(OutboxAggregateType.TASK, 작업_하나(),
                "TASK_QUEUED", JSONB.valueOf("{}"));

        assertThat(outboxEventRepository.markPublished(event.getId())).isTrue();

        assertThat(outboxEventRepository.pollUnpublished(10)).isEmpty();
        // 같은 사건을 두 번 발행 완료로 처리하지 않는다.
        assertThat(outboxEventRepository.markPublished(event.getId())).isFalse();
    }

    @Test
    @DisplayName("재시도를 미루면 그동안 다른 사건이 먼저 나간다")
    void 실패한_사건이_큐를_막지_않는다() {
        var 실패한_사건 = outboxEventRepository.append(OutboxAggregateType.TASK, 작업_하나(),
                "TASK_QUEUED", JSONB.valueOf("{}"));
        var 다음_사건 = outboxEventRepository.append(OutboxAggregateType.ASYNC_JOB, 작업_하나(),
                "JOB_CREATED", JSONB.valueOf("{}"));

        outboxEventRepository.scheduleRetry(실패한_사건.getId(), SlashTime.now().plusMinutes(1));

        assertThat(outboxEventRepository.pollUnpublished(10))
                .extracting(record -> record.getId())
                .containsExactly(다음_사건.getId());
    }

    @Test
    @DisplayName("재시도를 미루면 시도 횟수가 올라간다")
    void 재시도_횟수를_센다() {
        var event = outboxEventRepository.append(OutboxAggregateType.TASK, 작업_하나(),
                "TASK_QUEUED", JSONB.valueOf("{}"));

        outboxEventRepository.scheduleRetry(event.getId(), SlashTime.now().minusSeconds(1));
        outboxEventRepository.scheduleRetry(event.getId(), SlashTime.now().minusSeconds(1));

        assertThat(outboxEventRepository.pollUnpublished(10))
                .singleElement()
                .extracting(record -> record.getAttemptCount())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("오래된 발행 완료 기록을 정리한다")
    void 발행된_기록을_정리한다() {
        var event = outboxEventRepository.append(OutboxAggregateType.TASK, 작업_하나(),
                "TASK_QUEUED", JSONB.valueOf("{}"));
        outboxEventRepository.markPublished(event.getId());

        int 지운_건수 = outboxEventRepository.deletePublishedBefore(SlashTime.now().plusMinutes(1));

        assertThat(지운_건수).isEqualTo(1);
    }
}
