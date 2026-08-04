import org.jooq.meta.jaxb.Logging

// Flyway Gradle 플러그인이 PostgreSQL 을 다루려면 플러그인 클래스패스에
// 드라이버와 데이터베이스 모듈이 함께 있어야 한다. (애플리케이션 런타임과 별개)
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.postgresql:postgresql:42.7.8")
        classpath("org.flywaydb:flyway-database-postgresql:11.18.0")
    }
}

plugins {
    java
    id("org.springframework.boot") version "3.5.9"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.flywaydb.flyway") version "11.18.0"
    id("nu.studer.jooq") version "9.0"
}

group = "com.likelion"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // 인증 — Cognito Access Token(JWT) 검증 (W1-01)
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // 실시간 연결 — 사용자 WSS, Agent WSS (W1-06)
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // 데이터 — jOOQ + Flyway + PostgreSQL
    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // 상태 공유·짧은 잠금 — ElastiCache for Valkey (Redis 프로토콜 호환)
    // 대기열로는 사용하지 않는다. 비동기 작업은 SQS 가 담당한다. (문서 3.8)
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    runtimeOnly("org.postgresql:postgresql")

    // jOOQ 코드 생성기가 사용하는 의존성
    jooqGenerator("org.postgresql:postgresql:42.7.8")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ---------------------------------------------------------------------------
// 로컬 개발 DB 접속 정보
//   - jOOQ 코드 생성은 반드시 로컬 Docker Postgres를 대상으로 한다. (RDS 금지)
//   - docker compose up -d 로 먼저 DB를 띄운 뒤 codegen 을 실행한다.
// ---------------------------------------------------------------------------
val dbUrl = providers.gradleProperty("db.url").orElse("jdbc:postgresql://localhost:5432/slash").get()
val dbUser = providers.gradleProperty("db.user").orElse("slash").get()
val dbPassword = providers.gradleProperty("db.password").orElse("slash").get()

flyway {
    url = dbUrl
    user = dbUser
    password = dbPassword
    locations = arrayOf("filesystem:src/main/resources/db/migration")
    cleanDisabled = false // 로컬 전용. 운영/Dev RDS 에서는 절대 활성화하지 않는다.
}

jooq {
    // Spring Boot 가 관리하는 jOOQ 런타임 버전과 반드시 일치시킨다.
    // 어긋나면 codegen 이 Version check 경고를 내고, 생성 코드가 런타임과 미묘하게 달라질 수 있다.
    version = "3.19.29"

    configurations {
        create("main") {
            generateSchemaSourceOnCompilation = false // 명시적으로 generateJooq 실행

            jooqConfiguration.apply {
                logging = Logging.WARN

                jdbc.apply {
                    driver = "org.postgresql.Driver"
                    url = dbUrl
                    user = dbUser
                    password = dbPassword
                }

                generator.apply {
                    name = "org.jooq.codegen.DefaultGenerator"

                    database.apply {
                        name = "org.jooq.meta.postgres.PostgresDatabase"
                        inputSchema = "public"
                        // Flyway 이력 테이블은 생성 대상에서 제외
                        excludes = "flyway_schema_history"
                    }

                    generate.apply {
                        isDeprecated = false
                        isRecords = true
                        isImmutablePojos = false
                        isPojos = false
                        isFluentSetters = true
                        isJavaTimeTypes = true // timestamptz -> OffsetDateTime
                    }

                    target.apply {
                        packageName = "com.likelion.slash.jooq"
                        // 5.10.2 규칙: 생성 코드를 저장소에 커밋한다.
                        // build/ 아래에 두면 gitignore 대상이 되므로 src/generated 로 뺀다.
                        // (CI 가 DB 없이도 컴파일할 수 있다는 장점도 있다)
                        directory = "src/generated/jooq"
                    }

                    strategy.name = "org.jooq.codegen.DefaultGeneratorStrategy"
                }
            }
        }
    }
}

// codegen 전에 마이그레이션이 항상 최신이도록 순서 강제
tasks.named("generateJooq") {
    dependsOn(tasks.named("flywayMigrate"))
}
