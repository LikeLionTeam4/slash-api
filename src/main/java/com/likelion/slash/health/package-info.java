/**
 * 의존 서비스 연결 점검.
 *
 * <p>담당 범위
 * <ul>
 *   <li>{@code GET /api/v1/health/dependencies} — RDS·Valkey 연결 상태</li>
 * </ul>
 *
 * <p>Kubernetes Probe 는 Actuator 의 {@code /actuator/health} 를 사용한다.
 * 이 패키지는 배포 확인과 장애 조사에서 어느 의존 서비스가 끊겼는지
 * 공통 응답 형식으로 확인하기 위한 것이다.
 *
 * <p>인증 없이 접근할 수 있으므로 접속 주소·자격증명 같은 내부 정보를 응답에 넣지 않는다.
 *
 * <p>관련 문서: WBS W1-00
 */
package com.likelion.slash.health;
