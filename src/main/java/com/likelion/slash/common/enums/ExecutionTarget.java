package com.likelion.slash.common.enums;

/**
 * 작업을 <b>실제로 실행한 주체</b>. (slash-docs#3)
 *
 * <p>{@link ProcessingRoute} 와 무엇이 다른가 — 그 값은 {@link TaskType} 이 상수로 들고 있는
 * 것이라 유형이 정해지면 언제나 같은 값이다. 이 값은 같은 유형이라도 상황에 따라 달라진다.
 * {@code TEXT_SUMMARY} 하나가 브라우저·PC·서버 셋 중 하나에서 실행되기 때문에 갈라 두었다.
 *
 * <p><b>어디서</b>만 나타내고 <b>무엇으로</b>는 나타내지 않는다. 같은 {@code BACKEND} 라도
 * GPU Gemma 와 CPU 추출 요약이 있고, 같은 {@code RUNNER} 라도 Claude Code 와 Codex 가 있다.
 * 그 구분은 작업 결과에 담는다 — 실행 위치를 고르는 데 쓰이지 않는 값이라 열로 둘 이유가 없다.
 *
 * <p><b>사용자나 브라우저가 정하지 않는다.</b> 요청에 실려 온 값을 그대로 믿으면 브라우저가
 * 자기 실행 결과를 PC 실행 결과인 것처럼 제출할 수 있다. slash-api 가 정하고 기록한다.
 */
public enum ExecutionTarget {

    /** 사용자 브라우저가 WebLLM 으로 실행한다. 결과를 사용자가 제출하므로 신뢰 수준이 다르다. */
    BROWSER,

    /** 등록한 PC 의 실행기가 실행한다. 서명한 Agent 만 결과를 제출할 수 있다. */
    RUNNER,

    /** slash-api 나 그 뒤의 서비스가 실행한다. 결과를 서버가 만든다. */
    BACKEND
}
