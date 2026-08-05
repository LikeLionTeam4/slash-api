package com.likelion.slash.job;

import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AsyncJobStatus;
import com.likelion.slash.common.enums.AsyncJobType;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AsyncJobRepository} 확인.
 *
 * <p>Worker 결과가 두 번 도착해도 한 번만 반영되는지가 핵심이다. (문서 7.2)
 */
@SpringBootTest
@Transactional
class AsyncJobRepositoryTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private AsyncJobRepository asyncJobRepository;

    private long 요약_작업() {
        return 작업(dsl, 사용자(dsl), null, TaskStatus.QUEUED.name());
    }

    @Test
    @DisplayName("접수하면 PENDING 으로 시작한다")
    void 접수_기본값() {
        var job = asyncJobRepository.create(요약_작업(), AsyncJobType.TEXT_SUMMARY,
                JSONB.valueOf("{\"text\":\"긴 글\"}"), SlashTime.now().plusMinutes(10));

        assertThat(job.getStatus()).isEqualTo(AsyncJobStatus.PENDING.name());
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("한 작업에는 AI Job 을 한 건만 만들 수 있다")
    void 작업당_한_건() {
        long taskId = 요약_작업();
        asyncJobRepository.create(taskId, AsyncJobType.TEXT_SUMMARY, null, SlashTime.now().plusMinutes(10));

        assertThatThrownBy(() -> asyncJobRepository.create(taskId, AsyncJobType.TEXT_SUMMARY, null,
                SlashTime.now().plusMinutes(10)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("발행하고 실행하면 시도 횟수와 시작 시각이 남는다")
    void 진행_기록() {
        var job = asyncJobRepository.create(요약_작업(), AsyncJobType.TEXT_SUMMARY, null,
                SlashTime.now().plusMinutes(10));

        assertThat(asyncJobRepository.markQueued(job.getId())).isTrue();
        assertThat(asyncJobRepository.markRunning(job.getId())).isTrue();

        var 실행중 = asyncJobRepository.findByPublicId(job.getPublicId()).orElseThrow();
        assertThat(실행중.getStatus()).isEqualTo(AsyncJobStatus.RUNNING.name());
        assertThat(실행중.getStartedAt()).isNotNull();
        assertThat(실행중.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SQS 재수신으로 다시 집으면 시도 횟수가 올라간다")
    void 재수신은_시도_횟수를_센다() {
        var job = asyncJobRepository.create(요약_작업(), AsyncJobType.TEXT_SUMMARY, null,
                SlashTime.now().plusMinutes(10));
        asyncJobRepository.markQueued(job.getId());

        asyncJobRepository.markRunning(job.getId());
        asyncJobRepository.markRunning(job.getId());

        assertThat(asyncJobRepository.findByTaskId(job.getTaskId()))
                .get()
                .extracting(record -> record.getAttemptCount())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("결과를 반영하면 완료 시각과 모델 정보가 남는다")
    void 성공_반영() {
        var job = asyncJobRepository.create(요약_작업(), AsyncJobType.TEXT_SUMMARY, null,
                SlashTime.now().plusMinutes(10));
        asyncJobRepository.markQueued(job.getId());
        asyncJobRepository.markRunning(job.getId());

        boolean 반영됨 = asyncJobRepository.succeed(job.getId(), UUID.randomUUID(),
                JSONB.valueOf("{\"summary\":\"요약문\"}"), "gemma-3", 1200);

        assertThat(반영됨).isTrue();
        var 완료 = asyncJobRepository.findByPublicId(job.getPublicId()).orElseThrow();
        assertThat(완료.getStatus()).isEqualTo(AsyncJobStatus.SUCCEEDED.name());
        assertThat(완료.getModel()).isEqualTo("gemma-3");
        assertThat(완료.getDurationMilliseconds()).isEqualTo(1200);
        assertThat(완료.getCompletedAt()).isNotNull();
        assertThat(완료.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("이미 마감된 Job 에는 결과가 다시 반영되지 않는다")
    void 결과는_한_번만_반영된다() {
        var job = asyncJobRepository.create(요약_작업(), AsyncJobType.TEXT_SUMMARY, null,
                SlashTime.now().plusMinutes(10));
        asyncJobRepository.markQueued(job.getId());
        asyncJobRepository.succeed(job.getId(), UUID.randomUUID(), JSONB.valueOf("{}"), "gemma-3", 100);

        boolean 두번째 = asyncJobRepository.succeed(job.getId(), UUID.randomUUID(),
                JSONB.valueOf("{\"summary\":\"다른 결과\"}"), "gemma-3", 100);

        assertThat(두번째).isFalse();
    }

    @Test
    @DisplayName("같은 결과 메시지가 다른 Job 에 반영되지 않는다")
    void 결과_메시지는_한_Job_에만() {
        UUID eventId = UUID.randomUUID();
        var 첫_Job = asyncJobRepository.create(요약_작업(), AsyncJobType.TEXT_SUMMARY, null,
                SlashTime.now().plusMinutes(10));
        var 둘째_Job = asyncJobRepository.create(요약_작업(), AsyncJobType.TEXT_SUMMARY, null,
                SlashTime.now().plusMinutes(10));
        asyncJobRepository.succeed(첫_Job.getId(), eventId, JSONB.valueOf("{}"), "gemma-3", 100);

        assertThatThrownBy(() -> asyncJobRepository.succeed(둘째_Job.getId(), eventId,
                JSONB.valueOf("{}"), "gemma-3", 100))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("실패를 반영하면 결과가 비고 재시도 가능 여부가 남는다")
    void 실패_반영() {
        var job = asyncJobRepository.create(요약_작업(), AsyncJobType.TEXT_SUMMARY, null,
                SlashTime.now().plusMinutes(10));

        boolean 반영됨 = asyncJobRepository.fail(job.getId(), UUID.randomUUID(),
                ErrorCode.LLM_NOT_READY.name(), true);

        assertThat(반영됨).isTrue();
        var 실패 = asyncJobRepository.findByPublicId(job.getPublicId()).orElseThrow();
        assertThat(실패.getStatus()).isEqualTo(AsyncJobStatus.FAILED.name());
        assertThat(실패.getResult()).isNull();
        assertThat(실패.getErrorCode()).isEqualTo(ErrorCode.LLM_NOT_READY.name());
        assertThat(실패.getRetryable()).isTrue();
    }

    @Test
    @DisplayName("배치가 기한이 지난 Job 을 마감해 GPU 작업을 정리한다")
    void 배치가_만료를_마감한다() {
        var job = asyncJobRepository.create(요약_작업(), AsyncJobType.TEXT_SUMMARY, null,
                SlashTime.now().plusMinutes(10));

        int 마감한_건수 = asyncJobRepository.expireOverdue(
                SlashTime.now().plusMinutes(20), ErrorCode.TASK_EXPIRED.name());

        assertThat(마감한_건수).isEqualTo(1);
        assertThat(asyncJobRepository.findByPublicId(job.getPublicId()))
                .get()
                .extracting(record -> record.getStatus())
                .isEqualTo(AsyncJobStatus.EXPIRED.name());
    }
}
