package com.likelion.slash.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@link ClientAddressResolver} 확인. (WBS W1-02)
 *
 * <p>이 판정이 틀리면 시도 횟수 제한이 통째로 무력화된다.
 * 등록 코드는 6자리뿐이라 그 제한이 유일한 방어선이므로 경계마다 고정해 둔다.
 */
class ClientAddressResolverTest {

    private static final String SOCKET_ADDRESS = "10.0.0.5";

    private MockHttpServletRequest 요청(String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(SOCKET_ADDRESS);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    // -----------------------------------------------------------------------
    // 프록시 없음 (로컬·시험 기본값)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("신뢰할 프록시가 없으면 헤더를 아예 보지 않는다")
    void 프록시가_없으면_소켓_주소를_쓴다() {
        ClientAddressResolver resolver = new ClientAddressResolver(0);

        // 헤더에 뭘 넣든 판정이 흔들리지 않아야 한다.
        assertThat(resolver.resolve(요청("1.1.1.1"))).isEqualTo(SOCKET_ADDRESS);
        assertThat(resolver.resolve(요청("1.1.1.1, 2.2.2.2"))).isEqualTo(SOCKET_ADDRESS);
        assertThat(resolver.resolve(요청(null))).isEqualTo(SOCKET_ADDRESS);
    }

    // -----------------------------------------------------------------------
    // 프록시 1개 (ALB 만 있는 구성)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("프록시가 하나면 맨 뒤 값을 쓴다")
    void 프록시가_하나면_맨_뒤를_쓴다() {
        ClientAddressResolver resolver = new ClientAddressResolver(1);

        // ALB 는 클라이언트가 보낸 값을 덮어쓰지 않고 뒤에 실제 주소를 이어 붙인다.
        assertThat(resolver.resolve(요청("203.0.113.9"))).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve(요청("1.1.1.1, 203.0.113.9"))).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("클라이언트가 앞에 지어낸 값은 판정을 바꾸지 못한다")
    void 위조된_앞부분은_무시된다() {
        ClientAddressResolver resolver = new ClientAddressResolver(1);

        // 공격자가 요청마다 앞 값을 바꿔도 판정은 같은 주소로 고정된다.
        // 여기가 흔들리면 매번 새 버킷이 잡혀 시도 횟수가 1 에서 오르지 않는다.
        String first = resolver.resolve(요청("9.9.9.9, 203.0.113.9"));
        String second = resolver.resolve(요청("8.8.8.8, 203.0.113.9"));
        String third = resolver.resolve(요청("7.7.7.7, 6.6.6.6, 203.0.113.9"));

        assertThat(first).isEqualTo("203.0.113.9");
        assertThat(second).isEqualTo("203.0.113.9");
        assertThat(third).isEqualTo("203.0.113.9");
    }

    // -----------------------------------------------------------------------
    // 프록시 2개 (ALB → Ingress Controller)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("프록시가 둘이면 뒤에서 두 번째 값을 쓴다")
    void 프록시가_둘이면_뒤에서_두_번째를_쓴다() {
        ClientAddressResolver resolver = new ClientAddressResolver(2);

        // 클라이언트가 보냄 → ALB 가 실제 주소를 붙임 → Ingress 가 ALB 주소를 붙임
        assertThat(resolver.resolve(요청("1.1.1.1, 203.0.113.9, 10.0.1.20")))
                .isEqualTo("203.0.113.9");
    }

    // -----------------------------------------------------------------------
    // 헤더가 기대와 다른 경우
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("헤더가 없으면 소켓 주소로 돌아간다")
    void 헤더가_없으면_소켓_주소로_돌아간다() {
        ClientAddressResolver resolver = new ClientAddressResolver(1);

        assertThat(resolver.resolve(요청(null))).isEqualTo(SOCKET_ADDRESS);
        assertThat(resolver.resolve(요청("   "))).isEqualTo(SOCKET_ADDRESS);
    }

    @Test
    @DisplayName("헤더가 기대보다 짧으면 소켓 주소로 돌아간다")
    void 헤더가_짧으면_소켓_주소로_돌아간다() {
        // 프록시 둘을 기대하는데 값이 하나뿐이다. 남은 값은 신뢰 구간 밖이라 쓰지 않는다.
        ClientAddressResolver resolver = new ClientAddressResolver(2);

        assertThat(resolver.resolve(요청("1.1.1.1"))).isEqualTo(SOCKET_ADDRESS);
    }

    @Test
    @DisplayName("빈 항목이 섞여 있으면 소켓 주소로 돌아간다")
    void 빈_항목은_소켓_주소로_돌아간다() {
        ClientAddressResolver resolver = new ClientAddressResolver(1);

        assertThat(resolver.resolve(요청("1.1.1.1,   "))).isEqualTo(SOCKET_ADDRESS);
    }

    @Test
    @DisplayName("홉 수를 음수로 설정하면 기동하지 않는다")
    void 음수_홉은_거부된다() {
        // 조용히 0 으로 떨어뜨리면 잘못된 설정을 배포하고도 모른다.
        assertThatThrownBy(() -> new ClientAddressResolver(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
