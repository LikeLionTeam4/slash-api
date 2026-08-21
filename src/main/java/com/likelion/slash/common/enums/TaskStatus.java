package com.likelion.slash.common.enums;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 요청(Task)의 상태. 개발문서 3.3.1 / 2.4.1
 *
 * <p>DB 에는 PostgreSQL Enum 이 아니라 varchar + CHECK 로 저장한다. (문서 2.5)
 * 허용되지 않은 상태 변경은 409 Conflict 로 거부하며, 상태 변경과 task_events 기록은
 * 같은 트랜잭션에서 수행한다. (문서 3.10)
 */
public enum TaskStatus {

    /** 요청을 저장했으나 분석을 시작하지 않음 */
    CREATED,

    /** Slash·규칙·Kiwi 로 의도와 인자를 분석 중 */
    ANALYZING,

    /** 위치·기기·검색 루트 등 필수 인자가 부족함 */
    NEEDS_CLARIFICATION,

    /**
     * 실행하기 전에 사용자 확인을 기다림. (P0-C · 계획 문서 §1.5)
     *
     * <p>분석은 끝났고 무엇을 할지도 정해졌지만 <b>아직 아무것도 하지 않은</b> 상태다.
     * 승인하면 원래 가려던 경로로 이어지고, 거절하면 실패로, 답이 없으면 기한이 지나
     * 만료로 마감한다.
     */
    WAITING_FOR_APPROVAL,

    /** 대상 PC 연결 또는 READY 상태를 기다림 */
    WAITING_FOR_DEVICE,

    /** SQS 또는 Agent 전달 대기열에 접수됨 */
    QUEUED,

    /** 외부 API·AI Worker·Agent 가 실행 중 */
    RUNNING,

    /** 최종 결과 저장과 전달 성공 */
    SUCCEEDED,

    /** 재시도 후에도 실행 실패 */
    FAILED,

    /** 실행 기한 또는 사용자 응답 기한 만료 */
    EXPIRED;

    /** 더 이상 전이가 일어나지 않는 최종 상태. completed_at 이 채워져야 한다. (문서 2.7) */
    private static final Set<TaskStatus> TERMINAL =
            EnumSet.of(SUCCEEDED, FAILED, EXPIRED);

    /**
     * 허용 전이표. 문서 3.10 의 상태 전이도를 옮긴 것이다.
     *
     * <p>최종 상태(FAILED·EXPIRED)로의 전이는 어느 단계에서든 일어날 수 있어
     * 각 항목에 공통으로 포함한다.
     */
    private static final Map<TaskStatus, Set<TaskStatus>> ALLOWED = Map.of(
            CREATED,             EnumSet.of(ANALYZING, FAILED, EXPIRED),
            ANALYZING,           EnumSet.of(NEEDS_CLARIFICATION, WAITING_FOR_APPROVAL, WAITING_FOR_DEVICE,
                                            QUEUED, RUNNING, FAILED, EXPIRED),
            NEEDS_CLARIFICATION, EnumSet.of(ANALYZING, FAILED, EXPIRED),
            // 승인하면 원래 가려던 곳으로 이어진다. PC 가 꺼져 있으면 그 앞에서 기다린다.
            WAITING_FOR_APPROVAL, EnumSet.of(QUEUED, WAITING_FOR_DEVICE, FAILED, EXPIRED),
            WAITING_FOR_DEVICE,  EnumSet.of(QUEUED, FAILED, EXPIRED),
            QUEUED,              EnumSet.of(RUNNING, FAILED, EXPIRED),
            RUNNING,             EnumSet.of(SUCCEEDED, FAILED, EXPIRED),
            SUCCEEDED,           EnumSet.noneOf(TaskStatus.class),
            FAILED,              EnumSet.noneOf(TaskStatus.class),
            EXPIRED,             EnumSet.noneOf(TaskStatus.class)
    );

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** this 에서 next 로 전이할 수 있는지 확인한다. */
    public boolean canTransitionTo(TaskStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
