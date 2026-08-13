# =============================================================================
# slash-api 컨테이너 이미지
#
#   docker build -t slash-api:local .
#   docker run --rm -p 8080:8080 slash-api:local
#
# 두 단계로 나눈다. JDK·Gradle·소스는 최종 이미지에 남기지 않는다.
# =============================================================================

# -----------------------------------------------------------------------------
# 1단계 — 빌드
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

# 래퍼와 빌드 정의를 먼저 넣는다. 소스만 바뀐 커밋에서는 Gradle 배포판을 내려받는
# 아래 층이 캐시로 재사용된다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet help

COPY src src

# 시험을 돌리지 않는다(-x test). Postgres·Valkey 가 떠 있어야 도는데 빌드 컨테이너에는
# 둘 다 없다. 시험은 CI 가 서비스 컨테이너를 띄운 별도 단계에서 돌린다.
#
# DB 없이 컴파일되는 것은 jOOQ 생성 코드를 src/generated/jooq 에 커밋해 두기 때문이다.
# (build.gradle.kts 의 generateSchemaSourceOnCompilation = false)
RUN ./gradlew --no-daemon --quiet bootJar -x test

# 실행 가능 JAR 을 층으로 나눈다. 의존성은 잘 바뀌지 않아 배포마다 다시 올리지 않아도 된다.
#
# --launcher 를 준다. 이게 없으면 application/slash-api-{버전}.jar 를 직접 실행하는 형태로
# 나오는데, ENTRYPOINT 는 exec 형식이라 와일드카드를 풀지 못해 파일명에 버전을 박아야 한다.
# launcher 형태는 JarLauncher 로 띄우므로 버전이 올라가도 이 파일을 고칠 일이 없다.
RUN java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination extracted

# -----------------------------------------------------------------------------
# 2단계 — 실행
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# 이 프로젝트의 시각 기준은 한국 시각이다. 애플리케이션은 스스로 Asia/Seoul 을 쓰지만
# (SlashTime · Jackson · JDBC Session) 컨테이너 기본값이 UTC 면 로그만 9시간 어긋나
# 장애 조사 때 DB 와 대조하기 어렵다. Alpine 은 tzdata 가 있어야 TZ 가 먹는다.
RUN apk add --no-cache tzdata
ENV TZ=Asia/Seoul

# root 로 돌리지 않는다.
RUN addgroup -S slash && adduser -S -G slash slash
USER slash

WORKDIR /app

# 바뀌는 빈도가 낮은 것부터 넣어 층 재사용을 늘린다.
COPY --from=build --chown=slash:slash /workspace/extracted/dependencies/ ./
COPY --from=build --chown=slash:slash /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=slash:slash /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=slash:slash /workspace/extracted/application/ ./

# REST · 사용자 WSS(/ws/user) · Agent WSS(/ws/agent) 가 모두 이 포트 하나를 쓴다.
EXPOSE 8080

# 컨테이너 메모리 한도의 75% 를 힙 상한으로 쓴다. 값을 주지 않으면 JVM 이 25% 만 쓴다.
# 추가 옵션은 JAVA_TOOL_OPTIONS 환경 변수로 넣으면 JVM 이 알아서 덧붙인다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
