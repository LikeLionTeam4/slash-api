package com.likelion.slash.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pod 간 WSS 이벤트 구독이 걸릴 때까지 뒤에서 다시 시도한다. (이슈 #36)
 *
 * <p>{@link RedisMessageListenerContainer} 의 자동 시작은 꺼 두었다
 * ({@link com.likelion.slash.config.WsPubSubConfig}). 켜 두면 Valkey 에 닿지 못할 때
 * 컨텍스트 시작이 그대로 실패해 애플리케이션 전체가 뜨지 못하기 때문이다. 대신 여기서
 * 붙을 때까지 시도한다. 그동안 API·WSS·스윕은 정상으로 돌고, 발행만 조용히 버려진다.
 *
 * <p><b>{@code isListening()} 으로 판정한다.</b> 시작에 실패해도 {@code isRunning()} 은
 * {@code true} 가 되어 버려서 그 값으로는 실패를 알 수 없다. 실측한 동작은 이렇다.
 *
 * <pre>
 * start() 실패      → running=true,  listening=false
 * start() 다시 호출  → 아무 일도 없음 (이미 running 이라 무시)
 * stop() 뒤 start() → running=true,  listening=true
 * </pre>
 *
 * <p>그래서 다시 시도할 때는 {@link RedisMessageListenerContainer#stop()} 을 먼저 부른다.
 *
 * <p><b>붙은 뒤에는 개입하지 않는다.</b> 구독이 한 번 걸리면 연결이 끊겨도
 * {@code isListening()} 은 {@code true} 로 남는다. 그 구간의 재연결은 Lettuce 와 컨테이너의
 * 자체 recovery 가 맡는 몫이고, 여기가 끼어들면 오히려 그것을 끊는다.
 */
@Component
public class WsSubscriptionStarter {

    private static final Logger log = LoggerFactory.getLogger(WsSubscriptionStarter.class);

    private final RedisMessageListenerContainer container;

    /**
     * 실패를 한 번 알린 뒤에는 붙을 때까지 조용히 있는다. 회차마다 같은 오류를 쌓지 않는다.
     *
     * <p>{@code volatile} 인 이유 — 회차가 겹치지는 않지만 <b>매번 같은 스레드가 아니다.</b>
     * 스케줄러 풀에서 그때그때 다른 스레드가 집어 간다(실측: 실패는 {@code scheduling-3},
     * 성공은 {@code scheduling-1}). 값이 스레드 사이에 보이지 않으면 막으려던 로그가 그대로 쌓인다.
     */
    private volatile boolean failureReported;

    public WsSubscriptionStarter(RedisMessageListenerContainer wsMessageListenerContainer) {
        this.container = wsMessageListenerContainer;
    }

    /**
     * 밀리초 단위로 받는 이유는 다른 주기 작업과 같다 — {@code @Scheduled} 의 문자열 값은
     * 설정 파일의 {@code 5s} 표기를 그대로 해석하지 못한다.
     *
     * <p>{@code initialDelayString} 을 0 으로 두어 기동하자마자 한 번 시도한다. 첫 회차를
     * 기다리면 그 시간만큼 이벤트가 통째로 버려진다.
     */
    @Scheduled(
            fixedDelayString = "${slash.websocket.subscribe-retry.interval-ms}",
            initialDelayString = "0")
    public void subscribeUntilConnected() {
        if (container.isListening()) {
            return;
        }

        try {
            // 실패한 컨테이너는 running 인 채로 남아 start() 를 무시한다. 먼저 내려야 다시 붙는다.
            if (container.isRunning()) {
                container.stop();
            }
            container.start();

            if (container.isListening()) {
                log.info("Pod 간 WSS 이벤트 구독을 시작했다");
                failureReported = false;
            }

        } catch (Exception e) {
            // 붙지 못한 것은 앱을 멈출 사유가 아니다. 다음 회차가 다시 시도한다.
            if (!failureReported) {
                log.error("Pod 간 WSS 이벤트 구독 실패 — 붙을 때까지 다시 시도한다: {}", e.getMessage());
                failureReported = true;
            }
        }
    }
}
