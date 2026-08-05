package com.likelion.slash.task;

import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.작업;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.ProcessingRoute;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.enums.TaskType;
import com.likelion.slash.common.error.ErrorCode;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TaskRepository} 확인.
 *
 * <p>상태 전이가 계약(문서 3.10)을 지키는지, 그리고 두 요청이 같은 작업을 동시에 옮길 때
 * 나중 것이 앞선 전이를 덮어쓰지 않는지가 핵심이다.
 */
@SpringBootTest
@Transactional
class TaskRepositoryTest {

    @Autowired
    private DSLContext dsl;

    @Autowired
    private TaskRepository taskRepository;

    @Test
    @DisplayName("접수하면 CREATED 로 시작하고 분석 결과는 비어 있다")
    void 접수_기본값() {
        long userId = 사용자(dsl);

        var task = taskRepository.create(userId, "오늘 서울 날씨 알려줘", UUID.randomUUID());

        assertThat(task.getStatus()).isEqualTo(TaskStatus.CREATED.name());
        assertThat(task.getTaskType()).isNull();
        assertThat(task.getProcessingRoute()).isNull();
        assertThat(task.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("남의 작업은 식별자를 알아도 조회되지 않는다")
    void 남의_작업은_조회되지_않는다() {
        long 주인 = 사용자(dsl);
        long 남 = 사용자(dsl);
        var task = taskRepository.create(주인, "내 요청", null);

        assertThat(taskRepository.findByPublicIdAndUserId(task.getPublicId(), 주인)).isPresent();
        assertThat(taskRepository.findByPublicIdAndUserId(task.getPublicId(), 남)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 상태 전이
    // ------------------------------------------------------------------

    @Test
    @DisplayName("허용된 전이는 반영된다")
    void 허용된_전이() {
        long userId = 사용자(dsl);
        var task = taskRepository.create(userId, "요청", null);

        assertThat(taskRepository.transition(task.getId(), TaskStatus.CREATED, TaskStatus.ANALYZING)).isTrue();
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.ANALYZING.name());
    }

    @Test
    @DisplayName("계약에 없는 전이는 DB 에 닿기 전에 막는다")
    void 계약에_없는_전이는_거부한다() {
        long userId = 사용자(dsl);
        var task = taskRepository.create(userId, "요청", null);

        assertThatThrownBy(() ->
                taskRepository.transition(task.getId(), TaskStatus.CREATED, TaskStatus.RUNNING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("허용되지 않은 상태 전이");
    }

    @Test
    @DisplayName("현재 상태가 기대값과 다르면 전이가 반영되지 않는다")
    void 앞선_전이를_덮어쓰지_않는다() {
        long userId = 사용자(dsl);
        var task = taskRepository.create(userId, "요청", null);
        // 다른 요청이 먼저 ANALYZING 으로 옮긴 상황
        taskRepository.transition(task.getId(), TaskStatus.CREATED, TaskStatus.ANALYZING);

        boolean 반영됨 = taskRepository.transition(task.getId(), TaskStatus.CREATED, TaskStatus.ANALYZING);

        assertThat(반영됨).isFalse();
    }

    @Test
    @DisplayName("최종 상태는 transition 으로 옮길 수 없다")
    void 최종_상태는_전용_메서드로만() {
        long userId = 사용자(dsl);
        var task = taskRepository.create(userId, "요청", null);

        assertThatThrownBy(() ->
                taskRepository.transition(task.getId(), TaskStatus.CREATED, TaskStatus.FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("succeed·finishWithError");
    }

    @Test
    @DisplayName("성공 마감은 결과와 완료 시각을 함께 남긴다")
    void 성공_마감() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, null, TaskStatus.RUNNING.name());

        boolean 반영됨 = taskRepository.succeed(taskId, TaskStatus.RUNNING,
                JSONB.valueOf("{\"temperature\":29}"));

        assertThat(반영됨).isTrue();
        var 마감된_작업 = taskRepository.findById(taskId).orElseThrow();
        assertThat(마감된_작업.getStatus()).isEqualTo(TaskStatus.SUCCEEDED.name());
        assertThat(마감된_작업.getCompletedAt()).isNotNull();
        assertThat(마감된_작업.getErrorCode()).isNull();
    }

    @Test
    @DisplayName("실패 마감은 부분 결과를 지우고 오류 코드를 남긴다")
    void 실패_마감은_결과를_지운다() {
        long userId = 사용자(dsl);
        long taskId = 작업(dsl, userId, null, TaskStatus.RUNNING.name());

        boolean 반영됨 = taskRepository.finishWithError(taskId, TaskStatus.RUNNING,
                TaskStatus.FAILED, ErrorCode.NLU_UNAVAILABLE);

        assertThat(반영됨).isTrue();
        var 마감된_작업 = taskRepository.findById(taskId).orElseThrow();
        assertThat(마감된_작업.getStatus()).isEqualTo(TaskStatus.FAILED.name());
        assertThat(마감된_작업.getResult()).isNull();
        assertThat(마감된_작업.getErrorCode()).isEqualTo(ErrorCode.NLU_UNAVAILABLE.name());
        assertThat(마감된_작업.getCompletedAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // 분석 결과 반영
    // ------------------------------------------------------------------

    @Test
    @DisplayName("분석 결과를 반영한다")
    void 분석_결과_반영() {
        long userId = 사용자(dsl);
        var task = taskRepository.create(userId, "오늘 서울 날씨", null);
        taskRepository.transition(task.getId(), TaskStatus.CREATED, TaskStatus.ANALYZING);

        boolean 반영됨 = taskRepository.applyAnalysis(task.getId(), TaskType.WEATHER_LOOKUP,
                ProcessingRoute.BACKEND_SERVICE, null,
                JSONB.valueOf("{\"location\":\"서울\"}"), "서울 날씨 조회");

        assertThat(반영됨).isTrue();
        var 분석된_작업 = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(분석된_작업.getTaskType()).isEqualTo(TaskType.WEATHER_LOOKUP.name());
        assertThat(분석된_작업.getProcessingRoute()).isEqualTo(ProcessingRoute.BACKEND_SERVICE.name());
        assertThat(분석된_작업.getRequestSummary()).isEqualTo("서울 날씨 조회");
    }

    @Test
    @DisplayName("로컬 실행 작업에 대상 기기가 없으면 DB 에 닿기 전에 막는다")
    void 로컬_실행에는_기기가_필요하다() {
        long userId = 사용자(dsl);
        var task = taskRepository.create(userId, "보고서 파일 찾아줘", null);
        taskRepository.transition(task.getId(), TaskStatus.CREATED, TaskStatus.ANALYZING);

        assertThatThrownBy(() -> taskRepository.applyAnalysis(task.getId(), TaskType.FILE_SEARCH,
                ProcessingRoute.LOCAL_AGENT, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대상 기기가 필요");
    }

    // ------------------------------------------------------------------
    // 이력·동시 실행
    // ------------------------------------------------------------------

    @Test
    @DisplayName("이력은 최신순으로 읽고 커서로 다음 쪽을 이어 받는다")
    void 이력_커서_조회() {
        long userId = 사용자(dsl);
        var 첫번째 = taskRepository.create(userId, "요청 1", null);
        var 두번째 = taskRepository.create(userId, "요청 2", null);
        var 세번째 = taskRepository.create(userId, "요청 3", null);

        var 첫쪽 = taskRepository.findRecent(userId, null, null, 2);
        assertThat(첫쪽).extracting(record -> record.getId())
                .containsExactly(세번째.getId(), 두번째.getId());

        var 마지막 = 첫쪽.get(첫쪽.size() - 1);
        var 다음쪽 = taskRepository.findRecent(userId, 마지막.getCreatedAt(), 마지막.getId(), 2);
        assertThat(다음쪽).extracting(record -> record.getId())
                .containsExactly(첫번째.getId());
    }

    @Test
    @DisplayName("이력에 남의 작업이 섞이지 않는다")
    void 이력은_내_것만() {
        long 나 = 사용자(dsl);
        long 남 = 사용자(dsl);
        taskRepository.create(남, "남의 요청", null);
        var 내_작업 = taskRepository.create(나, "내 요청", null);

        assertThat(taskRepository.findRecent(나, null, null, 20))
                .extracting(record -> record.getId())
                .containsExactly(내_작업.getId());
    }

    @Test
    @DisplayName("마감된 작업은 기기의 동시 실행 판정에서 빠진다")
    void 동시_실행_판정() {
        long userId = 사용자(dsl);
        long deviceId = 준비된_기기(dsl, userId);
        long taskId = 작업(dsl, userId, deviceId, TaskStatus.RUNNING.name());

        assertThat(taskRepository.hasActiveTaskOnDevice(deviceId)).isTrue();

        taskRepository.succeed(taskId, TaskStatus.RUNNING, JSONB.valueOf("{}"));

        assertThat(taskRepository.hasActiveTaskOnDevice(deviceId)).isFalse();
    }

    @Test
    @DisplayName("배치가 기한이 지난 미완료 작업을 만료로 마감한다")
    void 만료_마감_배치() {
        long userId = 사용자(dsl);
        long 미완료 = 작업(dsl, userId, null, TaskStatus.QUEUED.name());
        long 이미_성공 = 작업(dsl, userId, null, TaskStatus.RUNNING.name());
        taskRepository.succeed(이미_성공, TaskStatus.RUNNING, JSONB.valueOf("{}"));

        int 마감한_건수 = taskRepository.expireOverdue(SlashTime.now().plusMinutes(1));

        assertThat(마감한_건수).isEqualTo(1);
        var 만료된_작업 = taskRepository.findById(미완료).orElseThrow();
        assertThat(만료된_작업.getStatus()).isEqualTo(TaskStatus.EXPIRED.name());
        assertThat(만료된_작업.getErrorCode()).isEqualTo(ErrorCode.TASK_EXPIRED.name());
    }
}
