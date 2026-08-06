package com.likelion.slash.ws;

/**
 * WSS 프레임을 받을 대상의 종류.
 *
 * <p>Pod 간 발행 채널이 이 값으로 갈린다. 대상마다 연결 개수 성질이 달라서 한 채널로 합치지 않는다.
 *
 * <p>관련 문서: 3.4.2 · WBS W1-06
 */
public enum WsTarget {

    /** 로컬 에이전트. 기기 한 대당 연결은 한 개다. */
    DEVICE("slash:ws:device"),

    /** 사용자 브라우저. 탭마다 연결이 생기므로 한 사용자에 여러 개일 수 있다. */
    USER("slash:ws:user");

    private final String channel;

    WsTarget(String channel) {
        this.channel = channel;
    }

    /** 이 대상에게 보낼 이벤트를 발행하는 Valkey Pub/Sub 채널. */
    public String channel() {
        return channel;
    }
}
