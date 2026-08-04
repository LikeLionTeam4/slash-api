/**
 * 실시간 연결(WebSocket) 처리.
 *
 * <p>담당 범위
 * <ul>
 *   <li>사용자 WSS {@code /ws/user} — 30초·1회용 Ticket 으로 접속, 상태 변경 알림 전송</li>
 *   <li>에이전트 WSS {@code /ws/agent} — 기기 Token + 연결마다 Ed25519 도전값 서명 검증</li>
 *   <li>HELLO · CHALLENGE · AUTH · READY · HEARTBEAT · TASK · ACK · RESULT 프레임 처리</li>
 *   <li>Heartbeat 30초, 3회 누락 시 기기를 OFFLINE 으로 변경</li>
 * </ul>
 *
 * <p>WSS 는 빠른 화면 반영을 담당하고, 신뢰할 수 있는 최종 원장은 REST 가 담당한다.
 * 연결이 끊겨도 REST 조회로 상태를 복구할 수 있어야 한다. (문서 7.3.1)
 *
 * <p><b>미해결</b> — slash-api 는 최소 2 Pod 로 운영되는데 Agent 의 WSS 연결은 특정 Pod 에만
 * 존재한다. 다른 Pod 에서 발생한 이벤트를 연결 보유 Pod 로 전달하는 방법이 문서에 정의되어
 * 있지 않다. (Valkey Pub/Sub 등 검토 필요)
 *
 * <p>관련 문서: 3.4.2 · 3.6 · WBS W1-06
 */
package com.likelion.slash.ws;
