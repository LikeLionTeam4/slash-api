package com.likelion.slash.ws;

/**
 * 사용자 WSS {@code /ws/user} 메시지 계약의 고정값. (WBS W1-06)
 *
 * <p>계약 원본은 slash-runner 의 {@code slash-python-pc-runner/src/slash_pc_runner/protocol.py} 이고,
 * (과거 {@code contracts/src/userMessages.ts} — Python 재작성으로 소멸)
 * 참조 구현은 {@code mock-api/src/userWss.ts} 다.
 *
 * <p><b>Agent 채널과 다르다.</b> Agent 메시지는 {@code schemaVersion}·{@code eventId}·
 * {@code sentAt} 을 모두 요구하지만, 사용자 이벤트에는 공통 필드가 없다. 이쪽은 원장이 아니라
 * <b>화면을 빠르게 반영하기 위한 알림 채널</b>이라서다. 없는 필드를 흉내 내 넣지 않는다.
 *
 * <p><b>진실 소스는 REST 다.</b> 연결이 끊긴 동안의 이벤트는 그냥 사라지고, 프론트는 재조회로
 * 따라잡는다. (docs/frontend-api-contract.md §7)
 *
 * <p><b>여기에는 실제로 내보내는 타입만 둔다.</b> 계약에 있더라도 아직 발행하지 않는 것을
 * 미리 적어 두면 읽는 사람이 이미 되는 기능으로 오해한다. 기기 상태 알림처럼 뒤에 붙는 것은
 * 발행 메서드·DTO 와 함께 그때 추가한다.
 */
public final class UserProtocol {

    /** 접속 직후 서버가 먼저 보낸다. 프론트는 이걸 받아야 연결이 선 것으로 본다. */
    public static final String TYPE_CONNECTED = "CONNECTED";

    /** 작업 상태가 바뀌었다. */
    public static final String TYPE_TASK_STATUS_CHANGED = "TASK_STATUS_CHANGED";

    /** 작업이 끝나 결과를 조회할 수 있다. */
    public static final String TYPE_TASK_RESULT_AVAILABLE = "TASK_RESULT_AVAILABLE";

    public static final String TYPE_PING = "PING";
    public static final String TYPE_PONG = "PONG";

    /**
     * {@code resultPreview} 최대 길이. 참조 구현과 같은 200자다.
     *
     * <p>본문 전체를 여기 싣지 않는다. 결과는 64KB 까지 커질 수 있고, 그것을 모든 탭에 밀어 넣는
     * 것은 알림 채널이 할 일이 아니다. 프론트는 이 미리보기로 화면을 먼저 바꾸고 본문은 REST 로 받는다.
     */
    public static final int RESULT_PREVIEW_LIMIT = 200;

    private UserProtocol() {
    }
}
