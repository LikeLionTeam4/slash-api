package com.likelion.slash.auth;

/**
 * Cognito {@code userInfo} 응답에서 필요한 값만 추린 것.
 *
 * @param email       사용자 이메일. {@code users.email} 이 필수라 이 값이 없으면 사용자를 만들 수 없다.
 * @param displayName 표시 이름. 없을 수 있다.
 */
public record CognitoUserProfile(String email, String displayName) {
}
