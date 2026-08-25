package com.likelion.slash.task;

import java.time.OffsetDateTime;

/**
 * 작업을 만들면서 함께 선점할 멱등 키.
 *
 * <p>{@code Idempotency-Key} 헤더 값 하나로는 부족하다. 같은 키라도 경로가 다르면 다른
 * 요청이고({@code uk_idempotency_scope}), 같은 키에 다른 본문이 온 것을 가려내려면 본문
 * 해시가 함께 있어야 한다.
 *
 * @param key         {@code Idempotency-Key} 헤더 값
 * @param requestPath 키의 범위. 접수와 브라우저 결과 제출이 서로 다른 키 공간을 쓴다
 * @param requestHash 같은 키에 다른 본문이 왔는지 판별할 값
 * @param expiresAt   이 기록을 보존할 기한
 */
public record IdempotencyClaim(String key,
                               String requestPath,
                               String requestHash,
                               OffsetDateTime expiresAt) {
}
