# W1-06 · WSS 멀티 Pod 라우팅 결정 문서

slash-api 는 최소 2 Pod 로 운영되는데 Agent 의 WSS 연결은 **특정 Pod 하나에만** 존재합니다.
다른 Pod 에서 발생한 이벤트를 연결 보유 Pod 로 넘기는 방법이 어느 문서에도 정의되어 있지 않습니다.
(`ws/package-info.java` 에 "미해결"로 표시된 항목)

이 문서는 선택지를 비교하고 **한 가지 안을 권고**합니다. 팀 결정이 끝나면 6장을 그대로 구현합니다.

| | |
|---|---|
| 버전 | v0.3 |
| 기준일 | 2026-08-12 |
| 담당 | 코어 API (김강찬) |
| 상태 | **A안 채택** (2026-08-06). 6장 구현 완료 — 사용자 WSS 까지(9장). 7장은 남은 확인 항목 |

---

## 1. 문제

```
[사용자 브라우저] ──REST──> Pod A   :  POST /api/v1/requests 로 작업 생성
                                        → Agent 에게 TASK 프레임을 보내야 한다
[사용자 PC Agent] ══WSS═══> Pod B   :  그런데 연결은 Pod B 에만 있다
```

Pod A 는 소켓을 갖고 있지 않으므로 아무것도 보낼 수 없습니다. 반대 방향도 같습니다.
Agent 가 Pod B 로 보낸 RESULT 를 Pod A 에 붙어 있는 사용자 브라우저에 알릴 수 없습니다.

영향 범위는 **로컬 PC 관련 기능 전체**입니다. 파일 검색, 상태 조회, 로컬 AI 실행이 전부 여기에 걸립니다.

### 오해하기 쉬운 점

Ingress **세션 고정(sticky session)으로는 해결되지 않습니다.**
세션 고정은 "같은 클라이언트를 같은 Pod 로" 보내는 장치인데, 여기서 문제가 되는 이벤트는
클라이언트가 아니라 **다른 Pod 의 서버 코드**에서 시작됩니다. 출발점이 클라이언트가 아니므로
고정할 대상 자체가 없습니다.

---

## 2. 이미 정해져 있어서 바꾸지 않는 것

선택지를 좁히는 기존 결정입니다.

| 항목 | 내용 | 출처 |
|---|---|---|
| Valkey 용도 | 상태 공유·짧은 잠금. **대기열로 쓰지 않는다** | 문서 3.8, `build.gradle.kts` |
| 비동기 작업 | SQS 가 담당 | 문서 3.8 |
| 신뢰의 기준 | WSS 는 빠른 화면 반영, **최종 원장은 REST/DB** | 문서 7.3.1 |
| 전달 원장 | `agent_dispatches` 에 시도를 기록하고, 재연결 뒤 미완료 전달을 재전송 | V005, `dispatch/package-info.java` |
| 중복 방지 | ACK·RESULT 는 `dispatchId` 기준으로 한 번만 반영 | `dispatch/package-info.java` |
| 일정 | 1차 시연 **8/13**, 기능 동결 **8/26** | 팀 일정 |

**마지막 세 줄이 이 결정의 난이도를 크게 낮춥니다.**
전달이 원장에 남고, 재연결 재전송이 이미 설계에 있고, 중복이 안전하다면
Pod 간 전달 경로는 **유실되어도 되는 빠른 길**이면 충분합니다. 보장 전달을 새로 만들 필요가 없습니다.

---

## 3. 선택지 비교

| | 방식 | 새로 필요한 것 | 8/13 가능 | 주요 위험 |
|---|---|---|---|---|
| **A** | Valkey Pub/Sub **브로드캐스트** — 전체 Pod 에 뿌리고 연결 보유 Pod 만 처리 | 없음 (Valkey·의존성 이미 있음) | ✅ 여유 | 전달 보장 없음 (유실 시 스윕이 복구) |
| **B** | Valkey Pub/Sub **지목 전송** — 연결 소유 Pod 를 조회해 그 Pod 채널로만 발행 | 연결 레지스트리 키 | ✅ 가능 | 레지스트리와 실제 연결의 불일치 구간 |
| **C** | Pod 간 직접 HTTP — 레지스트리에서 Pod IP 를 찾아 내부 호출 | Headless Service, mTLS 또는 내부 인증, 재시도 | ⚠️ 빠듯 | 인프라 협의 필요, 실패 처리 코드가 늘어남 |
| **D** | WSS 전용 Deployment **replica 1** 로 고정 | Helm 분리 | ✅ 쉬움 | 단일 장애점. HPA·무중단 배포 포기. NFR 위반 |
| **E** | 푸시 없이 Agent 폴링 | 폴링 API | ✅ 쉬움 | 응답 지연, WSS 설계 폐기. 프로토콜 문서와 충돌 |
| **F** | API Gateway WebSocket API 로 연결 관리 위임 | 인프라 전면 변경, Ed25519 핸드셰이크 재설계 | ❌ 불가 | 8/13 안에 불가능. 비용 구조도 재검토 대상 |

**A 와 B 는 배타적인 선택이 아닙니다.** 호출부 API 가 같아서(`send(target, frame)`) 채널 이름만
바뀝니다. A 로 시작해 Pod 수가 늘어나면 B 로 승급하는 것이 자연스럽습니다.

### C·D·E·F 를 권고하지 않는 이유

- **C** 는 A/B 로 충분한 문제에 인프라 협의(slash-infra)와 장애 처리 코드를 추가로 요구합니다.
  Pod 재시작 중 IP 가 바뀌는 구간을 직접 다뤄야 하는데, 이득이 A/B 대비 거의 없습니다.
- **D** 는 시연 하루 전에 급하게 쓸 수 있는 **탈출구**로만 남깁니다. NFR-07(가용성)과 정면 충돌합니다.
- **E** 는 프로토콜 정의(HELLO·CHALLENGE·TASK·ACK·RESULT)를 사실상 버리는 선택입니다.
- **F** 는 일정상 논외입니다. 8/26 이후 운영 단계에서 다시 볼 만합니다.

---

## 4. 권고 — A안 (Valkey Pub/Sub 브로드캐스트) + 연결 레지스트리

세 가지 이유입니다.

1. **새로 도입할 것이 없습니다.** Valkey 는 이미 `docker-compose.yml` 과 `spring-boot-starter-data-redis`
   로 들어와 있고, dispatch 의 짧은 잠금도 여기를 씁니다. 인프라 티켓이 필요 없습니다.
2. **유실이 사고로 이어지지 않습니다.** `agent_dispatches` 가 원장이고 재연결 재전송이 이미 범위 안이라,
   Pub/Sub 이 놓친 건은 스윕(5.4)이 주워 갑니다.
3. **되돌리기 쉽습니다.** 발행/구독 지점이 각각 한 곳이라, B안 승급도 D안 후퇴도 코드 한 군데입니다.

### "Valkey 를 대기열로 쓰지 않는다"에 어긋나지 않습니다

이 규칙은 **작업을 쌓아 두고 소비자가 꺼내 가는 구조**를 금지하는 것입니다(그 역할은 SQS).
Pub/Sub 은 쌓지 않습니다. 구독자가 없으면 그냥 사라집니다.
저장·순서·재시도 책임은 전부 PostgreSQL(`agent_dispatches`)에 남고, Valkey 는 "지금 연결을
쥔 Pod 야, 이거 보내라"는 **신호만** 옮깁니다. 규칙의 의도와 충돌하지 않습니다.

---

## 5. 설계

> **A안이 실제로 필요로 하는 것은 5.3·5.4·5.5 뿐입니다.**
> 5.1(Pod 식별자)과 5.2(연결 레지스트리)는 **라우팅에 필요하지 않습니다.**
> 브로드캐스트는 각 Pod 이 "이 소켓을 내가 들고 있는가"만 확인하면 되고, 그 답은 Pod 안의
> 소켓 보관소에 이미 있습니다. 조회할 외부 상태가 없습니다.
>
> 5.1·5.2 는 **B안 승급용**이자 기기 온라인 판정 개선용으로 남겨 둡니다.
> 따라서 `POD_NAME` 주입과 Cluster mode 확인은 **착수를 막지 않습니다.**

### 5.1 Pod 식별자 (A안에서는 선택 사항 — 추적용)

K8s Downward API 로 Pod 이름을 넣고, 없으면(로컬) 기동 시 임의 값을 만듭니다.

```yaml
# Helm — slash-infra 에 요청할 항목
env:
  - name: POD_NAME
    valueFrom:
      fieldRef:
        fieldPath: metadata.name
```

### 5.2 Valkey 키 (A안에서는 선택 사항 — 온라인 판정·B안 승급용)

| 키 | 값 | TTL | 갱신 시점 |
|---|---|---|---|
| `ws:device:{deviceId}:pod` | Pod 이름 | 90초 | HEARTBEAT 수신마다 |
| `ws:user:{userId}:pods` | Pod 이름 Set | 90초 | 사용자 WSS Heartbeat |

TTL 90초는 `slash.device.offline-threshold` 와 같은 값입니다(Heartbeat 30초 × 3회 누락).
**설정에서 파생시키고 상수로 박지 않습니다.**

이 키는 라우팅 승급(B안)뿐 아니라 **"이 기기가 지금 실제로 붙어 있는가"** 판정에도 씁니다.
`devices.status` 열은 Pod 이 갑자기 죽으면 ONLINE 인 채로 남지만, 이 키는 TTL 로 알아서 사라집니다.

### 5.3 채널과 봉투

```
채널  slash:ws:device      — 모든 Pod 이 구독 (A안)
      slash:ws:user
      slash:ws:pod:{podName} — B안 승급 시 사용
```

```json
{
  "target": "DEVICE",
  "targetId": 42,
  "frame": "TASK",
  "dispatchId": "3f1c…",
  "payload": { },
  "issuedAt": "2026-08-06T10:47:00+09:00",
  "originPod": "slash-api-7d9f-abcde"
}
```

수신 Pod 은 `targetId` 소켓을 **자기가 들고 있을 때만** 처리하고, 없으면 조용히 버립니다.
`originPod` 은 자기가 발행한 것을 되받는 경우를 거르고 추적할 때 씁니다.

### 5.4 전달 흐름

```
Pod A                              Valkey            Pod B (연결 보유)
  │ 1. agent_dispatches INSERT (PENDING)
  │ 2. 기기별 짧은 잠금 획득
  │ 3. PUBLISH slash:ws:device ──────>│──────────────>│ 4. 내 소켓인가? yes
  │                                                   │ 5. TASK 프레임 전송
  │                                                   │ 6. PENDING → DISPATCHED
  │                                                        (dispatched_at 기록)
```

**5번과 6번 사이에 Pod B 가 죽으면** 행은 PENDING 으로 남습니다. 이것이 복구 지점입니다.

**스윕(유실 복구)** — 주기적으로 아래를 다시 발행합니다.

```sql
SELECT … FROM agent_dispatches
 WHERE status = 'PENDING' AND expires_at > now()
```

여러 Pod 이 동시에 스윕해 중복 발행해도 안전합니다. 기기별 짧은 잠금이 대부분을 걸러 내고,
통과하더라도 Agent 는 `dispatchId` 로 중복을 무시하도록 이미 설계돼 있습니다.
스윕 주기는 **5초**를 제안합니다(재연결 재전송이 주 경로, 스윕은 그물).

### 5.5 종료 처리

`server.shutdown: graceful` 은 이미 켜져 있습니다. 추가로 필요한 것:

- SIGTERM 을 받으면 Agent 소켓을 **1012(Service Restart)** 로 닫아 즉시 재연결을 유도합니다.
- 재연결 시 미완료 전달 재전송이 공백을 메웁니다(이미 dispatch 범위).
- Helm 에 `preStop` 지연이 필요합니다 — slash-infra 협의 항목.

### 5.6 로컬에서 재현하는 법

멀티 Pod 문제는 1개 Pod 으로는 절대 드러나지 않습니다. 시험은 반드시 2개를 띄웁니다.

```bash
./gradlew bootRun                                   # 8080
SERVER_PORT=8081 ./gradlew bootRun                  # 8081 — 같은 Valkey 를 본다
# Agent 는 8081 에 붙이고, 작업 생성은 8080 으로 호출한다
```

---

## 6. 구현 순서

| 순서 | 내용 | 상태 |
|---|---|---|
| 1 | `/ws/agent` 핸들러 + Pod 내 소켓 보관소 (`WsSessionRegistry`) | **완료** (2026-08-06) |
| 2 | `WsMessagePublisher` / `WsMessageListener` — 채널·봉투 | **완료** — 호출부는 `send(target, targetId, frame)` 만 본다 |
| 3 | 2개 Pod 확인 (5.6) | **완료** — 아래 기록 |
| 4 | PENDING 스윕 스케줄러 | **완료** (2026-08-06) — `PendingDispatchSweeper` |
| 5 | `/ws/user` 에 같은 경로 적용 | **완료** (2026-08-12) — 아래 기록 |
| 6 | `PodIdentity` — POD_NAME 주입 | 추적용. 지금은 발행 Pod 표시만 |
| 7 | `WsConnectionRegistry` — 5.2 키 | 온라인 판정 개선·B안 승급 시 |

### 2 Pod 확인 기록 (2026-08-06)

8080·8081 두 인스턴스를 띄우고, Agent 는 **8081 에만** 연결한 뒤 바깥 프로세스에서 발행했다.

```
$ docker exec -i slash-valkey valkey-cli -x PUBLISH slash:ws:device < envelope.json
2                       ← 구독자 2 = 두 Pod 모두 받았다
```

| 확인 | 결과 |
|---|---|
| 연결을 가진 8081 이 프레임 전달 | `WSS 프레임 전달 target=DEVICE targetId=721 연결=1 발행Pod=pod-a-흉내` |
| 연결이 없는 8080 은 조용히 버림 | WSS 로그 0줄, 오류 0건 |
| Agent 가 받은 내용 | 봉투가 벗겨진 프레임만 (`target`·`originPod` 없음) |
| 인증 → READY → 종료 | `devices.status` 가 ONLINE → READY → OFFLINE, `device_capabilities` 반영 |

발행자가 애플리케이션이 아니라 `valkey-cli` 인 것이 핵심이다.
"다른 프로세스가 발행하고, 연결을 쥔 쪽만 내보낸다"는 이 방식의 전제를 그대로 재현한다.

### 스윕 확인 기록 (2026-08-06)

같은 2 Pod 구성에서, **1분 전에 만들어졌는데 아직 PENDING 인 전달**을 DB 에 직접 넣어
유실 상황을 만들었다. (`INSERT ... created_at = now() - interval '1 minute'`)

```
만든 전달:   445|da0c61e7-…|PENDING
재발행 로그: 미전달 작업 재발행 1건 (대상 1건)      ← 두 Pod 중 한 쪽에서만
Agent 수신:  {"type":"TASK","dispatchId":"da0c61e7-…","taskType":"FILE_SEARCH",
              "parameters":{"query":"보고서"},"expiresAt":"2026-08-06T11:21:27+09:00"}
전달 상태:   DISPATCHED | attempt_count=1 | dispatched_at 기록됨
```

| 확인 | 결과 |
|---|---|
| 유실된 전달을 스윕이 주워 재발행 | 5초 주기 안에 1회 |
| 두 Pod 이 동시에 스윕해도 발행은 한 번 | Valkey 짧은 잠금이 걸러냄 (로그 1건) |
| 실제로 나간 뒤에야 DISPATCHED | `attempt_count=1`, 재발행 대상에서 빠짐 |
| 오류 | 0건 |

---

## 8. 프레임 계약 정렬 (2026-08-06)

처음 구현할 때는 프레임 필드 이름을 스키마에서 유추했다. 이후 **실제 계약이 코드로 존재한다**는
것을 확인했다. 문서 저장소가 아니라 slash-agent 안에 있다.

| 무엇 | 어디 |
|---|---|
| 계약 원본 (zod 스키마) | `slash-agent/contracts/src/agentMessages.ts` |
| 서버 쪽 참조 구현 | `slash-api` 의 `slash-api-test` 브랜치 `mock-api/src/agentWss.ts` |
| 실제 주고받는 JSON 예시 | `slash-agent/docs/MESSAGE_GUIDE.md` |

**앞으로 계약을 확인할 때는 여기를 먼저 본다.** `slash-docs` 에는 아직 없다.

대조해서 고친 것:

| 어긋난 곳 | 결과 |
|---|---|
| 서명 대상이 nonce 원본 바이트 | 인증이 **항상** 실패 → `challengeId:nonce:deviceId` 문자열로 |
| RESULT 를 `ok` boolean 으로 판정 | 성공이 **전부 실패로 기록** → `status: "SUCCEEDED"` 로 |
| 공통 필드(`schemaVersion`·`eventId`·`sentAt`) 없음 | Agent 의 zod 가 메시지를 통째로 거부 → 전 프레임에 추가 |
| CHALLENGE 에 `challengeId`·`expiresAt` 없음 | AUTH 를 대조할 수 없음 → 추가 |
| 오류를 종료 코드로만 알림 | Agent 가 이유를 모른 채 재접속 반복 → `PROTOCOL_ERROR` 프레임으로 |
| TASK 에 `payloadSha256` 없음 | 형식 검증 실패 → 참조 구현과 같은 방식으로 계산 |

**라우팅 계층은 한 줄도 고치지 않았다.** 발행·구독·소켓 보관소·스윕은 프레임 모양과 무관하다.
채택안을 고를 때 "되돌리기 쉬운가"를 근거로 삼은 것이 여기서 값을 했다.

### 왕복 확인 기록

시뮬레이터를 계약대로 고쳐(서명 대상·공통 필드) 2 Pod 구성에서 다시 확인했다.

```
전달 상태: COMPLETED | attempt_count=1 | dispatched_at ✓ | acknowledged_at ✓ | completed_at ✓
```

TASK 재발행 → Agent 의 ACK → RESULT 까지 한 번에 이어졌다.

**이 확인에서 결함 하나를 찾았다.** 첫 실행에서 `acknowledged_at` 만 비어 있었다.
전달은 소켓에 쓴 <b>뒤</b> DISPATCHED 로 기록하는데, Agent 의 ACK 가 그 기록보다 먼저 도착한다.
`acknowledge()` 가 DISPATCHED 상태만 받고 있어서 그 ACK 가 조용히 버려지고 있었다.
ACK 가 왔다는 것은 프레임이 나갔다는 뜻이므로, PENDING 상태의 ACK 도 받고 `dispatched_at` 을
함께 채우도록 고쳤다. 시험만으로는 드러나지 않는 종류의 결함이다.

---

## 7. 팀이 정해야 하는 것

- [x] **A안 채택** (2026-08-06). 문서 3.8 에 "Pub/Sub 은 Pod 간 신호 용도로 허용" 한 줄을 추가해야 합니다.
      — 착수와 무관하나 문서 정합성 때문에 필요합니다.
- [ ] **slash-infra** — ElastiCache for Valkey 의 **Cluster mode** 여부.
      일반 Pub/Sub 은 Cluster mode 에서도 클러스터 버스로 전파되므로 **A안 착수를 막지 않습니다.**
      Pod 수가 늘어 샤디드 Pub/Sub 으로 옮길 때 필요한 정보입니다.
      `preStop` 지연(5.5)은 별건으로 요청합니다 — 이건 무중단 배포에 실제로 필요합니다.
- [ ] **HPA 최대 replica 수.** B안으로 언제 승급할지의 판단 기준입니다.
      대략 **4 이하면 A 로 충분**하고, 그 이상이면 B 를 넣는 편이 낫습니다.
- [x] **slash-agent** — 재연결 시 미완료 전달 재수신, 같은 전달 중복 무시.
      **이미 구현되어 있습니다** (2026-08-06 확인). `taskId:dispatchId` 를 키로 캐시해 재실행 없이
      기존 ACK·RESULT 를 돌려주고, 재연결 시 `resendUnackedResults` 로 다시 보냅니다.
      다만 **Agent 는 `RESULT_ACK` 를 받아야 그 캐시를 지웁니다.** 우리가 아직 보내지 않으므로
      재연결마다 과거 결과를 다시 보냅니다. 데이터는 안전하지만(활성 상태에서만 반영)
      W1-04 에서 반드시 닫아야 하는 항목입니다.
- [x] **8/13 시연 범위** — 사용자 WSS 알림(`/ws/user`)을 **같은 경로로 넣었습니다** (2026-08-12).
      화면 재조회로 갈음하지 않습니다. 9장 참고. 프론트는 WSS 에 붙지 않아도 폴링으로 동작하므로
      이것이 시연을 막는 항목은 아닙니다.

> 위 항목이 막히더라도 **D안(WSS replica 1)** 이 시연용 탈출구로 남아 있습니다.
> 다만 그 경우 무중단 배포와 HPA 를 시연 기간 동안 포기하는 것이라, 결정으로 남겨야 합니다.

---

## 9. 사용자 WSS 적용 (2026-08-12)

6장 5번을 닫은 기록입니다. `/ws/user` 는 Agent 채널과 **같은 발행·구독 경로를 그대로** 씁니다.
채널만 `slash:ws:user` 로 갈리고 봉투·보관소·수신 판정은 한 코드입니다.

| 무엇 | 어디 |
|---|---|
| 접속표 발급 (30초·1회용, 해시 저장) | `WsTicketController` · `UserWsTicketStore` |
| 소켓 처리 (표 검증 · CONNECTED · PING/PONG) | `UserWebSocketHandler` |
| 상태 전이 → 알림 발행 | `TaskStateWriter` → `UserEventPublisher` |

### 두 채널이 다른 점

| | Agent `/ws/agent` | 사용자 `/ws/user` |
|---|---|---|
| 인증 | 기기 Token + Ed25519 도전값 서명 | 30초·1회용 접속표 (URL 질의) |
| Origin | 데스크톱 앱이라 보내지 않음 → 검사 없음 | 브라우저 → REST 와 같은 허용 목록 |
| 연결 수 | 기기당 1개 (옛 연결을 밀어냄) | 탭마다 1개 (밀어내지 않음) |
| 공통 필드 | `schemaVersion`·`eventId`·`sentAt` 필수 | 없음. 원장이 아니라 알림이라서 |
| 실패 시 | 전달 원장이 복구 | 복구하지 않음. REST 재조회로 따라잡음 |

접속표를 **Valkey 에 두는 이유**는 발급(REST)과 접속(WSS)이 다른 Pod 로 갈리기 때문입니다.
Pod 메모리에 두면 2 Pod 에서 절반이 실패합니다. 라우팅과 같은 이유의 같은 제약입니다.

### 알림은 커밋 뒤에 나간다

상태 전이는 트랜잭션 안에서 일어나고 롤백될 수 있습니다. 트랜잭션 안에서 그대로 발행하면
**일어나지 않은 전이가 화면에 뜹니다.** `UserEventPublisher` 가 `afterCommit` 으로 미루고,
발행 실패는 삼킵니다 — 화면이 늦는 것은 불편이지만 전이가 취소되는 것은 사실이 달라지는 일입니다.

### 이 과정에서 찾은 결함 — 소켓 동시 쓰기

`WsSessionRegistry` 는 등록할 때 세션을 `ConcurrentWebSocketSessionDecorator` 로 감싸
전송을 직렬화합니다. 그런데 두 핸들러가 **등록한 뒤에도 원본 세션에 직접 쓰고 있었습니다.**
데코레이터는 자기를 거쳐 간 호출끼리만 직렬화하므로, 그 잠금을 통째로 우회합니다.

```
브라우저가 PING → 핸들러 스레드가 PONG 을 원본 세션에 쓴다
동시에            Pub/Sub 스레드가 TASK_STATUS_CHANGED 를 데코레이터로 쓴다
                → 같은 소켓에 두 스레드가 동시에 write
                → IllegalStateException (TEXT_FULL_WRITING)
                → deliver 가 끊긴 연결로 보고 소켓을 닫는다
```

**PING 을 보냈다는 이유로 알림 채널이 죽습니다.** Agent 채널도 같았습니다 — RESULT_ACK 를 쓰는
동안 Pub/Sub 이 TASK 를 밀어 넣을 수 있습니다. 이번 작업이 만든 것이 아니라 넓힌 것입니다.

`WsSessionRegistry.guarded()` 가 감싼 세션을 내주고 두 핸들러의 `send` 가 그것을 거치게 고쳤습니다.
등록 전(Agent 의 CHALLENGE 등)에는 원본을 그대로 돌려줍니다 — 보관소에 없는 소켓은 다른 스레드가
찾을 길이 없어 경쟁 자체가 없습니다.

> **시험으로는 드러나지 않는 종류입니다.** 8장의 `acknowledged_at` 결함과 같습니다.
> 단일 스레드 시험에서는 두 경로가 겹치지 않습니다. 회귀 시험은 "등록된 소켓에 직접 쓸 때도
> 보관소가 감싼 같은 인스턴스를 준다"를 고정하는 방식으로 넣었습니다.

### 남은 것

`TaskRepository.expireOverdue` 는 대량 UPDATE 라 `TaskStateWriter` 를 거치지 않아 알림이
나가지 않습니다. **지금은 호출자가 없어 드러나지 않지만**, 만료 배치를 붙이는 쪽이
`user_id`·`public_id` 를 받아 함께 발행해야 합니다. 그러지 않으면 만료된 작업의 화면이
새로고침 전까지 "진행 중"에 머뭅니다. 정의부에 경고를 적어 두었습니다.
