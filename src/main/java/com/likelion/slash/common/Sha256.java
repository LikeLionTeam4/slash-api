package com.likelion.slash.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * SHA-256 hex 문자열.
 *
 * <p>등록 코드와 기기 Token 은 <b>원문을 저장하지 않고</b> 이 값으로 대조한다.
 * DB 가 유출돼도 그 값만으로는 남의 PC 에 연결할 수 없어야 한다. (V003 · V008)
 *
 * <p>비밀번호가 아니므로 BCrypt 같은 느린 해시를 쓰지 않는다. 대상이 충분히 긴 임의값이라
 * 사전 공격이 성립하지 않고, WSS 접속마다 대조하므로 빠른 편이 낫다.
 * 다만 <b>등록 코드는 6자리로 짧다</b> — 그쪽은 해시가 아니라 시도 횟수 제한이 방어선이다.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static String hex(String value) {
        return hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);

            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();

        } catch (Exception e) {
            // SHA-256 은 모든 JVM 이 갖고 있다. 여기서 실패하면 환경 문제다.
            throw new IllegalStateException("SHA-256 계산에 실패했습니다.", e);
        }
    }
}
