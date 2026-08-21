package com.likelion.slash.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업 활성화.
 *
 * <p>지금 도는 것은 여덟 가지이고, 그중 <b>둘은 요약을 GPU 로 할 때만</b> 만들어진다
 * ({@code slash.summary.engine=GEMMA}). 기본값인 CPU 추출 요약에서는 여섯 가지가 돈다.
 * <ul>
 *   <li>{@link com.likelion.slash.dispatch.PendingDispatchSweeper} — 미전달 작업 재발행</li>
 *   <li>{@link com.likelion.slash.dispatch.DispatchExpirySweeper} — 기한이 지난 전달 마감</li>
 *   <li>{@link com.likelion.slash.task.StaleTaskSweeper} — 전달이 붙지 않은 채 남은 작업 마감</li>
 *   <li>{@link com.likelion.slash.device.DeviceOfflineSweeper} — Heartbeat 가 끊긴 기기 내리기</li>
 *   <li>{@link com.likelion.slash.pairing.PairingRequestSweeper} — 기한이 지난 등록 요청 정리</li>
 *   <li>{@link com.likelion.slash.ws.WsSubscriptionStarter} — Pod 간 이벤트 구독 재시도</li>
 *   <li>{@link com.likelion.slash.llm.LlmJobSweeper} — 시작되지 못한 요약 재실행·기한 마감
 *       <b>(GPU 요약 전용)</b></li>
 *   <li>{@link com.likelion.slash.llm.LlmReadiness} — 요약 모델이 작업을 받을 수 있는지 확인
 *       <b>(GPU 요약 전용)</b></li>
 * </ul>
 *
 * <p><b>모든 Pod 이 동시에 돈다.</b> 주기 작업은 Pod 마다 따로 실행되므로,
 * 여기에 붙는 작업은 여러 Pod 이 같은 대상을 집어도 안전해야 한다.
 * 재발행 스윕은 전달마다 Valkey 짧은 잠금을 잡아 한 Pod 만 발행하도록 한다.
 *
 * <p>스레드 수는 {@code spring.task.scheduling.pool.size} 로 둔다. 기본값 1 이면
 * 한 작업이 늦어질 때 나머지가 모두 그 뒤에 선다.
 *
 * <p><b>여기 붙는 작업은 스레드를 오래 붙들지 않아야 한다.</b> 요약 스윕이 모델 호출을
 * 기다리지 않고 {@code @Async} 로 넘기는 것도 그래서다. (PR #42 리뷰)
 *
 * <p><b>시험에서는 끈다.</b> ({@code slash.scheduling.enabled=false} · 이슈 #31)
 * 켜 두면 시험이 도는 동안 스윕이 함께 돌면서 무관한 시험의 자료를 건드린다. 특히 만료 스윕은
 * 상태를 {@code EXPIRED} 로 바꾸고 작업을 마감하므로, 커밋된 자료가 남아 있으면 다른 시험이
 * 그 영향을 받고 행 잠금 경쟁도 생긴다. 스윕 자체를 확인하는 시험은 모두 메서드를 직접
 * 부르므로 꺼도 영향이 없다.
 *
 * <p>{@code matchIfMissing = true} 로 둔다. 설정이 없으면 켜지므로 운영에서 실수로 꺼질 일이 없다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(value = "slash.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
