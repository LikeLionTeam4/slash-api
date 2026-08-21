package com.likelion.slash.common.enums;

/**
 * 요약을 <b>무엇으로</b> 하는지. (slash-docs#3 · LLM 실행 구조 전환)
 *
 * <p>{@link ExecutionTarget} 과 갈라 두었다. 그 값은 <b>어디서</b> 실행했는지만 나타낸다 —
 * 둘 다 서버가 하는 일이라 실행 위치는 {@code BACKEND} 로 같고, 여기서 갈린다.
 *
 * <p>실행할 때 고르는 값이라 설정으로 둔다. <b>작업마다 달라지는 값이 아니다.</b> 사용자가
 * 방식을 고를 수 있게 되면(권장안의 {@code summaryMode}) 그때 요청에서 정해지겠지만,
 * 고를 것이 하나뿐인 동안에는 고르게 할 이유가 없다.
 */
public enum SummaryEngine {

    /** slash-nlu 의 CPU 추출 요약. 원문에서 중요한 문장을 고른다. GPU 를 쓰지 않는다. */
    EXTRACTIVE,

    /**
     * GPU EC2 의 Ollama(Gemma). 문장을 새로 만든다.
     *
     * <p>권장 순서 5번("Gemma 신규 유입 중단")까지 남겨 두는 값이다. 과거 이력을 읽는 것과
     * 되돌릴 자리를 위해서지, 새 작업을 이쪽으로 보내려는 것이 아니다.
     */
    GEMMA
}
