package com.likelion.slash.ws;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentSignatureVerifier} 확인. (WBS W1-06)
 *
 * <p>Agent 구현이 공개키를 어떤 표기로 보내든 등록이 막히지 않아야 하므로
 * X.509 와 원시 32바이트를 모두 확인한다.
 */
class AgentSignatureVerifierTest {

    private static final int RAW_KEY_LENGTH = 32;

    private final AgentSignatureVerifier verifier = new AgentSignatureVerifier();

    private final byte[] 도전값 = "nonce-0123456789-0123456789-0123".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("X.509 표기 공개키로 서명을 검증한다")
    void x509_표기() throws Exception {
        KeyPair keyPair = 키쌍();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        assertThat(verifier.verify(publicKey, 도전값, 서명(keyPair, 도전값))).isTrue();
    }

    @Test
    @DisplayName("원시 32바이트 표기 공개키로도 서명을 검증한다")
    void 원시_표기() throws Exception {
        KeyPair keyPair = 키쌍();

        // X.509 SubjectPublicKeyInfo 의 마지막 32바이트가 원시 공개키다.
        byte[] encoded = keyPair.getPublic().getEncoded();
        byte[] raw = Arrays.copyOfRange(encoded, encoded.length - RAW_KEY_LENGTH, encoded.length);

        String publicKey = Base64.getEncoder().encodeToString(raw);

        assertThat(verifier.verify(publicKey, 도전값, 서명(keyPair, 도전값))).isTrue();
    }

    @Test
    @DisplayName("다른 키로 만든 서명은 거부한다")
    void 다른_키의_서명() throws Exception {
        KeyPair 등록된_기기 = 키쌍();
        KeyPair 공격자 = 키쌍();

        String publicKey = Base64.getEncoder().encodeToString(등록된_기기.getPublic().getEncoded());

        assertThat(verifier.verify(publicKey, 도전값, 서명(공격자, 도전값))).isFalse();
    }

    @Test
    @DisplayName("다른 도전값에 대한 서명은 거부한다 — 지난 연결의 값을 재생할 수 없다")
    void 다른_도전값의_서명() throws Exception {
        KeyPair keyPair = 키쌍();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

        byte[] 지난_도전값 = "nonce-9876543210-9876543210-9876".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(publicKey, 도전값, 서명(keyPair, 지난_도전값))).isFalse();
    }

    @Test
    @DisplayName("형식이 깨진 값은 예외 대신 실패로 다룬다")
    void 형식_오류() {
        assertThat(verifier.verify("공개키가-아님", 도전값, "서명도-아님")).isFalse();
    }

    private static KeyPair 키쌍() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static String 서명(KeyPair keyPair, byte[] payload) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(payload);
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
