package com.likelion.slash.ws;

import com.likelion.slash.common.enums.TaskStatus;
import com.likelion.slash.ws.dto.TaskResultAvailableEvent;
import com.likelion.slash.ws.dto.TaskStatusChangedEvent;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 브라우저에게 보낼 알림을 발행한다. (WBS W1-06)
 *
 * <p>{@link WsMessagePublisher} 를 그대로 쓰되 <b>커밋된 뒤에 보내는 것</b>을 책임진다.
 *
 * <p><b>왜 커밋을 기다리는가</b> — 상태 전이는 트랜잭션 안에서 일어나고 롤백될 수 있다.
 * 트랜잭션 안에서 그대로 발행하면 <b>일어나지 않은 전이가 화면에 뜬다.</b> 사용자는 작업이
 * 진행되는 것을 보다가 새로고침하면 이전 상태로 돌아가 있는 것을 보게 된다. REST 가 진실
 * 소스라 결국은 바로잡히지만, 잘못된 화면을 한 번 보여준 뒤에 고치는 것과 처음부터 맞는 것은
 * 다르다.
 *
 * <p>트랜잭션 밖에서 부르면 곧바로 발행한다. Agent 프레임 처리처럼 트랜잭션이 없는 경로도
 * 같은 메서드를 쓸 수 있게 하기 위해서다.
 *
 * <p><b>전달을 보장하지 않는다.</b> 연결이 없거나 Pod 이 재시작 중이면 그냥 사라진다.
 * 이 채널은 화면을 빠르게 바꾸기 위한 것이고 최종 상태는 REST 가 답한다.
 * (docs/frontend-api-contract.md §7)
 */
@Component
public class UserEventPublisher {

    private final WsMessagePublisher publisher;

    public UserEventPublisher(WsMessagePublisher publisher) {
        this.publisher = publisher;
    }

    /** 작업 상태가 바뀌었음을 알린다. */
    public void taskStatusChanged(long userId, UUID taskPublicId, TaskStatus from, TaskStatus to) {
        afterCommit(userId, TaskStatusChangedEvent.of(taskPublicId, from, to));
    }

    /** 작업이 끝나 결과를 조회할 수 있음을 알린다. */
    public void taskResultAvailable(long userId, UUID taskPublicId, TaskStatus status, JSONB result) {
        afterCommit(userId, TaskResultAvailableEvent.of(taskPublicId, status, result));
    }

    private void afterCommit(long userId, Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publisher.send(WsTarget.USER, userId, event);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.send(WsTarget.USER, userId, event);
            }
        });
    }
}
