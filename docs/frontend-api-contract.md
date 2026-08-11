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
| 계약만 확정 | 기기 API (W1-03) |

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

접수 응답의 `statusUrl` 을 **2초 간격으로 폴링**해 주세요. 사용자 WSS 로 밀어 주는 것은
다음 단계입니다. 상태가 `SUCCEEDED`·`FAILED`·`EXPIRED` 중 하나가 되면 멈추면 됩니다.

#### 상태값

| status | 뜻 | 화면 |
|---|---|---|
| `ANALYZING` | 무슨 요청인지 분석 중 | 진행 표시 |
| `NEEDS_CLARIFICATION` | 되물어야 함 | `question` 을 보여주고 다시 입력받기 |
| `WAITING_FOR_DEVICE` | **PC 가 꺼져 있어 대기 중** | "PC 가 켜지면 실행됩니다" |
| `QUEUED` | PC 로 보냈고 수락 대기 | 진행 표시 |
| `RUNNING` | 실행 중 | 진행 표시 |
| `SUCCEEDED` | 완료 | `result` 표시 |
| `FAILED` / `EXPIRED` | 실패·기한 만료 | `errorCode` 로 안내 |

> **`WAITING_FOR_DEVICE` 를 오류로 다루지 마세요.** PC 가 꺼져 있어도 요청은 정상 접수됩니다.
> 사용자가 PC 를 켜면 그때 실행되고 상태가 알아서 넘어갑니다. 이게 데스크톱 앱으로는 안 되는
> 동작이라 시연에서 보여줄 장면이기도 합니다.

> `POST` 응답의 `status` 는 **고정값이 아닙니다.** 접수 시점에 이미 정해진 실제 상태가 옵니다.
> PC 가 붙어 있으면 `QUEUED`, 꺼져 있으면 `WAITING_FOR_DEVICE`, 못 알아들었으면 `FAILED` 입니다.

#### 지금 되는 명령

`/status` (시스템 상태) 하나가 종단까지 동작합니다. `/file` 은 검색 폴더를 저장하는 표가 아직
없어 대기 중이고, `/weather`·`/summary` 는 각각 외부 API·LLM 연결이 남아 있어 지금은
`UPSTREAM_UNAVAILABLE`·`LLM_NOT_READY` 로 마감됩니다. **화면은 네 개를 다 만들어 두셔도
됩니다** — 오류 코드로 구분해 안내만 다르게 하면 붙는 대로 그대로 동작합니다.

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

### 외부·내부 서비스

| code | HTTP | 프론트 동작 |
|---|---|---|
| `NLU_UNAVAILABLE` | 503 | 잠시 후 다시 안내. **자동 재시도 금지** |
| `LLM_NOT_READY` | 503 | AI 모델 준비 중 안내 |
| `UPSTREAM_UNAVAILABLE` | 503 | 외부 서비스 문제 안내 |
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
1. POST /api/v1/ws/ticket      (Bearer 인증)  →  { "ticket": "..." }
2. wss://{TBD}/ws/user?ticket=...             (30초 안에 접속)
3. 연결이 끊기면 1번부터 다시. Ticket 은 재사용할 수 없습니다.
```

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
