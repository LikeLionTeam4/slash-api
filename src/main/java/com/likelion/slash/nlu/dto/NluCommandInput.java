package com.likelion.slash.nlu.dto;

import java.util.List;

/**
 * 슬래시 명령을 분해한 형태. (slash-nlu {@code models.py} 의 {@code CommandInput})
 *
 * <p>NLU 는 {@code path[0]} 을 {@code SLASH_ALIASES} 에서 찾아 작업 유형을 정한다.
 * 같은 {@code /status} 라도 {@code text} 로 보내면 Kiwi 규칙 분석으로 새기 때문에
 * 슬래시로 시작하는 입력은 반드시 이 형태로 보낸다.
 *
 * @param path     명령 경로. 빈 문자열 조각이 있으면 NLU 가 거부한다.
 * @param operands 명령 뒤에 붙은 값들. 없으면 빈 목록이다.
 */
public record NluCommandInput(List<String> path, List<String> operands) {
}
