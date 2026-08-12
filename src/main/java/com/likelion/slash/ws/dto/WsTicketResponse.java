package com.likelion.slash.ws.dto;

/**
 * 사용자 WSS 접속표 발급 응답. (계약 §7)
 *
 * <p><b>접속표 원문은 여기서만 나간다.</b> 서버는 해시만 보관하므로 다시 알려줄 수 없다.
 * 잃어버리면 새로 발급받으면 된다 — 30초짜리라 재발급이 정상적인 흐름이다.
 *
 * @param expiresIn 유효 기간(초). 연결이 끊겨 다시 붙을 때 표를 새로 받아야 하는지
 *                  프론트가 판단하는 데 쓴다
 * @param wsUrl     접속할 사용자 WSS 주소. 환경마다 다르므로 서버가 알려준다
 */
public record WsTicketResponse(
        String ticket,
        long expiresIn,
        String wsUrl) {
}
