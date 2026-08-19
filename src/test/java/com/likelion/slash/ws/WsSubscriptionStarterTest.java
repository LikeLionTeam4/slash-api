package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 늦게 붙는 Valkey 에 구독이 걸리는지 확인. (이슈 #36)
 *
 * <p>Valkey 를 직접 껐다 켜는 대신 <b>중계 소켓</b>을 하나 두고 그것을 열고 닫는다.
 * 컨테이너는 중계 포트를 Valkey 로 알고, 중계가 닫혀 있는 동안은 접속이 거부된다.
 * Valkey 컨테이너를 시험 도중에 조작하면 같은 Valkey 를 쓰는 다른 시험이 함께 넘어진다.
 *
 * <p>실제 Valkey 가 떠 있어야 한다. ({@code docker compose up -d})
 */
class WsSubscriptionStarterTest {

    /** 중계가 듣는 포트. 컨테이너는 여기를 Valkey 로 안다. */
    private static final int 중계_포트 = 6399;

    /** docker compose 의 Valkey. */
    private static final int 실제_포트 = 6379;

    /** 다른 시험과 겹치지 않을 채널. */
    private static final String 채널 = "시험용-늦게-붙는-구독";

    private final CountDownLatch 받았다 = new CountDownLatch(1);
    private final List<Socket> 열린_소켓 = new CopyOnWriteArrayList<>();
    private volatile ServerSocket 중계;
    private RedisMessageListenerContainer container;
    private LettuceConnectionFactory connectionFactory;

    @AfterEach
    void tearDown() throws IOException {
        if (container != null) {
            container.stop();
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        중계를_닫는다();
    }

    @Test
    @DisplayName("Valkey 에 닿지 못해도 예외를 밖으로 내지 않는다")
    void 붙지_못해도_던지지_않는다() {
        WsSubscriptionStarter starter = new WsSubscriptionStarter(컨테이너를_만든다());

        assertThatCode(starter::subscribeUntilConnected).doesNotThrowAnyException();
        assertThat(container.isListening()).isFalse();
    }

    @Test
    @DisplayName("Valkey 가 늦게 올라오면 그때 구독이 걸린다")
    void 늦게_붙는다() throws Exception {
        WsSubscriptionStarter starter = new WsSubscriptionStarter(컨테이너를_만든다());

        starter.subscribeUntilConnected();
        assertThat(container.isListening()).isFalse();

        중계를_연다();
        starter.subscribeUntilConnected();

        assertThat(container.isListening()).isTrue();

        // isListening() 이 참이어도 구독이 실제로 걸렸다는 뜻은 아니다. 한 건 흘려 본다.
        new StringRedisTemplate(connectionFactory).convertAndSend(채널, "안녕");

        assertThat(받았다.await(5, TimeUnit.SECONDS))
                .as("늦게 붙은 구독으로 메시지가 들어와야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("이미 구독 중이면 건드리지 않는다")
    void 붙은_뒤에는_개입하지_않는다() throws Exception {
        중계를_연다();
        WsSubscriptionStarter starter = new WsSubscriptionStarter(컨테이너를_만든다());
        starter.subscribeUntilConnected();
        assertThat(container.isListening()).isTrue();

        // 다시 불러도 stop() 으로 끊어 놓지 않는다. 끊기면 그 사이 이벤트가 통째로 사라진다.
        starter.subscribeUntilConnected();

        assertThat(container.isListening()).isTrue();
    }

    private RedisMessageListenerContainer 컨테이너를_만든다() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 중계_포트));
        connectionFactory.afterPropertiesSet();

        container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> 받았다.countDown(), List.of(new ChannelTopic(채널)));
        container.afterPropertiesSet();
        return container;
    }

    /**
     * 중계 포트로 들어온 연결을 실제 Valkey 로 이어 준다.
     *
     * <p><b>{@code SO_REUSEADDR} 을 켜는 이유</b> — 앞 시험이 닫은 포트가 {@code TIME_WAIT} 로
     * 남아 있으면 같은 포트를 다시 열 수 없다. macOS 는 그냥 열어 주지만 Linux 는 거절해서
     * CI 에서만 {@code BindException} 으로 깨졌다. 시험끼리 같은 포트를 이어 쓰는 구조라
     * 명시적으로 켠다.
     */
    private void 중계를_연다() throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", 중계_포트));
        중계 = server;

        Thread accepting = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket 들어온_연결 = server.accept();
                    Socket 나가는_연결 = new Socket("127.0.0.1", 실제_포트);
                    열린_소켓.add(들어온_연결);
                    열린_소켓.add(나가는_연결);
                    이어_붓는다(들어온_연결, 나가는_연결);
                    이어_붓는다(나가는_연결, 들어온_연결);
                } catch (IOException e) {
                    return;
                }
            }
        });
        accepting.setDaemon(true);
        accepting.start();
    }

    private void 중계를_닫는다() throws IOException {
        if (중계 != null) {
            중계.close();
            중계 = null;
        }
        for (Socket socket : 열린_소켓) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 이미 닫혔으면 그만이다.
            }
        }
        열린_소켓.clear();
    }

    private void 이어_붓는다(Socket from, Socket to) {
        Thread pump = new Thread(() -> {
            try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                in.transferTo(out);
            } catch (IOException ignored) {
                // 어느 쪽이든 닫히면 끝난다.
            }
        });
        pump.setDaemon(true);
        pump.start();
    }
}
