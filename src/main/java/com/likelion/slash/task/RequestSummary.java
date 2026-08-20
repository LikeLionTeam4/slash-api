package com.likelion.slash.task;

/**
 * 이력 목록에 한 줄로 보여줄 요약.
 *
 * <p>입력 원문의 앞부분을 그대로 쓴다. 별도로 말을 지어내지 않는 이유는, 사용자가 자기가 무엇을
 * 시켰는지 알아보는 것이 목록의 목적이기 때문이다.
 *
 * <p><b>규칙을 한 곳에 둔다.</b> 접수할 때 {@code tasks.request_summary} 에 적어 두지만,
 * 분석에 이르지 못하고 실패한 요청은 그 열이 비어 있다. 그때는 목록을 만들면서 원문에서 다시
 * 만들어야 하는데, 두 곳에서 따로 자르면 같은 요청이 화면마다 다르게 보인다.
 */
public final class RequestSummary {

    /** 목록 한 줄에 들어갈 만한 길이. 넘으면 자른다. */
    private static final int MAX_LENGTH = 80;

    private RequestSummary() {
    }

    public static String of(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.length() <= MAX_LENGTH ? trimmed : trimmed.substring(0, MAX_LENGTH);
    }
}
