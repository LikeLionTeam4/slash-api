# Slash | 코어 API

Slash(/)는 자연어 질문과 `/` 슬래시 명령어를 한 입력창에서 처리하는 AI 에이전트 서비스입니다.
이 저장소는 그중 **코어 API** 파트를 담당합니다.

## 역할

- 인증
- 작업 관리
- 실행 위치 결정 (로컬 / 서버)
- DB 연동

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

### 스키마를 변경할 때

1. `src/main/resources/db/migration/` 에 새 마이그레이션을 추가한다.
   - 파일명 `V{번호}__{설명}.sql`
   - 번호는 서비스 백엔드 담당자가 배정한다
   - **이미 공유된 마이그레이션은 수정하지 않고 새 번호를 추가한다**
2. `./gradlew generateJooq` 로 적용·재생성한다.
3. 생성 코드(`src/generated`)와 쿼리 시험을 **함께** 커밋한다.

### 디렉터리 구조

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

`local` 은 값이 모두 기본 설정되어 있어 별도 주입이 필요 없다.
`dev`·`demo` 는 아래 값을 Secrets Manager 에서 주입한다.

| 변수 | 설명 |
|---|---|
| `DB_URL` | PostgreSQL 접속 주소 |
| `DB_USERNAME` / `DB_PASSWORD` | DB 자격증명 |
| `VALKEY_HOST` / `VALKEY_PORT` | ElastiCache for Valkey |
| `COGNITO_ISSUER_URI` | Cognito User Pool 발급자 주소 |
| `NLU_BASE_URL` | slash-nlu 내부 주소 (예: `http://slash-nlu/internal/v1`) |

> 자격증명을 저장소에 평문으로 두지 않는다. Namespace 를 코드에 고정하지 않는다.

### 코드 규칙

- 시각은 모두 `timestamptz`(UTC)로 저장하고, API 는 ISO 8601 UTC 로 주고받는다.
- 내부 PK 는 `bigint`, 외부 노출 식별자는 `uuid public_id` 를 사용한다.
- 상태값은 PostgreSQL Enum 대신 `varchar` + `CHECK` 로 관리한다.
- `DSLContext` 는 Repository 계층 안에서만 사용하고, 생성된 jOOQ Record 를 서비스 밖으로 내보내지 않는다.

## 관련 저장소

| 저장소 | 역할 |
|---|---|
| [slash-web](https://github.com/LikeLionTeam4/slash-web) | 웹 클라이언트 — React·Vite UI, S3/CloudFront 배포 |
| **slash-api** (현재) | 코어 API — 인증, 작업 관리, 실행 위치 결정, DB 연동 |
| [slash-nlu](https://github.com/LikeLionTeam4/slash-nlu) | 자연어 분석 — slash 명령 파싱, 규칙·Kiwi 의도 분류, 인자 추출 |
| [slash-llm](https://github.com/LikeLionTeam4/slash-llm) | LLM 서비스 — Gemma 추론, 요약·대화 생성 |
| [slash-agent](https://github.com/LikeLionTeam4/slash-agent) | 로컬 에이전트 — PC 파일 검색, 상태 조회, 로컬 AI 실행·결과 전달 |
| [slash-infra](https://github.com/LikeLionTeam4/slash-infra) | 인프라 — Terraform(AWS), Helm·ArgoCD 배포 |
| [slash-docs](https://github.com/LikeLionTeam4/slash-docs) | 프로젝트 문서 — 아키텍처, API 계약, ERD, 회의록 |
