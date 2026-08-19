package com.likelion.slash.config;

import com.likelion.slash.ws.WsMessageListener;
import com.likelion.slash.ws.WsTarget;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

/**
 * Pod 간 WSS 이벤트 전달 구독 설정. (WBS W1-06)
 *
 * <p>slash-api 는 최소 2 Pod 로 운영되는데 WSS 연결은 특정 Pod 에만 존재한다.
 * 이벤트를 전체 Pod 에 발행하고 연결 보유 Pod 만 내보내는 방식을 쓴다.
 * 선택지 비교와 설계는 {@code docs/w1-06-wss-routing.md} 에 있다. (2026-08-06 결정)
 *
 * <p><b>Valkey 를 대기열로 쓰는 것이 아니다.</b> 문서 3.8 이 금지하는 것은 작업을 쌓아 두고
 * 꺼내 가는 구조이고(그 역할은 SQS), Pub/Sub 은 쌓지 않는다. 저장·재시도 책임은
 * {@code agent_dispatches} 에 남고 여기는 신호만 옮긴다.
 *
 * <p><b>구독은 컨텍스트 시작과 함께 걸지 않는다.</b> {@link RedisMessageListenerContainer} 는
 * {@code SmartLifecycle} 이라 그냥 두면 시작 단계에서 구독을 시도하는데, 그때 Valkey 에
 * 닿지 못하면 예외가 그대로 올라가 <b>애플리케이션 전체가 뜨지 못한다.</b> Pub/Sub 은
 * 화면을 빠르게 바꾸기 위한 부가 채널일 뿐이고 최종 상태는 REST 가 답하므로
 * (docs/frontend-api-contract.md §7), 그것 하나 때문에 API·WSS·스윕까지 함께 멈추는 것은
 * 균형이 맞지 않는다. 그래서 자동 시작을 끄고 {@link com.likelion.slash.ws.WsSubscriptionStarter}
 * 가 붙을 때까지 뒤에서 다시 시도한다. (이슈 #36)
 *
 * <p>구독이 걸리지 않은 동안 발행은 조용히 실패하고({@link com.likelion.slash.ws.WsMessagePublisher}),
 * 그 사이 놓친 전달은 {@code agent_dispatches} 원장과 스윕이 복구한다.
 */
@Configuration
public class WsPubSubConfig {

    @Bean
    RedisMessageListenerContainer wsMessageListenerContainer(
            RedisConnectionFactory connectionFactory, WsMessageListener listener) {

        RedisMessageListenerContainer container = new ManualStartContainer();
        container.setConnectionFactory(connectionFactory);

        List<Topic> topics = Arrays.stream(WsTarget.values())
                .map(target -> (Topic) new ChannelTopic(target.channel()))
                .toList();

        container.addMessageListener(listener, topics);
        return container;
    }

    /**
     * 컨텍스트 시작과 함께 구독하지 않는 컨테이너.
     *
     * <p>{@code isAutoStartup()} 을 끄는 setter 가 없어 하위 클래스로 막는다.
     * (spring-data-redis 3.5) 익명 클래스로 두면 로그에 {@code WsPubSubConfig$1} 로 찍혀
     * 무엇이 말하는지 알아보기 어려워 이름을 준다.
     */
    private static class ManualStartContainer extends RedisMessageListenerContainer {

        @Override
        public boolean isAutoStartup() {
            return false;
        }
    }
}
