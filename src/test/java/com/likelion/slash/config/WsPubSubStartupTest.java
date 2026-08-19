package com.likelion.slash.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.health.DependencyHealthService;
import com.likelion.slash.health.dto.DependencyHealthResponse.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Valkey 에 닿지 못해도 애플리케이션이 뜨는지 확인. (이슈 #36)
 *
 * <p>Pub/Sub 은 화면을 빠르게 바꾸기 위한 부가 채널이다. 최종 상태는 REST 가 답하고
 * 놓친 전달은 원장과 스윕이 복구하므로, 그 채널 하나 때문에 API·WSS·스윕까지 함께
 * 멈춰서는 안 된다. 실제로 dev 에서 Valkey 접속이 어긋나 Pod 이 크래시루프에 빠졌다.
 *
 * <p>아무도 듣지 않는 포트를 Valkey 로 주어 그 상황을 만든다.
 */
@SpringBootTest(properties = "spring.data.redis.port=6398")
class WsPubSubStartupTest {

    @Autowired
    private RedisMessageListenerContainer container;

    @Autowired
    private DependencyHealthService healthService;

    @Test
    @DisplayName("Valkey 가 없어도 컨텍스트는 뜨고, 구독만 걸리지 않는다")
    void 발키_없이도_뜬다() {
        // 여기까지 왔다는 것이 곧 컨텍스트가 떴다는 뜻이다.
        assertThat(container.isListening()).isFalse();
    }

    @Test
    @DisplayName("Valkey 가 없는 것은 헬스체크가 DOWN 으로 알린다")
    void 상태로_드러난다() {
        assertThat(healthService.check().valkey()).isEqualTo(Status.DOWN);
    }
}
