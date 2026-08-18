package com.likelion.slash.dispatch;

import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import static com.likelion.slash.jooq.Tables.AGENT_DISPATCHES;

import com.likelion.slash.common.SlashTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import com.likelion.slash.common.enums.AgentDispatchStatus;
import com.likelion.slash.jooq.tables.records.AgentDispatchesRecord;
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
        agentDispatchRepository.acknowledge(첫_전달.getId(), SlashTime.now().plusMinutes(5));
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
    @DisplayName("ACK 를 받으면 기한을 실행 기준으로 다시 잡는다")
    void ACK_가_기한을_다시_잡는다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var 전달_기한 = SlashTime.now().plusSeconds(60);
        var dispatch = agentDispatchRepository.create(taskId, deviceId, 전달_기한);

        // 나노초를 일부러 채운다. timestamptz 는 마이크로초까지만 담고 그 아래를 반올림하는데,
        // Linux JVM 은 나노초까지 만들어 내고 macOS 는 마이크로초에서 끊는다. 그대로 두면
        // 저장한 값과 읽은 값이 어긋나는 상황이 CI 에서만 생겨 로컬에서는 보이지 않는다.
        var 실행_기한 = SlashTime.now().plusMinutes(5).withNano(123_456_789);
        agentDispatchRepository.acknowledge(dispatch.getId(), 실행_기한);

        // 전달 기한(60초)은 전달이 도달하는 데 주는 시간이다. 그 값으로 실행까지 재면
        // 오래 걸리는 작업이 실행 도중 만료되고, PC 가 끝내고 보낸 결과가 버려진다.
        OffsetDateTime 저장된_기한 = agentDispatchRepository
                .findByPublicId(dispatch.getPublicId()).orElseThrow().getExpiresAt();

        assertThat(저장된_기한)
                .isAfter(전달_기한)
                .isCloseTo(실행_기한, within(1, ChronoUnit.MILLIS));
    }

    @Test
    @DisplayName("배치가 기한이 지난 전달을 마감해 막힌 기기를 푼다")
    void 배치가_만료를_마감한다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));

        var 마감된 = agentDispatchRepository.expireOverdue(
                SlashTime.now().plusMinutes(10), ErrorCode.TASK_EXPIRED.name(), 1000);

        // 건수로 판정하지 않는다. 표 전체를 쓸어담는 배치라 이 시험 밖에서 커밋된 미완료
        // 전달 하나에도 흔들린다. 내가 만든 전달이 그 안에 있는지만 본다.
        //
        // 목록을 돌려받는 것 자체가 계약이다 — 부르는 쪽은 이 값으로 딸린 작업을 마감한다.
        assertThat(마감된).extracting(record -> record.getTaskId()).contains(taskId);

        var 만료된_전달 = agentDispatchRepository.findByPublicId(dispatch.getPublicId()).orElseThrow();
        assertThat(만료된_전달.getStatus()).isEqualTo(AgentDispatchStatus.EXPIRED.name());
        assertThat(만료된_전달.getCompletedAt()).isNotNull();
        assertThat(agentDispatchRepository.findActiveByDeviceId(deviceId)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 스윕 재발행 대상 (WBS W1-06 · docs/w1-06-wss-routing.md 5.4)
    //
    //   Pub/Sub 이 유실했거나 대상 Pod 이 재시작 중이어서 아무도 내보내지 못한 전달만
    //   다시 발행해야 한다. 조건이 하나라도 헐거우면 같은 작업이 여러 번 실행된다.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("아무도 내보내지 못한 전달을 재발행 대상으로 찾는다")
    void 미전달을_찾는다() {
        var dispatch = 오래된_미전달_전달();

        assertThat(agentDispatchRepository.findPendingForResend(SlashTime.now(), SlashTime.now(), 100))
                .extracting(record -> record.getId())
                .contains(dispatch.getId());
    }

    @Test
    @DisplayName("이미 소켓으로 나간 전달은 재발행하지 않는다")
    void 전송된_전달은_제외한다() {
        var dispatch = 오래된_미전달_전달();
        agentDispatchRepository.markDispatched(dispatch.getId());

        assertThat(agentDispatchRepository.findPendingForResend(SlashTime.now(), SlashTime.now(), 100))
                .extracting(record -> record.getId())
                .doesNotContain(dispatch.getId());
    }

    @Test
    @DisplayName("방금 만든 전달은 재발행하지 않는다 — 최초 발행과 겹치면 두 번 실행된다")
    void 방금_만든_전달은_제외한다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));

        assertThat(agentDispatchRepository.findPendingForResend(
                SlashTime.now().minusSeconds(5), SlashTime.now(), 100))
                .extracting(record -> record.getId())
                .doesNotContain(dispatch.getId());
    }

    @Test
    @DisplayName("기한이 지난 전달은 재발행하지 않는다 — 보내도 Agent 가 거부한다")
    void 만료된_전달은_제외한다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusSeconds(1));

        assertThat(agentDispatchRepository.findPendingForResend(
                SlashTime.now(), SlashTime.now().plusMinutes(1), 100))
                .extracting(record -> record.getId())
                .doesNotContain(dispatch.getId());
    }

    @Test
    @DisplayName("전송 기록보다 ACK 가 먼저 도착해도 반영한다")
    void 전송_기록보다_빠른_ACK() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));

        // markDispatched 를 부르기 전, 즉 PENDING 인 상태에서 ACK 가 온 경우다.
        // 소켓에 쓴 뒤 DISPATCHED 를 기록하므로 Agent 응답이 더 빠를 수 있다.
        assertThat(agentDispatchRepository.acknowledge(dispatch.getId(), SlashTime.now().plusMinutes(5))).isTrue();

        var 반영된_전달 = agentDispatchRepository.findByPublicId(dispatch.getPublicId()).orElseThrow();
        assertThat(반영된_전달.getStatus()).isEqualTo(AgentDispatchStatus.ACKNOWLEDGED.name());
        assertThat(반영된_전달.getAcknowledgedAt()).isNotNull();
        // ACK 가 왔다는 것은 프레임이 나갔다는 뜻이므로 전송 시각도 채워져 있어야 한다.
        assertThat(반영된_전달.getDispatchedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 마감된 전달에는 ACK 를 반영하지 않는다")
    void 마감된_전달의_ACK는_무시한다() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));
        agentDispatchRepository.markDispatched(dispatch.getId());
        agentDispatchRepository.complete(dispatch.getId());

        assertThat(agentDispatchRepository.acknowledge(dispatch.getId(), SlashTime.now().plusMinutes(5))).isFalse();
    }

    /** 만들어진 지 시간이 지났는데도 아직 PENDING 인 전달. */
    private AgentDispatchesRecord 오래된_미전달_전달() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.QUEUED.name());
        var dispatch = agentDispatchRepository.create(taskId, deviceId, SlashTime.now().plusMinutes(5));

        // created_at 은 DB 기본값이라 과거로 만들려면 직접 내려야 한다.
        dsl.update(AGENT_DISPATCHES)
                .set(AGENT_DISPATCHES.CREATED_AT, SlashTime.now().minusMinutes(1))
                .where(AGENT_DISPATCHES.ID.eq(dispatch.getId()))
                .execute();

        return dispatch;
    }
}
