package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentSignatureVerifier} 확인. (WBS W1-06)
 *
 * <p>서명 대상은 {@code challengeId:nonce:deviceId} <b>문자열</b>이다.
 * nonce 를 Base64 디코딩한 바이트에 서명하면 안 된다. 이 둘을 헷갈리면 인증이 전부 실패하는데
 * 로그에는 "검증 실패"로만 남아 원인을 찾기 어렵다. 그래서 여기서 못박아 둔다.
 *
 * <p>공개키 표기는 Agent 가 쓰는 원시 32바이트와 Java 가 내보내는 X.509 를 모두 받는다.
 */
class AgentSignatureVerifierTest {

    private static final int RAW_KEY_LENGTH = 32;

    private final AgentSignatureVerifier verifier = new AgentSignatureVerifier();

    private final UUID 도전값_ID = UUID.randomUUID();
    private final UUID 기기_공개ID = UUID.randomUUID();
    private final String nonce = Base64.getEncoder().encodeToString("임의값-32바이트-임의값-32바이트".getBytes(StandardCharsets.UTF_8));

    private final String 서명대상 = AgentProtocol.challengeSigningPayload(도전값_ID, nonce, 기기_공개ID);

    @Test
    @DisplayName("계약이 정한 문자열에 대한 서명을 검증한다")
    void 계약_문자열_서명() throws Exception {
        KeyPair keyPair = 키쌍();

        assertThat(verifier.verify(원시표기(keyPair), 서명대상, 서명(keyPair, 서명대상))).isTrue();
    }

    @Test
    @DisplayName("nonce 원본 바이트에 한 서명은 거부한다 — 계약은 문자열에 서명한다")
    void 원본_바이트_서명은_거부한다() throws Exception {
        KeyPair keyPair = 키쌍();

        String 잘못된_서명 = 서명바이트(keyPair, Base64.getDecoder().decode(nonce));

        assertThat(verifier.verify(원시표기(keyPair), 서명대상, 잘못된_서명)).isFalse();
    }

    @Test
    @DisplayName("X.509 표기 공개키로도 검증한다")
    void x509_표기() throws Exception {
        KeyPair keyPair = 키쌍();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        assertThat(verifier.verify(publicKey, 서명대상, 서명(keyPair, 서명대상))).isTrue();
    }

    @Test
    @DisplayName("다른 키로 만든 서명은 거부한다")
    void 다른_키의_서명() throws Exception {
        KeyPair 등록된_기기 = 키쌍();
        KeyPair 공격자 = 키쌍();

        assertThat(verifier.verify(원시표기(등록된_기기), 서명대상, 서명(공격자, 서명대상))).isFalse();
    }

    @Test
    @DisplayName("다른 도전값에 대한 서명은 거부한다 — 지난 연결의 값을 재생할 수 없다")
    void 다른_도전값의_서명() throws Exception {
        KeyPair keyPair = 키쌍();

        String 지난_서명대상 = AgentProtocol.challengeSigningPayload(UUID.randomUUID(), nonce, 기기_공개ID);

        assertThat(verifier.verify(원시표기(keyPair), 서명대상, 서명(keyPair, 지난_서명대상))).isFalse();
    }

    @Test
    @DisplayName("다른 기기의 서명은 거부한다 — 서명 대상에 deviceId 가 들어간다")
    void 다른_기기의_서명() throws Exception {
        KeyPair keyPair = 키쌍();

        String 남의_서명대상 = AgentProtocol.challengeSigningPayload(도전값_ID, nonce, UUID.randomUUID());

        assertThat(verifier.verify(원시표기(keyPair), 서명대상, 서명(keyPair, 남의_서명대상))).isFalse();
    }

    @Test
    @DisplayName("형식이 깨진 값은 예외 대신 실패로 다룬다")
    void 형식_오류() {
        assertThat(verifier.verify("공개키가-아님", 서명대상, "서명도-아님")).isFalse();
    }

    // ------------------------------------------------------------------

    private static KeyPair 키쌍() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** Agent 와 참조 구현이 쓰는 표기. X.509 의 마지막 32바이트가 원시 공개키다. */
    private static String 원시표기(KeyPair keyPair) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        return Base64.getEncoder()
                .encodeToString(Arrays.copyOfRange(encoded, encoded.length - RAW_KEY_LENGTH, encoded.length));
    }

    private static String 서명(KeyPair keyPair, String payload) throws Exception {
        return 서명바이트(keyPair, payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String 서명바이트(KeyPair keyPair, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(payload);
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
