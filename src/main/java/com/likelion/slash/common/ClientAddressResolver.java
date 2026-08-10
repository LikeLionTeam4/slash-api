package com.likelion.slash.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 요청을 보낸 클라이언트의 주소.
 *
 * <p>시도 횟수 제한처럼 <b>"같은 사람인지"</b> 를 판정하는 곳에서 쓴다.
 *
 * <p><b>왜 {@code X-Forwarded-For} 의 첫 값을 쓰면 안 되는가</b> — 이 헤더는 클라이언트가
 * 직접 채워 보낼 수 있고, AWS ALB 는 기존 값을 덮어쓰지 않고 <b>뒤에 이어 붙인다</b>.
 * ({@code routing.http.xff_header_processing.mode} 기본값 {@code append})
 * 그래서 클라이언트가 보낸 값이 앞에 그대로 남는다.
 *
 * <pre>
 * 클라이언트가 보냄:  X-Forwarded-For: 1.1.1.1
 * ALB 를 지난 뒤:     X-Forwarded-For: 1.1.1.1, 203.0.113.9   ← 뒤가 실제 주소
 * </pre>
 *
 * 첫 값을 쓰면 요청마다 다른 값을 넣어 <b>매번 새 버킷으로 잡히게</b> 할 수 있다.
 * 카운터가 1 에서 더 오르지 않으므로 시도 횟수 제한이 통째로 무력화된다.
 * 한도를 늘리는 정도가 아니라 없애는 것이다.
 *
 * <p>그래서 <b>오른쪽에서부터</b> 센다. 우리 앞에 신뢰할 수 있는 프록시가 N 개 있다면
 * 오른쪽에서 N 번째 값이 그 프록시들이 실제로 관측한 주소다. 그보다 왼쪽은 전부
 * 클라이언트가 지어낼 수 있는 영역이다.
 *
 * <p>{@code slash.trusted-proxy-hops} 가 0 이면 헤더를 아예 보지 않고 소켓 주소를 쓴다.
 * 설정을 빠뜨렸을 때 안전한 쪽으로 실패하도록 기본값을 0 으로 둔다.
 * 로컬·시험이 여기 해당한다.
 *
 * <p>배포 환경의 홉 수는 인프라 구성에 달려 있다. ALB 만 있으면 1,
 * ALB 뒤에 Ingress Controller 가 한 번 더 있으면 2 다.
 */
@Component
public class ClientAddressResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    private final int trustedProxyHops;

    public ClientAddressResolver(@Value("${slash.trusted-proxy-hops}") int trustedProxyHops) {
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException(
                    "slash.trusted-proxy-hops 는 0 이상이어야 합니다: " + trustedProxyHops);
        }
        this.trustedProxyHops = trustedProxyHops;
    }

    /**
     * 호출자 주소를 판정한다.
     *
     * <p>신뢰 구간을 벗어난 값은 쓰지 않는다. 헤더가 짧거나 비어 있으면 소켓 주소로 돌아간다.
     * 위조된 헤더로 판정을 흔들 수 있는 경로를 남기지 않는 쪽이 중요하다.
     */
    public String resolve(HttpServletRequest request) {
        if (trustedProxyHops == 0) {
            return request.getRemoteAddr();
        }

        String header = request.getHeader(FORWARDED_FOR);
        if (header == null || header.isBlank()) {
            return request.getRemoteAddr();
        }

        String[] hops = header.split(",");

        // 오른쪽에서 trustedProxyHops 번째. 마지막 값은 우리 바로 앞 프록시가 관측한 주소다.
        int index = hops.length - trustedProxyHops;
        if (index < 0) {
            // 헤더가 기대보다 짧다. 클라이언트가 헤더를 통째로 지운 경우가 여기에 해당한다.
            // 남은 값 중 가장 오른쪽이 그나마 신뢰도가 높지만, 그마저도 위조 영역일 수 있어
            // 소켓 주소로 돌아간다.
            return request.getRemoteAddr();
        }

        String address = hops[index].trim();
        return address.isEmpty() ? request.getRemoteAddr() : address;
    }
}
