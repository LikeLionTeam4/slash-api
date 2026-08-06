# W1-06 · WSS 멀티 Pod 라우팅 결정 문서

slash-api 는 최소 2 Pod 로 운영되는데 Agent 의 WSS 연결은 **특정 Pod 하나에만** 존재합니다.
다른 Pod 에서 발생한 이벤트를 연결 보유 Pod 로 넘기는 방법이 어느 문서에도 정의되어 있지 않습니다.
(`ws/package-info.java` 에 "미해결"로 표시된 항목)

이 문서는 선택지를 비교하고 **한 가지 안을 권고**합니다. 팀 결정이 끝나면 6장을 그대로 구현합니다.

| | |
|---|---|
| 버전 | v0.2 |
| 기준일 | 2026-08-06 |
| 담당 | 코어 API (김강찬) |
| 상태 | **A안 채택** (2026-08-06). 구현 착수. 7장은 남은 확인 항목 |

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
| 5 | `/ws/user` 에 같은 경로 적용 | 시연 범위 결정에 따라 (7장) |
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

## 7. 팀이 정해야 하는 것

- [x] **A안 채택** (2026-08-06). 문서 3.8 에 "Pub/Sub 은 Pod 간 신호 용도로 허용" 한 줄을 추가해야 합니다.
      — 착수와 무관하나 문서 정합성 때문에 필요합니다.
- [ ] **slash-infra** — ElastiCache for Valkey 의 **Cluster mode** 여부.
      일반 Pub/Sub 은 Cluster mode 에서도 클러스터 버스로 전파되므로 **A안 착수를 막지 않습니다.**
      Pod 수가 늘어 샤디드 Pub/Sub 으로 옮길 때 필요한 정보입니다.
      `preStop` 지연(5.5)은 별건으로 요청합니다 — 이건 무중단 배포에 실제로 필요합니다.
- [ ] **HPA 최대 replica 수.** B안으로 언제 승급할지의 판단 기준입니다.
      대략 **4 이하면 A 로 충분**하고, 그 이상이면 B 를 넣는 편이 낫습니다.
- [ ] **slash-agent** — 재연결 시 미완료 전달을 다시 받는 것과, 같은 `dispatchId` 를 두 번 받으면
      무시하는 것. 두 가지가 Agent 쪽에도 구현되어야 5.4 의 안전성이 성립합니다.
- [ ] **8/13 시연 범위** — 사용자 WSS 알림(`/ws/user`)도 같은 경로로 넣을지, 시연은 화면 재조회로
      갈음할지. 후자면 6장의 5번을 8/26 쪽으로 미룹니다.

> 위 항목이 막히더라도 **D안(WSS replica 1)** 이 시연용 탈출구로 남아 있습니다.
> 다만 그 경우 무중단 배포와 HPA 를 시연 기간 동안 포기하는 것이라, 결정으로 남겨야 합니다.
