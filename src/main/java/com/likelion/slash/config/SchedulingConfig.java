package com.likelion.slash.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업 활성화.
 *
 * <p>지금 도는 것은 일곱 가지다.
 * <ul>
 *   <li>{@link com.likelion.slash.dispatch.PendingDispatchSweeper} — 미전달 작업 재발행</li>
 *   <li>{@link com.likelion.slash.dispatch.DispatchExpirySweeper} — 기한이 지난 전달 마감</li>
 *   <li>{@link com.likelion.slash.task.StaleTaskSweeper} — 전달이 붙지 않은 채 남은 작업 마감</li>
 *   <li>{@link com.likelion.slash.device.DeviceOfflineSweeper} — Heartbeat 가 끊긴 기기 내리기</li>
 *   <li>{@link com.likelion.slash.ws.WsSubscriptionStarter} — Pod 간 이벤트 구독 재시도</li>
 *   <li>{@link com.likelion.slash.llm.LlmJobSweeper} — 시작되지 못한 요약 재실행·기한 마감</li>
 *   <li>{@link com.likelion.slash.llm.LlmReadiness} — 요약 모델이 작업을 받을 수 있는지 확인</li>
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
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
