/**
 * 보안 감사 기록.
 *
 * <p>애그리거트: {@code audit_events}
 *
 * <p>담당 범위
 * <ul>
 *   <li>PC 등록·해제, 권한 변경 같은 주요 사건 기록</li>
 *   <li>사용자별·대상 자원별 감사 이력 조회</li>
 * </ul>
 *
 * <p>비밀값·전체 파일 경로·원문 IP 는 저장하지 않는다. IP 는 해시로만 남긴다. (개인정보 최소 수집)
 *
 * <p>사용자를 지워도 기록은 남아야 하므로 {@code user_id} 는 {@code ON DELETE SET NULL} 이다.
 * 대상 자원도 {@code public_id} 로만 가리키고 FK 를 두지 않는다.
 *
 * <p>관련 문서: 0.7 · WBS W1-05
 */
package com.likelion.slash.audit;
