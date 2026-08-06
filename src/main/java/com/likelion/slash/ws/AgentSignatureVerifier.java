package com.likelion.slash.ws;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.security.spec.X509EncodedKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Agent 가 보낸 도전값 서명을 {@code devices.public_key} 로 검증한다.
 *
 * <p>개인키는 사용자 PC 밖으로 나오지 않는다. (문서 LA-01)
 * 연결마다 새 도전값에 서명을 받으므로, 지난 연결에서 오간 값을 재생해도 통과하지 못한다.
 *
 * <p><b>공개키 표기</b> — Base64 로 저장하되 두 가지 표기를 모두 받는다.
 * <ul>
 *   <li>X.509 SubjectPublicKeyInfo (44바이트) — Java·OpenSSL 이 내보내는 표기</li>
 *   <li>원시 32바이트 — libsodium 계열이 내보내는 표기</li>
 * </ul>
 * Agent 구현이 어느 쪽을 쓰는지에 따라 등록이 통째로 막히는 것을 피하려고 둘 다 받는다.
 * 원시 표기는 앞에 표준 헤더를 붙여 X.509 로 만든 뒤 처리한다.
 *
 * <p>관련 문서: 3.4.2 · LA-01 · WBS W1-06
 */
@Component
public class AgentSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(AgentSignatureVerifier.class);

    private static final String ALGORITHM = "Ed25519";

    /** 원시 32바이트 앞에 붙이면 X.509 SubjectPublicKeyInfo 가 되는 Ed25519 고정 헤더. */
    private static final byte[] X509_ED25519_HEADER =
            HexFormat.of().parseHex("302a300506032b6570032100");

    private static final int RAW_KEY_LENGTH = 32;

    /**
     * 도전값에 대한 서명이 이 공개키로 만들어졌는지 확인한다.
     *
     * @param publicKeyBase64 {@code devices.public_key}
     * @param challenge       서버가 보낸 도전값의 원본 바이트
     * @param signatureBase64 Agent 가 보낸 서명
     * @return 검증에 성공하면 true. 형식 오류·검증 실패는 모두 false 다.
     */
    public boolean verify(String publicKeyBase64, byte[] challenge, String signatureBase64) {
        try {
            PublicKey publicKey = parsePublicKey(publicKeyBase64);

            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(challenge);

            return signature.verify(Base64.getDecoder().decode(signatureBase64));

        } catch (Exception e) {
            // 검증 실패와 형식 오류를 호출부에서 구분할 이유가 없다. 어느 쪽이든 인증 실패다.
            // 사유를 응답에 담지 않는 것은 공격자에게 단서를 주지 않기 위해서다.
            log.debug("Ed25519 서명 검증 실패: {}", e.getMessage());
            return false;
        }
    }

    private PublicKey parsePublicKey(String publicKeyBase64) throws Exception {
        byte[] decoded = Base64.getDecoder().decode(publicKeyBase64.trim());

        byte[] x509 = decoded.length == RAW_KEY_LENGTH
                ? concat(X509_ED25519_HEADER, decoded)
                : decoded;

        return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(x509));
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] joined = new byte[head.length + tail.length];
        System.arraycopy(head, 0, joined, 0, head.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }
}
