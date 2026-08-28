# Slash | 코어 API

Slash(/)는 자연어 질문과 `/` 슬래시 명령어를 한 입력창에서 처리하는 AI 비서 서비스입니다.
이 저장소는 그중 **코어 API** 파트를 담당합니다.

## 역할

- 인증
- 작업 관리
- **실행 위치 결정** — 브라우저(`BROWSER`) · 사용자 PC(`RUNNER`) · 서버(`BACKEND`)
- DB 연동

## 지금 되는 것

일곱 가지다. 슬래시 명령으로도, 평범한 말로도 접수된다 — 브라우저는 가르지 않고 입력창의
한 줄을 그대로 보내고, 무엇을 시켰는지는 slash-nlu 가 판단한다.

| 명령 | 하는 일 | 실행 위치 |
|---|---|---|
| `/status` | PC 상태 조회 | 사용자 PC |
| `/file` | PC 파일 검색 | 사용자 PC |
| `/open` | 찾은 파일의 위치를 파일 탐색기로 표시 | 사용자 PC |
| `/code` | 로컬 AI 도구로 코드 분석 (읽기 전용) | 사용자 PC |
| `/usage` | 로컬 AI 도구 사용량 조회 | 사용자 PC |
| `/weather` | 날씨 조회 (Open-Meteo) | 서버 |
| `/summary` | 텍스트 요약 | 브라우저 · 서버 · PC |

**`/summary` 만 실행 위치가 셋으로 갈린다.** WebGPU 를 지원하는 브라우저는 원문을 밖으로
내보내지 않고 그 자리에서 요약한 뒤 **결과만** 제출한다. 그렇지 않으면 서버가 CPU 추출
요약으로 처리하고, **요약이 끝나면 원문을 갖고 있지 않는다.** 자세한 것은
[프론트엔드 연동 규약](docs/frontend-api-contract.md)에 있다.

## 시작하기

### 요구 사항

- JDK 21
- Docker (로컬 PostgreSQL·Valkey 구동용)

### 로컬 실행

```bash
# 1. 로컬 DB 기동 (PostgreSQL 16, Valkey 8)
docker compose up -d

# 2. 마이그레이션 적용 + jOOQ 코드 생성
./gradlew generateJooq

# 3. 애플리케이션 실행
./gradlew bootRun
```

### 로컬 인증

기본은 **임시 인증**이다. 아무 문자열을 Bearer 로 보내면 그 문자열의 사용자가 된다.

```bash
curl -H "Authorization: Bearer alice" http://localhost:8080/api/v1/me
```

실제 Cognito 로 붙으려면 `.env` 에 값을 채운다.
**채우는 순간 임시 인증은 자동으로 꺼진다.** 설정 파일은 고치지 않는다.

```bash
cp .env.example .env    # 값을 채운 뒤
./gradlew bootRun
```

값은 팀 채널에서 받는다. `.env` 는 저장소에 올리지 않는다.

- `COGNITO_ISSUER_URI` 가 전환 스위치다. 이 값이 비어 있으면 나머지를 채워도 임시 인증을 쓴다.
- 발급자만 넣고 나머지를 빠뜨리면 기동 시점에 어떤 값이 없는지 알려주고 멈춘다.
- 시험(`./gradlew test`)은 이 값을 무시한다. 항상 임시 인증으로 돈다.

IDE 에서 실행한다면 `.env` 가 아니라 실행 구성의 환경 변수에 같은 값을 넣는다.
(`.env` 를 읽는 것은 `bootRun` 이다)

### 스키마를 변경할 때

1. `src/main/resources/db/migration/` 에 새 마이그레이션을 추가한다.
   - 파일명 `V{번호}__{설명}.sql`
   - 번호는 서비스 백엔드 담당자가 배정한다
   - **이미 공유된 마이그레이션은 수정하지 않고 새 번호를 추가한다**
2. `./gradlew generateJooq` 로 적용·재생성한다.
3. 생성 코드(`src/generated`)와 쿼리 시험을 **함께** 커밋한다.

### 프로필

| 프로필 | 용도 | DB |
|---|---|---|
| `local` (기본값) | 로컬 개발 | Docker PostgreSQL |
| `dev` | Dev EKS | Dev RDS (`slash_dev`) |
| `demo` | 시연 | Dev RDS (`slash_demo`) |

```bash
# 로컬은 별도 지정 없이 실행하면 된다
./gradlew bootRun

# 다른 프로필로 실행할 때
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

### 환경 변수

`local` 은 값이 모두 기본 설정되어 있어 별도 주입이 필요 없다. `dev`·`demo` 는 Helm
차트(`slash-infra/helm/slash-api/values-<env>.yaml`)가 주입한다.

**Secrets Manager 를 거치는 것은 DB 자격증명 둘뿐이다**(`DB_USERNAME`·`DB_PASSWORD` —
External Secrets 가 동기화한다). 나머지는 평문 env 다. RDS·Valkey 엔드포인트와 Cognito
값은 공개 클라이언트가 쓰는 값이라 비밀이 아니다.

| 변수 | 설명 |
|---|---|
| `DB_URL` | PostgreSQL 접속 주소 |
| `DB_USERNAME` / `DB_PASSWORD` | DB 자격증명 |
| `VALKEY_HOST` / `VALKEY_PORT` | ElastiCache for Valkey |
| `COGNITO_ISSUER_URI` | Cognito User Pool 발급자 주소 |
| `COGNITO_CLIENT_ID` | App Client ID. 다른 Client 의 토큰을 거르는 기준 |
| `COGNITO_USER_INFO_URI` | Hosted UI 의 `/oauth2/userInfo`. 최초 로그인 시 이메일을 받아온다 |
| `CORS_ALLOWED_ORIGINS` | 웹 클라이언트 오리진 (쉼표 구분) |
| `NLU_BASE_URL` | slash-nlu 내부 주소 (예: `http://slash-nlu`). 뒤 경로는 코드가 붙인다 |

동작을 바꾸는 값들이다. 모두 기본값이 있어 넣지 않아도 돌지만, 넣으면 **배포 없이** 정책이
바뀐다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `SUMMARY_ENGINE` | `EXTRACTIVE` | 요약 엔진. `EXTRACTIVE` 는 slash-nlu 의 CPU 추출 요약이다. GPU 경로로 되돌리려면 `LLM_BASE_URL` 도 함께 채워야 한다 |
| `TEXT_SUMMARY_RUNNER_ENABLED` | `false` | 선택한 PC 가 요약 능력을 보고했을 때 그 PC 로 보낼지. `false` 여도 요약 자체는 막히지 않고 서버가 대신 한다 |
| `APPROVAL_REQUIRED_TASK_TYPES` | (비어 있음) | 실행 전에 사용자 확인을 받을 작업 유형. 지금은 해당하는 유형이 없다 |
| `TRUSTED_PROXY_HOPS` | `0` | 믿을 수 있는 프록시 단 수. **실제보다 작으면 위조된 주소를 믿게 되므로** 인프라 담당자와 확인하고 넣는다 |

> 자격증명을 저장소에 평문으로 두지 않는다. Namespace 를 코드에 고정하지 않는다.

### 코드 규칙

- **시각은 전 구간 한국 시각으로 통일한다.** API·WSS·SQS JSON 은 `2026-08-04T10:47:00+09:00` 형식,
  DB 열은 `timestamptz`, JDBC Session 과 SQL 조회는 `Asia/Seoul` 이다.
  `Instant` 는 오프셋이 없어 항상 `Z` 로 직렬화되므로 DTO 에 사용하지 않고 `OffsetDateTime` 을 쓴다.
  (V001 주석에 남은 "UTC 저장" 표현은 이미 공유된 마이그레이션이라 수정하지 않았다)
- 내부 PK 는 `bigint`, 외부 노출 식별자는 `uuid public_id` 를 사용한다.
- 상태값은 PostgreSQL Enum 대신 `varchar` + `CHECK` 로 관리한다.
- `DSLContext` 는 Repository 계층 안에서만 사용하고, 생성된 jOOQ Record 를 서비스 밖으로 내보내지 않는다.

## 구조

```
src/
├── main/
│   ├── java/com/likelion/slash/     # 애플리케이션 코드
│   └── resources/
│       ├── application.yml
│       └── db/migration/            # Flyway 마이그레이션
├── generated/jooq/                  # jOOQ 생성 코드 (커밋 대상, 직접 수정 금지)
└── test/java/com/likelion/slash/
```

패키지는 도메인 단위로 나눈다. 각 패키지의 담당 범위는 `package-info.java`에 적혀 있다.

| 패키지 | 담당 |
|---|---|
| `auth` | Cognito JWT 검증, 사용자 매핑 |
| `pairing` | PC 등록 코드 발급·서명 검증 |
| `device` | 등록 PC 관리, 소유권 강제, 실행 가능 여부 판정 |
| `task` | 작업 원장, 상태 전이, 처리 경로·실행 위치 결정 |
| `dispatch` | PC 전달 이력, 만료 처리 |
| `ws` | Agent·사용자 WebSocket 게이트웨이 |
| `approval` | 실행 전 사용자 확인 정책 |
| `nlu` `llm` `weather` | 외부·내부 서비스 연동 |
| `audit` `job` `health` `common` `config` | 감사, 배치, 상태 점검, 공통 |

## 문서

| 문서 | 대상 | 내용 |
|---|---|---|
| [프론트엔드 연동 규약](docs/frontend-api-contract.md) | slash-web | 공통 헤더, 응답·오류 형식, 오류 코드, Cognito 인증 흐름, WSS Ticket |
| [W1-06 WSS 멀티 Pod 라우팅](docs/w1-06-wss-routing.md) | 팀 · slash-infra | Pod 간 이벤트 전달 — 선택지 비교와 채택안(Valkey Pub/Sub) |
| [부하 시험](docs/load-test/README.md) | 팀 | 어디가 먼저 막히는가 — 경로별 포화 처리량과 병목의 위치 |
| [기능 동결 시점 상태](docs/feature-freeze-2026-08-26.md) | 팀 | 2026-08-26 기준 일곱 명령 종단 확인 결과와 남긴 것 |

## 관련 저장소

| 저장소 | 역할 |
|---|---|
| [slash-web](https://github.com/LikeLionTeam4/slash-web) | 웹 클라이언트 — React·Vite UI, S3/CloudFront 배포 |
| **slash-api** (현재) | 코어 API — 인증, 작업 원장, 실행 위치 결정, WSS 게이트웨이 |
| [slash-nlu](https://github.com/LikeLionTeam4/slash-nlu) | 자연어 분석 — slash 명령 파싱, 규칙·Kiwi 의도 분류, 인자 추출, CPU 추출 요약 |
| [slash-llm](https://github.com/LikeLionTeam4/slash-llm) | LLM 서비스 — Gemma 추론. 2026-08-25 dev 배포 제거, 기능 동결 |
| [slash-runner](https://github.com/LikeLionTeam4/slash-runner) | PC 작업 실행기 — 파일 검색·위치 열기·상태 조회·로컬 CLI 실행. Python·PyInstaller |
| [slash-infra](https://github.com/LikeLionTeam4/slash-infra) | 인프라 — Terraform(AWS), Helm·ArgoCD 배포 |
| [slash-docs](https://github.com/LikeLionTeam4/slash-docs) | 프로젝트 문서 — 아키텍처, API 계약, ERD, 회의록 |
