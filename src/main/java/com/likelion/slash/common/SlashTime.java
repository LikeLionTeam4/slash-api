package com.likelion.slash.common;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 프로젝트 공통 시각 기준. 메시지 프로토콜 정의 3.5 · 11.3
 *
 * <p>API·WSS·SQS·DB Session·SQL 조회·화면을 모두 한국 시각으로 통일한다.
 *
 * <p>{@link java.time.Instant} 는 오프셋 개념이 없어 항상 {@code Z} 로 직렬화되므로
 * 계약이 요구하는 {@code +09:00} 표기를 만들 수 없다.
 * 시각을 주고받을 때는 {@link OffsetDateTime} 을 사용한다.
 * (jOOQ 도 timestamptz 를 OffsetDateTime 으로 생성한다)
 */
public final class SlashTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private SlashTime() {
    }

    /** 한국 시각 기준 현재 시각. */
    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZONE);
    }
}
