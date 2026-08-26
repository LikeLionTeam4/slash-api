-- ---------------------------------------------------------------------------
-- V015 : 이미 끝난 요약에서 원문을 걷어낸다. (slash-docs#3 · 원문 기본 미저장)
--
--   신규 건은 코드가 성공 시점에 정리한다. 과거 행을 그대로 두면 지금 정하는 것이
--   "오늘부터의 정책"이 아니라 "신규 건에만 적용되는 예외"가 되므로 한 번 맞춘다.
--
--   무엇이 남아 있었나 (BACKEND 로 실행된 요약 한 건 기준, 실측)
--     input_text       원문 전체
--     parameters.text  원문 전체 한 벌 더
--     request_summary  원문 앞 80자
--
--   request_summary 도 바꾸는 이유
--     분량이 작아도 원문 발췌인 것은 같다. 이 시점에는 결과가 이미 있으므로 그것을
--     쓴다 — 목록에서 "무엇을 요약했는지" 알아보는 데도 원문 앞부분보다 낫다.
--     BROWSER 경로가 처음부터 쓰던 방식이다.
--
--   되돌릴 수 없다
--     원문을 지우는 것이 목적이라 복구 경로를 남기지 않는다.
--
--   건드리지 않는 것
--     성공하지 못한 요약 — 사용자가 다시 누를 근거가 원문뿐이다.
--     결과가 없는 성공 행 — request_summary 를 채울 것이 없다. 그런 행이 있다면
--       원문이 남으므로, 있는지부터 확인해야 할 결함이다(정상이라면 0건이다).
--     BROWSER 로 실행된 행 — 애초에 원문을 받지 않아 정리할 것이 없다.
--
--   request_summary 는 RequestSummary.of 와 같은 규칙으로 만든다 — 줄바꿈과 연속
--   공백을 한 칸으로 줄인 뒤 80자에서 자른다. 두 곳에서 다르게 자르면 같은 작업이
--   화면마다 다르게 보인다(그 클래스 주석이 규칙을 한 곳에 둔 이유다).
--
--   task_events 의 CREATED 메시지에도 원문 앞 80자가 들어 있지만 남긴다.
--   request_summary 와 같은 분량이고, 그 표는 "그때 무엇이 일어났는가"의 기록이라
--   나중에 고쳐 쓰면 기록으로서의 뜻이 흐려진다. (slash-docs#3 에서 합의)
-- ---------------------------------------------------------------------------

UPDATE tasks
SET input_text      = '[서버에서 요약 · 원문 '
                          || length(parameters ->> 'text')
                          || '자, 요약 후 저장하지 않음]',
    parameters      = (parameters - 'text')
                          || jsonb_build_object('inputLength', length(parameters ->> 'text')),
    request_summary = left(btrim(regexp_replace(result ->> 'summary', '\s+', ' ', 'g')), 80),
    version         = version + 1
WHERE task_type = 'TEXT_SUMMARY'
  AND status = 'SUCCEEDED'
  AND parameters ? 'text'
  AND result ? 'summary';
