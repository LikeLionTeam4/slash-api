package com.likelion.slash.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 비동기 실행 활성화.
 *
 * <p>지금 쓰는 것은 요약 실행({@link com.likelion.slash.llm.LlmSummaryRunner}) 하나다.
 * 모델이 생각하는 동안 {@code POST /api/v1/requests} 를 붙들지 않기 위해서다.
 *
 * <p><b>여기에 붙는 일은 잃어버려도 되는 것이어야 한다.</b> 스레드는 Pod 과 함께 사라진다.
 * 요약이 여기 붙을 수 있는 이유는 {@code async_jobs} 원장이 남고 스윕이 이어받기 때문이지,
 * 이 실행이 끝을 보장해서가 아니다.
 *
 * <p>스레드 수는 {@code spring.task.execution.pool} 로 둔다. 주기 작업의
 * {@code spring.task.scheduling.pool} 과는 다른 풀이다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
}
