package com.likelion.slash.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주기 작업 활성화.
 *
 * <p>지금 도는 것은 미전달 작업 재발행 스윕
 * ({@link com.likelion.slash.dispatch.PendingDispatchSweeper}) 하나다.
 *
 * <p><b>모든 Pod 이 동시에 돈다.</b> 주기 작업은 Pod 마다 따로 실행되므로,
 * 여기에 붙는 작업은 여러 Pod 이 같은 대상을 집어도 안전해야 한다.
 * 스윕은 전달마다 Valkey 짧은 잠금을 잡아 한 Pod 만 발행하도록 한다.
 *
 * <p>기기 Heartbeat 만료 처리와 전달 기한 만료 처리도 결국 여기에 붙어야 한다.
 * 둘 다 Task 상태 전이가 함께 필요해 W1-04 로 묶여 있다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
