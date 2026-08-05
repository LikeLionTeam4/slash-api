package com.likelion.slash.dispatch;

import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.AgentDispatchStatus;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AgentDispatchRepository} 확인.
 *
 * <p>서비스 코드가 실수해도 DB 가 막아주는 두 가지를 특히 본다.
 * <ul>
 *   <li>기기당 동시 작업 1건 — 부분 UNIQUE 인덱스</li>
 *   <li>작업의 대상 기기와 실제 전달 기기 일치 — 복합 FK (문서 DV-04)</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class AgentDispatchRepositoryTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private AgentDispatchRepository agentDispatchRepository;

    @Test
    @DisplayName("전달을 만들면 PENDING 으로 시작한다")
    void 생성_기본값() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());

        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));

        assertThat(dispatch.getStatus()).isEqualTo(AgentDispatchStatus.PENDING.name());
        assertThat(dispatch.getAttemptCount()).isZero();
        assertThat(dispatch.getPublicId()).isNotNull();
    }

    @Test
    @DisplayName("이미 작업 중인 기기에는 전달을 더 만들 수 없다")
    void 기기당_동시_작업은_한_건() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long 첫_작업 = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        long 둘째_작업 = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        agentDispatchRepository.create(첫_작업, deviceId, SlashTime.now().plusMinutes(5));

        assertThatThrownBy(() ->
                agentDispatchRepository.create(둘째_작업, deviceId, SlashTime.now().plusMinutes(5)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("작업의 대상 기기와 다른 기기로는 전달할 수 없다")
    void 다른_기기로_새지_않는다() {
        long 주인 = 사용자(dsl);
        long 남 = 사용자(dsl);
        long 내_기기 = 준비된_기기(dsl, 주인);
        long 남의_기기 = 준비된_기기(dsl, 남);
        long 내_작업 = 작업(dsl, 주인, 내_기기, TaskStatus.QUEUED.name());

        assertThatThrownBy(() ->
                agentDispatchRepository.create(내_작업, 남의_기기, SlashTime.now().plusMinutes(5)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("마감하면 그 기기가 다음 작업을 받을 수 있다")
    void 마감하면_다음_작업을_받는다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long 첫_작업 = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        long 둘째_작업 = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var 첫_전달 = agentDispatchRepository.create(첫_작업, deviceId, SlashTime.now().plusMinutes(5));

        agentDispatchRepository.markDispatched(첫_전달.getId());
        agentDispatchRepository.acknowledge(첫_전달.getId());
        assertThat(agentDispatchRepository.complete(첫_전달.getId())).isTrue();

        var 둘째_전달 = agentDispatchRepository.create(둘째_작업, deviceId, SlashTime.now().plusMinutes(5));
        assertThat(둘째_전달.getStatus()).isEqualTo(AgentDispatchStatus.PENDING.name());
    }

    @Test
    @DisplayName("재전송하면 시도 횟수가 올라간다")
    void 재전송은_시도_횟수를_센다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));

        agentDispatchRepository.markDispatched(dispatch.getId());
        agentDispatchRepository.markDispatched(dispatch.getId());

        assertThat(agentDispatchRepository.findByPublicId(dispatch.getPublicId()))
                .get()
                .extracting(record -> record.getAttemptCount())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("RESULT 를 두 번 받아도 한 번만 반영된다")
    void 결과는_한_번만_반영된다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));
        agentDispatchRepository.markDispatched(dispatch.getId());

        assertThat(agentDispatchRepository.complete(dispatch.getId())).isTrue();
        assertThat(agentDispatchRepository.complete(dispatch.getId())).isFalse();
        assertThat(agentDispatchRepository.fail(dispatch.getId(), ErrorCode.POLICY_DENIED.name())).isFalse();
    }

    @Test
    @DisplayName("재연결하면 미완료 전달을 찾아 다시 보낼 수 있다")
    void 재연결_시_미완료_전달을_찾는다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));
        agentDispatchRepository.markDispatched(dispatch.getId());

        assertThat(agentDispatchRepository.findActiveByDeviceId(deviceId))
                .extracting(record -> record.getId())
                .containsExactly(dispatch.getId());
        assertThat(agentDispatchRepository.findActiveByTaskId(taskId)).isPresent();
    }

    @Test
    @DisplayName("배치가 기한이 지난 전달을 마감해 막힌 기기를 푼다")
    void 배치가_만료를_마감한다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));

        int 마감한_건수 = agentDispatchRepository.expireOverdue(
                SlashTime.now().plusMinutes(10), ErrorCode.TASK_EXPIRED.name());

        assertThat(마감한_건수).isEqualTo(1);
        var 만료된_전달 = agentDispatchRepository.findByPublicId(dispatch.getPublicId()).orElseThrow();
        assertThat(만료된_전달.getStatus()).isEqualTo(AgentDispatchStatus.EXPIRED.name());
        assertThat(만료된_전달.getCompletedAt()).isNotNull();
        assertThat(agentDispatchRepository.findActiveByDeviceId(deviceId)).isEmpty();
    }
}
