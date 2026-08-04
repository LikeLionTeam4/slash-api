/**
 * 등록된 PC(Device) 관리.
 *
 * <p>애그리거트: {@code devices} + {@code device_capabilities}
 *
 * <p>담당 범위
 * <ul>
 *   <li>내 PC 목록·단건 조회, 이름 수정(If-Match/ETag), 연결 해제</li>
 *   <li>모든 조회·수정·작업 요청에 소유권 강제 (문서 DV-04)</li>
 *   <li>실행 가능 여부 판정 — READY 상태와 Capability 확인 (문서 DV-05)</li>
 *   <li>Heartbeat 수신에 따른 상태 전이, 90초 미수신 시 OFFLINE</li>
 * </ul>
 *
 * <p>다른 사용자가 소유한 자원은 식별자 추측을 막기 위해 404 로 응답한다. (문서 3.2.3)
 *
 * <p>관련 문서: 0.5.2 · 3.4.4 · WBS W1-03
 */
package com.likelion.slash.device;
