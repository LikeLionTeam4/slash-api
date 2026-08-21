package com.likelion.slash.approval;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.jooq.tables.records.TaskApprovalsRecord;
import com.likelion.slash.task.TaskStateWriter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 답이 없는 승인 요청을 마감한다. (P0-C · 계획 문서 §1.5)
 *
 * <p><b>{@code StaleTaskSweeper} 가 있는데도 두는 이유.</b> 그쪽은 접수 후 30분이 지난
 * 모든 작업을 만료로 잡는다. 승인 기한은 그보다 짧으므로(기본 10분), 그것만 믿으면 기한이
 * 지난 뒤에도 한참을 승인할 수 있는 것처럼 보인다. <b>사용자가 잊은 요청이 20분 뒤에
 * 실행되는 것</b>이 이 스윕이 막는 일이다.
 *
 * <p>승인 원장과 작업을 한 트랜잭션에서 닫는다. 나눠 두면 그 사이에 Pod 이 내려갔을 때
 * 승인만 만료되고 작업은 기다리는 상태로 남는데, 그 작업은 승인할 방법이 없어진다.
 */
@Component
public class ApprovalExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ApprovalExpirySweeper.class);

    private final TaskApprovalRepository approvalRepository;
    private final TaskStateWriter stateWriter;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;

    public ApprovalExpirySweeper(TaskApprovalRepository approvalRepository,
                                 TaskStateWriter stateWriter,
                                 TransactionTemplate transactionTemplate,
                                 @Value("${slash.approval.expiry-sweep.batch-size}") int batchSize) {
        this.approvalRepository = approvalRepository;
        this.stateWriter = stateWriter;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = batchSize;
    }

    /**
     * 밀리초 단위로 받는 이유는 다른 주기 작업과 같다 — {@code @Scheduled} 의 문자열 값은
     * 설정 파일의 {@code 30s} 표기를 그대로 해석하지 못한다.
     */
    @Scheduled(
            fixedDelayString = "${slash.approval.expiry-sweep.interval-ms}",
            initialDelayString = "${slash.approval.expiry-sweep.interval-ms}")
    public void expireOverdue() {
        try {
            List<TaskApprovalsRecord> overdue =
                    approvalRepository.findOverdue(SlashTime.now(), batchSize);

            if (overdue.isEmpty()) {
                return;
            }

            transactionTemplate.executeWithoutResult(status -> {
                // 조회한 것만 마감한다. 조건으로 한 번에 갱신하면 배치를 넘긴 것까지 만료되는데
                // 그 작업은 여기서 닫지 못해 승인할 수도 실행할 수도 없는 채로 남는다.
                approvalRepository.expire(overdue.stream().map(TaskApprovalsRecord::getId).toList());

                for (TaskApprovalsRecord approval : overdue) {
                    stateWriter.expire(approval.getTaskId(), TaskStatus.WAITING_FOR_APPROVAL,
                            "확인 기한이 지나 실행하지 않았습니다.");
                }
            });

            log.info("확인 기한이 지난 작업 {}건을 마감했다", overdue.size());

        } catch (Exception e) {
            // 한 회차의 실패로 스케줄이 멈추지 않게 한다. 다음 회차가 같은 일을 다시 시도한다.
            log.error("승인 만료 스윕 실패: {}", e.getMessage());
        }
    }
}
