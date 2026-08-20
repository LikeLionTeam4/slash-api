# 프론트엔드 연동 규약

slash-api 를 호출할 때 지켜야 하는 공통 규약입니다.
개별 엔드포인트 명세가 아니라 **모든 API 에 공통으로 적용되는 부분**을 다룹니다.

| | |
|---|---|
| 버전 | v0.3 |
| 기준일 | 2026-08-10 |
| 담당 | 코어 API (김강찬) |

> 값이 정해지지 않은 항목은 `TBD` 로 표시했습니다. 확정될 때마다 이 문서를 갱신합니다.

---

## 0. 지금 실제로 붙일 수 있는 API

| 상태 | 엔드포인트 |
|---|---|
| 동작 중 | `GET /api/v1/health/dependencies` |
| 동작 중 | `GET /api/v1/me` |
| **동작 중** | `POST /api/v1/pairing-requests` — PC 등록 코드 발급 |
| **동작 중** | `GET /api/v1/pairing-requests/{id}` — 등록 진행 상태 |
| **동작 중** | `GET /api/v1/task-types` — 작업 유형 기준 목록 |
| **동작 중** | `POST /api/v1/requests` — 작업 접수 |
| **동작 중** | `GET /api/v1/tasks/{taskId}` — 작업 상태·결과 조회 |
| **동작 중** | `GET /api/v1/tasks` — 작업 이력 목록 (갈래·상태·PC 필터) |
| **동작 중** | `GET /api/v1/devices` — 등록된 PC 목록 |
| **동작 중** | `DELETE /api/v1/devices/{id}` — PC 등록 해제 |
| **동작 중** | `PATCH /api/v1/devices/{id}/task-intake` — 작업 수신 켜기·끄기 |
| 계약만 확정 | 기기 이름 변경 (W1-03) |

### PC 등록 화면 (W1-02)

```
POST /api/v1/pairing-requests            → 201
{ "data": { "pairingRequestId": "…", "pairingCode": "900823",
            "expiresAt": "2026-08-06T15:35:11+09:00" } }

GET  /api/v1/pairing-requests/{id}       → 200
{ "data": { "status": "PENDING", "deviceId": null } }
{ "data": { "status": "CLAIMED", "deviceId": "e0d68b9f-…" } }
```

화면 흐름은 이렇습니다.

1. "PC 등록" 을 누르면 코드를 발급받아 **6자리를 크게 보여줍니다.** 사용자가 그 값을 자기 PC 의
   Agent 에 입력합니다.
2. 발급 응답의 `expiresAt` 까지 남은 시간을 함께 보여주세요. **5분**입니다.
3. 그동안 `GET` 으로 상태를 조회해 `CLAIMED` 로 바뀌면 등록 완료로 처리합니다.
   (2~3초 간격이면 충분합니다)

> **`pairingCode` 는 발급 응답에서 딱 한 번만 나옵니다.** 서버는 해시만 저장해서 다시 알려줄 수
> 없습니다. 화면을 벗어나면 새로 발급받아야 하니, 그 전에 이탈하지 않도록 안내해 주세요.

> 코드는 **사용자당 한 개**만 살아 있습니다. 새로 발급하면 이전 코드는 즉시 무효가 됩니다.
> 사용자가 등록 화면을 두 번 열면 먼저 연 쪽의 코드가 죽으니, 화면을 다시 열 때 코드를
> 자동 재발급하기보다 "코드 다시 받기" 버튼으로 두는 편이 안전합니다.

> Agent 가 호출하는 `POST /api/v1/agent/pair`·`/pair/verify`·`/sessions/refresh` 는
> 프론트가 부를 일이 없습니다. 사용자 인증 없이 Ed25519 서명으로 동작하는 별도 경로입니다.

### 지정 PC 관리 화면 (W1-03)

```
GET  /api/v1/devices                     → 200
{ "data": { "devices": [
    { "deviceId": "e0d68b9f-…", "name": "내 PC", "status": "READY",
      "os": "MACOS", "osVersion": "macOS-26.6.1-arm64-arm-64bit",
      "agentVersion": "slash-pc-runner-py/0.4.0", "acceptingTasks": true,
      "lastSeenAt": "2026-08-13T10:24:01.431535+09:00",
      "registeredAt": "2026-08-12T17:50:19.195244+09:00", "version": 0 } ] } }

DELETE /api/v1/devices/{deviceId}        → 204   (PC 등록 해제)
If-Match: "0"

PATCH  /api/v1/devices/{deviceId}/task-intake  → 200   (작업 수신 켜기·끄기)
If-Match: "0"
{ "accepting": false }
{ "data": { "deviceId": "…", "acceptingTasks": false, "version": 1, … } }
```

**화면을 열 때 이걸 부르면 됩니다.** 등록이 끝난 뒤(`CLAIMED`)에도 이 목록을 다시 불러
갱신하는 편이 안전합니다 — 다른 탭에서 등록한 PC 도 함께 들어옵니다.

**해제한 PC 는 목록에 오지 않습니다.** 등록 한도를 `devices.length` 로 판단해도 됩니다.

> **`osVersion` 과 `agentVersion` 은 Agent 가 보고한 원문 그대로입니다.** 위 예시가 실제
> 형태입니다 — 서버가 다듬지 않습니다. 화면에 그대로 뿌리면 거칠어 보이니, 필요하면
> 앞부분만 잘라 쓰거나(`macOS-26.6.1` 까지) 도구 설명으로 넘기세요.
>
> **한 번도 연결된 적 없는 PC 는 이 셋이 아예 없습니다.** `osVersion`·`agentVersion`·
> `lastSeenAt` 은 값이 `null` 이면 응답에서 빠집니다(§2). 옵셔널 체이닝으로 접근하세요.

#### `status` 로 연결 여부 판단하기

| status | 뜻 | 연결됨 표시 |
|---|---|---|
| `READY` | 작업을 받을 수 있음 | ✅ |
| `ONLINE` | 붙어 있으나 준비 보고 전 | ✅ |
| `BUSY` | 작업 하나 실행 중 | ✅ |
| `OFFLINE` | 꺼져 있거나 90초 동안 소식 없음 | ❌ |

`REVOKED` 는 이 목록에 오지 않으므로 다루지 않아도 됩니다.

> **`status` 는 최대 2분까지 늦을 수 있습니다.** PC 가 꺼진 것을 알려주는 신호가 없어서,
> 서버가 마지막 Heartbeat 로부터 90초가 지났는지를 30초마다 확인해 내립니다.
> 더 정확한 시각이 필요하면 `lastSeenAt` 을 직접 보세요.

> `deviceId` 가 작업 접수의 `selectedDeviceId` 에 넣는 값입니다. PC 를 고르는 화면을 만들면
> 이 값을 그대로 쓰면 됩니다.

#### 등록 해제와 작업 수신 중지는 다릅니다

| | 해제 (`DELETE`) | 수신 중지 (`PATCH .../task-intake`) |
|---|---|---|
| 되돌리기 | **불가**. 다시 등록하려면 코드를 새로 받아야 합니다 | 가능. 언제든 다시 켤 수 있습니다 |
| 연결 | **그 자리에서 끊깁니다.** 다시 붙지 못합니다 | 그대로 유지됩니다 |
| 목록 | 사라집니다 | 남아 있고 `acceptingTasks: false` 로 옵니다 |
| 그동안 온 요청 | 보낼 PC 가 없으므로 실패합니다 | `WAITING_FOR_DEVICE` 로 쌓였다가 다시 켜면 실행됩니다 |
| 실행 중인 작업 | 연결이 끊겨 결과를 받지 못합니다 | 끝까지 실행됩니다. **새 작업만** 안 받습니다 |

X 버튼(해제)은 되돌릴 수 없으니 확인을 한 번 받는 편이 좋습니다.

#### 수정 요청에는 `If-Match` 가 필요합니다

`DELETE` 와 `PATCH` 둘 다 조회 응답의 `version` 을 `If-Match` 로 넣어야 합니다.

```
If-Match: "0"      ← 목록 응답의 version 값
```

- **400 `VALIDATION_ERROR`** — 헤더를 빠뜨렸거나 숫자로 읽을 수 없음
- **412 `RESOURCE_VERSION_MISMATCH`** — 그 사이 다른 탭·기기에서 먼저 바뀜 → **재조회 후 다시 시도**
- **404 `RESOURCE_NOT_FOUND`** — 없는 기기이거나 이미 해제된 기기

`version` 은 **사용자가 일으킨 변경에만** 올라갑니다. PC 가 켜지고 꺼지는 것(Heartbeat)으로는
바뀌지 않으니, 화면을 오래 열어 두어도 들고 있던 값이 헛되이 낡지 않습니다.

`PATCH` 응답에 바뀐 기기가 그대로 들어 있어 목록을 다시 부르지 않아도 되고, 다음 수정에 쓸
`version` 도 거기 있습니다.

> **`accepting` 은 토글이 아니라 원하는 상태를 그대로 보냅니다.** 같은 값을 두 번 보내도
> 결과가 같습니다. 화면이 들고 있는 값이 낡았을 때 의도와 반대로 뒤집히는 것을 막습니다.

### 입력창 화면 (W1-04)

**입력창의 한 줄을 그대로 보내세요.** 슬래시 명령인지 자연어인지 프론트가 가르지 않습니다.
`/status` 같은 명령을 분해하는 것도, 어떤 작업인지 알아내는 것도 서버와 NLU 가 합니다.

```
POST /api/v1/requests                    → 202
Idempotency-Key: {UUID v4}
{ "text": "/status", "selectedDeviceId": null }

{ "data": { "taskId": "9c1e…", "status": "QUEUED",
            "statusUrl": "/api/v1/tasks/9c1e…" } }

GET  /api/v1/tasks/{taskId}              → 200
{ "data": { "taskId": "9c1e…", "status": "SUCCEEDED",
            "taskType": "SYSTEM_STATUS", "processingRoute": "LOCAL_AGENT",
            "deviceId": "e0d6…", "inputText": "/status",
            "parameters": { … }, "result": { … }, "errorCode": null,
            "correlationId": "…", "createdAt": "…", "updatedAt": "…",
            "completedAt": "…" } }
```

`selectedDeviceId` 는 **비워도 됩니다.** 비우면 등록된 PC 중에서 서버가 고르되 **연결돼 있는
것을 먼저** 고릅니다. PC 를 고르는 화면이 아직 없다면 그냥 생략하세요.

진행 상황은 **사용자 WSS 로 밀어 드립니다**(§7). `TASK_STATUS_CHANGED` 로 진행 표시를 바꾸고,
`TASK_RESULT_AVAILABLE` 을 받으면 `statusUrl` 을 한 번 조회해 본문을 받으세요.

**WSS 에 붙지 않았거나 끊긴 동안에는 `statusUrl` 을 2초 간격으로 폴링**해 주세요. WSS 는 빠른
화면 반영이고 **진실은 REST** 라, 폴링만으로도 화면은 정상 동작합니다. 상태가
`SUCCEEDED`·`FAILED`·`EXPIRED` 중 하나가 되면 멈추면 됩니다.

#### 상태값

| status | 뜻 | 화면 |
|---|---|---|
| `ANALYZING` | 무슨 요청인지 분석 중 | 진행 표시 |
| `NEEDS_CLARIFICATION` | 되물어야 함 | `question` 을 보여주고 다시 입력받기 |
| `WAITING_FOR_DEVICE` | **PC 가 작업을 받을 수 없어 대기 중** | 아래 안내 문구를 그대로 |
| `QUEUED` | PC 로 보냈고 수락 대기 | 진행 표시 |
| `RUNNING` | 실행 중 | 진행 표시 |
| `SUCCEEDED` | 완료 | `result` 표시 |
| `FAILED` / `EXPIRED` | 실패·기한 만료 | `errorCode` 로 안내 |

> **`WAITING_FOR_DEVICE` 를 오류로 다루지 마세요.** PC 가 꺼져 있어도 요청은 정상 접수됩니다.
> 사용자가 PC 를 켜면 그때 실행되고 상태가 알아서 넘어갑니다. 이게 데스크톱 앱으로는 안 되는
> 동작이라 시연에서 보여줄 장면이기도 합니다.
>
> 기다리는 이유가 두 가지입니다 — **PC 가 꺼져 있거나, 작업 수신을 꺼 두었거나.** 서버가
> 타임라인 안내 문구로 구분해 주니 그대로 보여주면 됩니다. ("PC 가 연결되면 실행합니다" /
> "PC 가 작업 수신을 다시 켜면 실행합니다") 둘 다인 경우에는 연결부터 안내합니다.

> `POST` 응답의 `status` 는 **고정값이 아닙니다.** 접수 시점에 이미 정해진 실제 상태가 옵니다.
> PC 가 붙어 있으면 `QUEUED`, 꺼져 있으면 `WAITING_FOR_DEVICE`, 못 알아들었으면 `FAILED` 입니다.

#### 지금 되는 명령

`/status` (시스템 상태)·`/file` (파일 검색)·`/weather` (날씨)·`/summary` (요약)가
**모두 종단까지 동작합니다.** P0 네 가지가 다 붙었습니다.

**`/usage` (AI 도구 사용량)는 서버와 PC 실행기가 준비됐습니다.** 다만 NLU 가 아직 이 명령을
알지 못해, 붙기 전까지는 `UNRECOGNIZED_COMMAND` 로 옵니다.

> **`provider` 는 `CLAUDE_CODE` 또는 `CODEX` 여야 합니다.** 대소문자와 이음표(`claude-code`)는
> 서버가 맞춰 주지만, `claude` 처럼 목록에 없는 말은 `INVALID_PARAMETERS` (422) 로 거절합니다.
> PC 까지 갔다 오지 않고 접수 시점에 바로 답하므로, PC 가 꺼져 있어도 즉시 알 수 있습니다.

> **`/weather` 는 PC 를 고르지 않습니다.** 서버가 조회하는 일이라 등록된 PC 가 없어도
> 실행되고, `selectedDeviceId` 를 보내도 무시합니다.
>
> 결과에 **찾아낸 지명을 함께 싣습니다.** 사용자가 말한 "수원" 과 실제로 조회한
> "수원시(경기도)" 가 다를 수 있어서, `location` 과 `region` 을 함께 보여 주시면 엉뚱한 곳일 때
> 사용자가 알아챌 수 있습니다.
>
> ```json
> { "location": "수원시", "region": "경기도", "country": "대한민국",
>   "temperature": 26.7, "apparentTemperature": 32.0, "humidity": 84,
>   "precipitation": 0.0, "windSpeed": 5.2,
>   "description": "흐림", "observedAt": "2026-08-19T11:00" }
> ```
>
> `description` 은 서버가 우리말로 옮긴 값이라 그대로 보여 주시면 됩니다. 모르는 날씨
> 코드가 오면 빈 문자열이니 **비어 있을 때를 대비해 주세요** — 나머지 값은 정상입니다.
>
> 지명을 못 찾으면 `LOCATION_NOT_FOUND` (422) 입니다. 이때는 **"시·군 이름으로 다시
> 말씀해 주세요"** 로 안내해 주세요 — 사용자가 다시 말하면 되는 상황입니다. 날씨 서비스
> 자체에 닿지 못하면 `UPSTREAM_UNAVAILABLE` (503) 이고, 이건 기다리는 수밖에 없습니다.

> **`/summary` 는 PC 를 고르지 않습니다.** 서버 쪽 모델이 하는 일이라 등록된 PC 가 없어도
> 실행됩니다. `selectedDeviceId` 를 보내도 무시합니다.
>
> 접수 응답은 `QUEUED` 로 즉시 돌아오고 **모델이 생각하는 동안 기다리지 않습니다.**
> 진행은 다른 작업과 똑같이 WSS·폴링으로 따라오시면 됩니다. 실측 3~4초쯤 걸립니다.
>
> 결과는 `{ "summary": "...", "model": "..." }` 입니다. `model` 은 어떤 모델이 만든
> 요약인지 남긴 값이라 화면에 보여 줄 필요는 없습니다.
>
> 요약할 내용이 짧으면 NLU 단계에서 `NEEDS_CLARIFICATION` 으로 되묻고, 그보다 긴데도
> 모델이 거절하면 `INVALID_PARAMETERS` (422) 로 옵니다. 모델이 밀려 있으면
> `LLM_NOT_READY`, 아예 닿지 못하면 `UPSTREAM_UNAVAILABLE` (둘 다 503) 이라
> **잠시 뒤 다시 시도해 달라고** 안내해 주세요.

> **`/file` 은 검색할 폴더를 프론트가 고르지 않습니다.** 사용자가 PC 의 Agent 에서 등록해 둔
> 폴더 중에서 **서버가 자동으로 고릅니다.** 요청에 넣을 값이 없고, `/file 보고서` 처럼 찾을
> 말만 보내면 됩니다.
>
> 등록된 폴더가 없거나 폴더를 읽을 수 없으면 `SEARCH_FOLDER_NOT_FOUND` (422) 로 옵니다.
> 이때는 **"PC 의 Agent 에서 검색할 폴더를 추가해 주세요"** 로 안내해 주세요 — 사용자가
> 우리 화면에서 할 수 있는 일이 아니라 PC 쪽에서 해야 하는 일입니다.
>
> 색인이 아직 도는 중인 폴더도 그대로 검색합니다. 결과가 조금 덜 나올 수는 있어도
> 실패로 오지는 않습니다.

### 이력 화면 (P0-B)

```
GET /api/v1/tasks                        → 200
GET /api/v1/tasks?taskType=FILE_SEARCH&status=SUCCEEDED&deviceId=e0d6…&limit=20&cursor=…

{ "data": { "items": [
      { "taskId": "06c805a0-70e3-43d1-a3c0-646aecc4c219", "status": "CREATED",
        "requestSummary": "어제 만든 그거 좀",
        "createdAt": "2026-08-20T09:32:34.925993+09:00" },

      { "taskId": "e9cf57ef-467a-4a20-8c12-e7e0a067ae74", "status": "QUEUED",
        "taskType": "TEXT_SUMMARY", "processingRoute": "LLM_SERVICE",
        "requestSummary": "이 글 세 줄로 요약해줘",
        "createdAt": "2026-08-20T09:32:34.925615+09:00" },

      { "taskId": "f634a7d4-7443-4319-b4e1-fd2007eb7fa4", "status": "SUCCEEDED",
        "taskType": "WEATHER_LOOKUP", "processingRoute": "BACKEND_SERVICE",
        "requestSummary": "오늘 서울 날씨 알려줘",
        "createdAt": "2026-08-20T09:32:34.922341+09:00",
        "completedAt": "2026-08-20T09:29:34.922341+09:00" }
    ],
    "nextCursor": "MjAyNi0wOC0yMFQwOTozMjozNC45MjU2MTUrMDk6MDB8MTI2MTc" } }
```

*(위는 실제로 받은 응답입니다. `nextCursor` 는 `limit=2` 로 불렀을 때 온 값입니다.)*

**맨 위 줄처럼 `taskType` 이 없는 항목이 옵니다.** 아직 분석되지 않았거나 무슨 말인지
알아내지 못한 요청입니다. 갈래 뱃지를 그릴 때 값이 없는 경우를 다뤄 주세요.

**결과 본문(`result`)과 입력값(`parameters`)은 목록에 없습니다.** 한 건에 64KB 까지 허용되어
스무 줄이면 응답이 1MB 를 넘길 수 있어서입니다. 한 줄을 펼칠 때
`GET /api/v1/tasks/{taskId}` 로 그 건만 받으세요.

**`requestSummary` 는 항상 있습니다.** 사용자가 입력한 원문의 앞부분(최대 80자)입니다.
무엇을 시켰는지 알아보라고 두는 값이라 목록의 제목으로 그대로 쓰시면 됩니다.

**다음 쪽은 `nextCursor` 로만 판단하세요.** 이 값이 없으면 마지막 쪽입니다. 받은 값을 다음
요청의 `cursor` 에 그대로 넣으면 됩니다 — 안에 무엇이 들었는지는 보실 필요가 없고, 서버가
나중에 담는 내용을 바꿔도 이 방식은 그대로입니다.

> 항목 수가 `limit` 과 같은지로 판단하지 마세요. 마지막 쪽이 정확히 `limit` 개일 수 있어
> 빈 쪽을 한 번 더 부르게 됩니다.

**필터 세 가지는 모두 선택입니다.** 비우면 전체입니다.

| 조건 | 값 | 비고 |
|---|---|---|
| `taskType` | `GET /api/v1/task-types` 가 주는 이름 | 아직 분석되지 않은 요청은 이 조건에 걸리지 않습니다 |
| `status` | `CREATED`·`ANALYZING`·`NEEDS_CLARIFICATION`·`WAITING_FOR_DEVICE`·`QUEUED`·`RUNNING`·`SUCCEEDED`·`FAILED`·`EXPIRED` | `GET /api/v1/tasks/{taskId}` 의 `status` 와 같은 값입니다 |
| `deviceId` | `GET /api/v1/devices` 의 `deviceId` | `/weather`·`/summary` 는 PC 를 쓰지 않아 걸리지 않습니다 |
| `limit` | 1~100, 기본 20 | 범위를 벗어나면 `VALIDATION_ERROR` 입니다 |

**모르는 이름을 넣으면 400 (`VALIDATION_ERROR`)** 으로 옵니다. 조용히 무시하지 않는 이유는,
필터가 걸린 줄 알고 전체 목록을 보시게 되면 잘못 판단하시기 때문입니다.

**해제한 PC 로 실행했던 작업도 이력에 남습니다.** 그 줄의 `deviceId` 는 그대로 오지만
`GET /api/v1/devices` 목록에는 그 PC 가 없습니다. 이름을 찾지 못하면 "해제된 PC" 로
보여 주세요.

---

**Cognito 값이 아직 없어도 로컬에서는 로그인 이후 화면을 개발할 수 있습니다.**
[5.1 로컬 개발용 임시 인증](#51-로컬-개발용-임시-인증)을 보세요.

아래 1~4장은 모든 API 에 공통 적용되므로, 엔드포인트가 나오기 전에도
클라이언트 공통 모듈(요청 래퍼·오류 처리)에 미리 넣을 수 있습니다.

---

## 1. 요청 헤더

| 헤더 | 언제 | 값 |
|---|---|---|
| `Authorization` | 공개 경로 외 전부 | `Bearer {accessToken}` |
| `Content-Type` | 본문이 있을 때 | `application/json; charset=utf-8` |
| `Idempotency-Key` | 작업 생성 (`POST /api/v1/requests`) | UUID v4 |
| `If-Match` | 수정 요청 (예: 기기 이름 변경) | 직전 조회 응답의 `ETag` 값 |

### `Idempotency-Key`

사용자가 버튼을 **한 번 누른 것**을 기준으로 UUID 를 만들고,
네트워크 오류로 재시도할 때는 **같은 값을 유지**합니다.

재시도마다 새로 만들면 중복 방지가 무력화되어 같은 작업이 두 번 실행됩니다.
같은 키에 다른 본문을 보내면 `IDEMPOTENCY_CONFLICT` (409) 로 거부됩니다.
보존 기간은 24시간입니다.

### `If-Match` 와 `ETag`

수정 대상 자원은 조회 응답에 `ETag` 를 함께 돌려줍니다.
수정 요청에 그 값을 `If-Match` 로 넣으면, 그 사이 다른 탭이나 기기에서 먼저 수정된 경우
`RESOURCE_VERSION_MISMATCH` (412) 로 거부됩니다.

412 를 받으면 **재조회 후 다시 시도**하도록 안내합니다. 그냥 덮어쓰지 않습니다.

---

## 2. 성공 응답

모든 성공 응답은 `data` 와 `meta` 로 감싸집니다. 최상위에 데이터가 바로 오는 경우는 없습니다.

```json
{
  "data": {
    "database": "UP",
    "valkey": "UP",
    "checkedAt": "2026-08-05T11:24:17.365089+09:00"
  },
  "meta": {
    "requestId": "af5688c6-187b-40e0-8623-7e993676ce08",
    "serverTime": "2026-08-05T11:24:17.365120+09:00"
  }
}
```

- **모든 시각은 한국 시각(`+09:00`)** 입니다. UTC(`Z`) 로 오지 않습니다.
  ISO 8601 이므로 `new Date()` 로 그대로 파싱됩니다.
- `meta.requestId` — 오류를 문의할 때 이 값을 함께 알려주세요. 서버 로그를 이 값으로 찾습니다.
- **`null` 인 필드는 응답에서 빠집니다.** 항상 존재한다고 가정하지 말고 옵셔널 체이닝으로 접근하세요.

### 2.1 작업 결과(`result`) 형식

`GET /api/v1/tasks/{taskId}` 의 `result` 는 **작업 유형마다 모양이 다릅니다.** `taskType` 으로
갈라서 읽으세요. 작업이 끝나기 전에는 `null` 입니다.

> **서버는 결과를 가공하지 않습니다.** PC 실행기와 slash-llm 이 만든 것을 그대로 저장하고
> 그대로 돌려줍니다. 그래서 이 표가 곧 그쪽 구현입니다 — 아래 예시는 실제 응답을 받아 적은
> 것이고, 바뀌면 이 문서도 함께 고칩니다.
>
> 다만 **드물게 나오는 값**(디스크를 읽지 못한 경우, 결과가 잘린 경우, 확장자 없는 파일)은
> 재현하기 어려워 PC 실행기 코드를 근거로 적었습니다.

#### `SYSTEM_STATUS`

```json
{ "cpuPercent": 23.8, "memoryPercent": 68, "memoryTotalMb": 24576, "memoryUsedMb": 16663,
  "diskPercent": 28, "diskTotalMb": 948534, "diskUsedMb": 266522,
  "collectedAt": "2026-08-19T16:47:37.742+09:00" }
```

**디스크 세 값은 없을 수 있습니다.** PC 실행기가 디스크 정보를 읽지 못하면 `null` 로 두는데,
위 규칙대로 `null` 은 응답에서 빠지므로 **키 자체가 오지 않습니다.** 메모리·CPU 는 항상 옵니다.

#### `FILE_SEARCH`

```json
{ "searchFolderId": "sf-fixtures-01",
  "query": "회의록",
  "items": [
    { "fileRef": "f62dfe8a-8525-4ba9-a0b5-7f6d70ebfedd",
      "name": "회의록_2026-07-01.txt",
      "relativePath": "회의록_2026-07-01.txt",
      "extension": "txt",
      "sizeBytes": 95,
      "modifiedAt": "2026-08-18T14:50:06.000+09:00" }
  ],
  "returnedCount": 1,
  "truncated": false }
```

| 필드 | 설명 |
|---|---|
| `fileRef` | **파일을 가리키는 열쇠.** 절대 경로 대신 씁니다 — PC 안의 실제 경로는 클라우드로 오지 않습니다. 나중에 파일 열기 같은 후속 동작을 붙일 때 이 값을 그대로 돌려보내면 됩니다 |
| `relativePath` | 검색 폴더 기준 상대 경로. 하위 폴더면 `sub/예산안_초안.txt` 처럼 옵니다. **화면에 보여 줄 값은 이것입니다** |
| `extension` | 확장자(점 없음). 없으면 빈 문자열입니다 |
| `truncated` | 상한을 넘어 잘렸는지. 참이면 "더 있습니다" 를 안내해 주세요 |

`searchFolderId` 와 `query` 는 `task.parameters` 에도 있지만 **`result` 안에도 함께 옵니다.**
어느 쪽을 읽어도 같습니다.

#### `WEATHER_LOOKUP`

```json
{ "location": "수원시", "region": "경기도", "country": "대한민국",
  "temperature": 26.7, "apparentTemperature": 32.0, "humidity": 84,
  "precipitation": 0.0, "windSpeed": 5.2,
  "description": "흐림", "observedAt": "2026-08-19T11:00" }
```

`description` 은 서버가 우리말로 옮긴 값입니다. 모르는 날씨 코드가 오면 **빈 문자열**이니
비어 있을 때를 대비해 주세요 — 나머지 값은 정상입니다.

#### `TEXT_SUMMARY`

```json
{ "summary": "슬래시는 사용자가 브라우저에서 …", "model": "gemma3:4b" }
```

`model` 은 어떤 모델이 만든 요약인지 남긴 값이라 화면에 보여 줄 필요는 없습니다.

#### `AI_AGENT_USAGE`

```json
{ "provider": "CLAUDE_CODE", "totalSessions": 36,
  "totalInputTokens": 105075, "totalOutputTokens": 10351006,
  "totalCachedTokens": 2692975528, "totalReasoningTokens": null,
  "totalTokens": 2703431609,
  "oldestSessionAt": "2026-05-18T11:33:29.464Z",
  "newestSessionAt": "2026-08-20T02:29:55.029Z",
  "collectedAt": "2026-08-20T11:30:11.666+09:00" }
```

PC 실행기가 Claude Code·Codex 의 **로컬 세션 로그**를 읽어 집계한 값입니다. 자체 호스팅
Gemma 의 추론량과는 무관합니다.

**여기 두 가지는 다른 응답과 다릅니다. 실제로 받아 확인한 것입니다.**

- **`totalReasoningTokens` 는 값이 없어도 빠지지 않고 `null` 로 옵니다.** §2 의 "`null` 인 필드는
  응답에서 빠집니다" 규칙은 서버가 만드는 필드에만 적용되는데, `result` 는 PC 실행기가 만든
  것을 서버가 그대로 통과시키기 때문입니다. Claude Code 는 `null`, Codex 는 숫자입니다
- **`oldestSessionAt`·`newestSessionAt` 은 UTC(`Z`)로 옵니다.** §2 의 "모든 시각은 한국
  시각(`+09:00`)" 규칙의 예외입니다. 로그 파일에 적힌 값을 그대로 싣기 때문입니다.
  같은 응답 안의 `collectedAt` 은 한국 시각이라 **한 응답에 두 표기가 섞입니다**

그 밖에 알아 두실 것:

- 원격에서 돈 세션(웹 등)은 그 PC 에 로그가 남지 않아 집계에 잡히지 않습니다
- 그 도구를 쓴 적이 없으면 `CODE_AGENT_NOT_CONFIGURED` (422) 로 옵니다
- 세션 수는 세었는데 토큰이 모두 `0` 으로 오는 경우가 있습니다. 실제로 Codex 에서 그런
  응답을 받았습니다(`totalSessions: 27`, 토큰 전부 `0`). 화면에서 0 을 그대로 보여 주면
  사용자가 오해할 수 있으니, 세션이 있는데 토큰이 0 이면 따로 안내해 주시는 편이 좋습니다

---

## 3. 오류 응답

```json
{
  "error": {
    "code": "DEVICE_NOT_READY",
    "message": "선택한 PC가 작업을 받을 수 없습니다.",
    "details": { "deviceId": "..." }
  },
  "meta": {
    "requestId": "af5688c6-187b-40e0-8623-7e993676ce08",
    "serverTime": "2026-08-05T11:24:17.365120+09:00"
  }
}
```

- **분기는 HTTP 상태가 아니라 `error.code` 로 합니다.** 같은 422 에 여러 코드가 옵니다.
- `error.message` 는 **사용자에게 그대로 보여줘도 되는 한국어**입니다.
  프론트에서 코드별 문구를 따로 만들지 않아도 됩니다.
- `details` 는 없을 수 있습니다.
- 인증 실패(401)도 같은 형식으로 옵니다. 본문 없는 401 은 나가지 않습니다.
- **경로·질의 값의 형식이 틀리면 400 `VALIDATION_ERROR`** 입니다. UUID 자리에 UUID 가 아닌
  값을 넣거나 `?limit=abc` 처럼 숫자 자리에 글자를 넣은 경우이고, `details` 에 문제가 된
  이름이 들어옵니다. **비워서 보낸 질의 조건(`?deviceId=`)은 오류가 아니라 조건이 없는
  것으로 봅니다** — 화면이 필터를 걸지 않은 채 질의 문자열을 만들어도 그대로 동작합니다.

---

## 4. 오류 코드

### 인증·권한

| code | HTTP | 프론트 동작 |
|---|---|---|
| `AUTH_REQUIRED` | 401 | 토큰 갱신 1회 → 실패 시 로그인 화면 |
| `FORBIDDEN` | 403 | 권한 없음 안내 |
| `AGENT_AUTH_FAILED` | 401 | 로컬 Agent 전용. 프론트 해당 없음 |
| `PAIRING_CODE_INVALID` | 422 | 등록 코드 재발급 유도 |

### 입력·자원

| code | HTTP | 프론트 동작 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | `details` 에 필드별 메시지가 담김 → 폼에 표시 |
| `INVALID_PARAMETERS` | 422 | 작업 입력값 재확인 |
| `RESOURCE_NOT_FOUND` | 404 | **다른 사용자의 자원도 404** 로 옵니다. 403 을 기대하지 마세요 |
| `RESOURCE_VERSION_MISMATCH` | 412 | 재조회 후 다시 시도 |
| `IDEMPOTENCY_CONFLICT` | 409 | 같은 멱등키에 다른 본문 |

### 기기·작업

| code | HTTP | 프론트 동작 |
|---|---|---|
| `DEVICE_NOT_READY` | 422 | PC 연결 상태 확인 안내 |
| `DEVICE_BUSY` | 409 | 다른 작업 실행 중. 잠시 후 재시도 |
| `TASK_TYPE_NOT_SUPPORTED` | 422 | 이 PC 가 지원하지 않는 기능 |
| `TASK_EXPIRED` | 422 | 실행 기한 만료 |
| `UNRECOGNIZED_COMMAND` | 422 | **무슨 요청인지 알아내지 못함.** 다시 입력 유도 |
| `SEARCH_FOLDER_NOT_FOUND` | 422 | 검색 폴더 재선택 |
| `WORKSPACE_NOT_FOUND` | 422 | P1 |
| `CODE_AGENT_NOT_CONFIGURED` | 422 | P1 |
| `POLICY_DENIED` | 403 | 허용되지 않은 경로 또는 작업 |
| `AGENT_REJECTED` | 422 | PC 가 작업을 받지 않음. 사유를 알 수 없을 때 옵니다 |
| `AGENT_TASK_FAILED` | 422 | PC 에서 실행이 끝나지 못함. 사유를 알 수 없을 때 옵니다 |
| `DEVICE_REVOKED` | 403 | **등록이 해제된 PC 입니다.** `FORBIDDEN` 과 달리 다시 시도할 여지가 없으니 재등록을 안내해 주세요 |

> **`AGENT_REJECTED`·`AGENT_TASK_FAILED` 는 마지막 수단입니다.** PC 가 사유를 함께 보내면
> 위 표의 구체적인 코드(`TASK_TYPE_NOT_SUPPORTED`·`POLICY_DENIED` 등)로 옵니다. 사유가 없거나
> 우리가 모르는 값일 때만 이 둘로 옵니다. 즉 **이 코드가 보이면 사용자에게 알려줄 원인이
> 없다는 뜻**이므로, 원인을 짐작해 쓰지 말고 일반 실패 안내 + 재시도 유도로 처리하세요.

### 외부·내부 서비스

| code | HTTP | 프론트 동작 |
|---|---|---|
| `NLU_UNAVAILABLE` | 503 | 잠시 후 다시 안내. **자동 재시도 금지** |
| `LLM_NOT_READY` | 503 | AI 모델 준비 중 안내 |
| `UPSTREAM_UNAVAILABLE` | 503 | 외부 서비스 문제 안내 |
| `LOCATION_NOT_FOUND` | 422 | **날씨를 조회할 지역을 찾지 못함.** 503 과 달리 사용자가 다시 말하면 되는 상황이라 "시·군 이름으로 다시 말씀해 주세요" 로 안내해 주세요 |
| `UNSUPPORTED_SCHEMA_VERSION` | 422 | 클라이언트 갱신 필요 |
| `INTERNAL_ERROR` | 500 | 일반 오류 안내 + `requestId` 노출 |

---

## 5. 인증

인증은 **Amazon Cognito** 가 담당합니다.
slash-api 에는 로그인·토큰 갱신·로그아웃 API 가 **없습니다.** 프론트가 Cognito 와 직접 통신합니다.

```
프론트 → Cognito Managed Login (Authorization Code + PKCE)
      → code 수신 → Cognito 토큰 엔드포인트에서 직접 교환
      → Access Token → slash-api 호출 시 Authorization 헤더
```

| 항목 | 값 |
|---|---|
| 흐름 | Authorization Code + PKCE (Implicit 사용 안 함) |
| App Client | secret 없는 public 클라이언트 |
| API 에 보낼 토큰 | **Access Token** |
| 스코프 | `openid email profile` |
| Access Token 수명 | 60분 (권장값, Cognito 설정 시 확정) |
| Refresh Token 수명 | 7일 (권장값) |
| 갱신 시점 | 만료 5분 전 선제 갱신 |
| 401 처리 | 갱신 → 원요청 1회만 재시도 → 또 실패하면 로그인 |
| 로그아웃 | 토큰 폐기 후 Cognito `/logout?client_id=&logout_uri=` 리다이렉트 |

### 반드시 지켜야 하는 두 가지

**ID Token 을 보내면 거부됩니다.** slash-api 는 `token_use` 클레임을 확인해
Access Token 만 받습니다. 두 토큰은 같은 User Pool 이 서명하므로 실수하기 쉽습니다.

**스코프에 `email` 이 없으면 최초 로그인이 실패합니다.**
Cognito Access Token 에는 이메일이 없어서, 서버가 첫 로그인 때
Cognito `userInfo` 로 이메일을 받아 사용자 레코드를 만듭니다.

### 토큰 저장 위치

| 토큰 | 저장 위치 |
|---|---|
| Access Token | 메모리(변수) |
| Refresh Token | localStorage |

Refresh Token 을 localStorage 에 두면 XSS 에 노출됩니다.
P0 범위에서는 `oidc-client-ts` / Amplify 기본값을 그대로 쓰고,
대신 Access Token 수명을 60분으로 짧게 잡는 것으로 감수합니다.
더 안전하게 가려면 백엔드가 httpOnly 쿠키로 관리하는 BFF 구조로 바꿔야 하는데,
8/13 1차 시연 일정에는 맞지 않는다고 판단했습니다.

### 5.1 로컬 개발용 임시 인증

Cognito 값이 나오기 전까지 로컬에서 쓰는 임시 장치입니다.
**아무 문자열이나 Bearer 로 보내면 그 문자열의 사용자로 인증됩니다.**

```bash
curl -H "Authorization: Bearer alice" http://localhost:8080/api/v1/me
```

```json
{
  "data": {
    "userId": "6388f7f6-8aeb-4824-a4f5-33b1983b7e26",
    "email": "alice@local.test",
    "displayName": "alice",
    "timezone": "Asia/Seoul",
    "status": "ACTIVE",
    "createdAt": "2026-08-05T15:39:42.641183+09:00"
  },
  "meta": { "requestId": "...", "serverTime": "..." }
}
```

- 같은 문자열은 항상 같은 사용자입니다. 여러 사람을 흉내 내려면 `alice` · `bob` 처럼 바꿔 쓰세요.
- 처음 보낸 순간 사용자 레코드가 만들어집니다. 별도 가입 절차가 없습니다.
- 쓸 수 있는 문자: 영문·숫자·`.` `_` `-`, 최대 64자. 벗어나면 401 입니다.
- **`local` 프로필 전용입니다.** dev·demo 환경에서는 이 기능의 빈 자체가 만들어지지 않습니다.

프론트 코드는 토큰을 **어디서 얻었는지만 다르고** 나머지는 동일하게 짜시면 됩니다.
Cognito 로 바뀔 때 `Authorization` 헤더에 넣는 값의 출처만 교체하면 됩니다.

---

## 6. 인증 없이 호출할 수 있는 경로

```
/api/v1/health/**
/actuator/health
/actuator/info
/api/v1/agent/pair          로컬 Agent 전용
/api/v1/agent/pair/verify   로컬 Agent 전용
```

그 밖의 모든 경로는 `Authorization` 헤더가 필요합니다.

---

## 7. WebSocket

브라우저 `WebSocket` API 는 `Authorization` 헤더를 붙일 수 없습니다.
그래서 **30초·1회용 Ticket** 으로 접속합니다.

```
1. POST /api/v1/ws/ticket   (Bearer 인증)  →  201
   { "ticket": "...", "expiresIn": 30, "wsUrl": "ws://localhost:8080/ws/user" }

2. new WebSocket(`${wsUrl}?ticket=${ticket}`)     (expiresIn 안에 접속)

3. 연결이 끊기면 1번부터 다시. Ticket 은 재사용할 수 없습니다.
```

접속 주소를 코드에 박지 마세요. **`wsUrl` 을 그대로 쓰면 됩니다** — 환경마다 다릅니다.

Ticket 이 만료됐거나 이미 쓰였으면 **종료 코드 4401** 로 끊깁니다. 이때는 표를 새로
받아 다시 붙으세요. 재시도 간격을 두지 않으면 발급과 실패를 무한히 반복할 수 있습니다.

### 받게 되는 이벤트

| type | 담긴 것 | 화면 |
|---|---|---|
| `CONNECTED` | `connectionId`, `serverTime` | 접속 직후 서버가 먼저 보냅니다. 이걸 받아야 연결이 선 것입니다 |
| `TASK_STATUS_CHANGED` | `taskId`, `from`, `to`, `occurredAt` | 진행 표시 갱신 |
| `TASK_RESULT_AVAILABLE` | `taskId`, `status`, `resultPreview` | 이 신호를 받으면 `GET /api/v1/tasks/{taskId}` 로 본문을 받으세요 |
| `PONG` | `sentAt` | `{"type":"PING"}` 을 보내면 돌아옵니다 |

**Agent 채널과 형식이 다릅니다.** 이쪽에는 `schemaVersion`·`eventId` 같은 공통 필드가
없습니다. 원장이 아니라 화면을 빠르게 반영하기 위한 알림이라서입니다.

`resultPreview` 는 결과 앞 **200자**입니다. 결과는 64KB 까지 커질 수 있어 전체를 싣지
않습니다. 실패로 끝났으면 `null` 입니다.

WSS 는 화면을 빠르게 반영하기 위한 것이고,
**신뢰할 수 있는 최종 상태는 REST 입니다.**
연결이 끊긴 동안 놓친 이벤트는 REST 조회로 따라잡습니다.
WSS 만으로 화면 상태를 구성하지 마세요.

---

## 8. 기기 Token 은 프론트가 다루지 않습니다

PC 등록으로 발급되는 기기 Token(24시간)은 **로컬 Agent 전용**입니다.
사용자 Access Token 과 완전히 분리되어 있고, 프론트는 등록 코드 발급 화면까지만 관여합니다.

---

## 9. 미확정 항목 (TBD)

| 항목 | 필요한 곳 | 담당 |
|---|---|---|
| API Base URL (dev) | 전체 | 인프라 |
| Cognito User Pool ID / App Client ID | 로그인 | 최윤혁 |
| Cognito 도메인 (Hosted UI) | 로그인 | 최윤혁 |
| 콜백 / 로그아웃 리다이렉트 URI | 로그인 | 프론트 → 등록 요청 |
| WSS 주소 | 실시간 | 인프라 |
| CORS 허용 오리진 (dev 배포) | 전체 | 인프라 → 백엔드 설정 |

### CORS

현재 아래 오리진이 허용되어 있습니다.

| 환경 | 허용 오리진 | 상태 |
|---|---|---|
| 로컬 | `http://localhost:5173` | 프론트 확정 |
| dev | `CORS_ALLOWED_ORIGINS` 환경 변수로 주입 | 배포 도메인 미정 |

허용되는 요청 헤더는 `Authorization` · `Content-Type` · `Idempotency-Key` · `If-Match` 이고,
`ETag` 는 브라우저가 읽을 수 있도록 열어 두었습니다.
쿠키를 쓰지 않으므로 `Access-Control-Allow-Credentials` 는 보내지 않습니다.

목록에 없는 오리진은 preflight 에서 403 으로 막힙니다.
포트를 바꾸시거나 dev 배포 도메인(CloudFront)이 정해지면 알려주세요.

---

## 변경 이력

| 버전 | 날짜 | 내용 |
|---|---|---|
| v0.2 | 2026-08-05 | `GET /api/v1/me` 구현 완료. 로컬 임시 인증(5.1)과 CORS 확정 내용 반영 |
| v0.1 | 2026-08-05 | 최초 작성. 공통 헤더·응답 형식·오류 코드·Cognito 인증 흐름 |
