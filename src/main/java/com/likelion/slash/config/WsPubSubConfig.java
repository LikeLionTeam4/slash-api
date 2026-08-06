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
 * <p>Valkey 가 끊겨도 컨테이너가 주기적으로 재구독하므로 애플리케이션은 계속 뜬다.
 * 끊긴 동안의 이벤트는 유실되고, 전달 원장의 스윕이 복구한다.
 */
@Configuration
public class WsPubSubConfig {

    @Bean
    RedisMessageListenerContainer wsMessageListenerContainer(
            RedisConnectionFactory connectionFactory, WsMessageListener listener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        List<Topic> topics = Arrays.stream(WsTarget.values())
                .map(target -> (Topic) new ChannelTopic(target.channel()))
                .toList();

        container.addMessageListener(listener, topics);
        return container;
    }
}
