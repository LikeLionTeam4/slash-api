/**
 * PC 등록(페어링)과 기기 인증.
 *
 * <p>애그리거트: {@code device_pairing_requests}
 *
 * <p>담당 범위
 * <ul>
 *   <li>5분·1회용 등록 코드 발급과 진행 상태 조회</li>
 *   <li>Agent 가 제출한 등록 코드 + Ed25519 공개키 검증</li>
 *   <li>도전값(nonce) 발급과 서명 검증으로 개인키 소유 증명</li>
 *   <li>기기 Token 발급(24시간)과 갱신</li>
 * </ul>
 *
 * <p>등록 코드 원문을 저장하지 않고 해시만 보관한다. 만료·재사용은 거부한다. (문서 DV-01)
 * 사용자 Access Token 과 기기 Token 을 같은 인증 방식으로 처리하지 않는다. (문서 0.7)
 *
 * <p>관련 문서: 0.5.2 · 3.4.3 · 3.5 · WBS W1-02
 */
package com.likelion.slash.pairing;
