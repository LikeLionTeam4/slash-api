package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 사용자 WSS 접속표 보관소 확인. (WBS W1-06)
 *
 * <p>Valkey 가 떠 있어야 한다. ({@code docker compose up -d})
 *
 * <p>여기서 보는 것은 <b>표가 정말 1회용인가</b>다. 재사용을 막지 못하면 접근 로그에 남은
 * URL 을 주운 쪽이 유효 시간 안에 여러 번 붙을 수 있고, 표를 짧게 두는 의미가 사라진다.
 */
@SpringBootTest
class UserWsTicketStoreTest {

    /** 다른 시험과 겹치지 않도록 실행마다 다른 사용자를 쓴다. */
    private final long 사용자 = System.nanoTime();

    @Autowired
    private UserWsTicketStore store;

    @Test
    @DisplayName("발급한 표로 주인을 찾는다")
    void 발급하고_쓴다() {
        String ticket = store.issue(사용자);

        assertThat(store.consume(ticket)).contains(사용자);
    }

    @Test
    @DisplayName("표는 1회용이다 — 두 번째는 통하지 않는다")
    void 표는_한_번만_쓴다() {
        String ticket = store.issue(사용자);
        store.consume(ticket);

        // 이걸 막지 못하면 접근 로그에서 주운 URL 로 유효 시간 안에 몇 번이든 붙을 수 있다.
        assertThat(store.consume(ticket)).isEmpty();
    }

    @Test
    @DisplayName("모르는 표와 빈 값은 조용히 거절한다")
    void 모르는_표는_거절한다() {
        assertThat(store.consume("없는-표")).isEmpty();
        assertThat(store.consume("")).isEmpty();
        assertThat(store.consume(null)).isEmpty();
    }

    @Test
    @DisplayName("표마다 값이 다르다")
    void 표는_매번_새로_만든다() {
        assertThat(store.issue(사용자)).isNotEqualTo(store.issue(사용자));
    }

    @Test
    @DisplayName("발급한 원문은 Valkey 에 그대로 남지 않는다")
    void 원문을_저장하지_않는다(@Autowired org.springframework.data.redis.core.StringRedisTemplate redis) {
        String ticket = store.issue(사용자);

        // 해시를 키로 쓴다. Valkey 가 통째로 새더라도 그것만으로는 접속할 수 없다.
        assertThat(redis.hasKey("ws:ticket:" + ticket)).isFalse();
        assertThat(store.consume(ticket)).contains(사용자);
    }
}
