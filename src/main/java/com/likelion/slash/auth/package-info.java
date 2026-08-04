/**
 * 사용자 인증과 계정 동기화.
 *
 * <p>담당 범위
 * <ul>
 *   <li>Cognito Access Token(JWT) 서명·발급자·대상·만료 검증</li>
 *   <li>JWT 의 sub 를 기준으로 users 레코드 연결·생성</li>
 *   <li>{@code GET /me}</li>
 *   <li>인증 사용자 정보를 다른 도메인에 제공</li>
 * </ul>
 *
 * <p>Spring 이 자체 로그인·토큰 갱신·로그아웃 API 를 만들지 않는다.
 * 해당 흐름은 Cognito Managed Login 이 담당한다. (문서 AC-01, AC-02, 3.2.1)
 *
 * <p>관련 문서: 0.5.1 · 3.2 · WBS W1-01
 */
package com.likelion.slash.auth;
