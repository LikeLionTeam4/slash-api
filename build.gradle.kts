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

    // 시험은 개발자 셸에 export 된 Cognito 설정을 타면 안 된다.
    // 값이 남아 있으면 실제 Cognito 검증이 켜지면서 로컬 임시 인증이 꺼지고,
    // 인증이 필요한 시험이 통째로 401 이 된다. (컨텍스트마다 외부 호출도 나간다)
    listOf("COGNITO_ISSUER_URI", "COGNITO_CLIENT_ID", "COGNITO_USER_INFO_URI")
        .forEach { environment(it, "") }
}

// ---------------------------------------------------------------------------
// .env 지원
//
//   Gradle 도 Spring Boot 도 .env 를 자동으로 읽지 않는다.
//   팀원마다 셸을 설정하지 않아도 되도록 bootRun 에서만 직접 읽어 넣는다.
//
//     cp .env.example .env   # 값을 채운 뒤
//     ./gradlew bootRun
//
//   .env 는 .gitignore 대상이라 저장소에 올라가지 않는다.
//   시험은 위 Test 설정이 값을 비우므로 .env 가 있어도 영향받지 않는다.
// ---------------------------------------------------------------------------
/**
 * .env 한 줄의 값 부분을 읽는다.
 *
 * 따옴표로 감쌌으면 그 안을 그대로 쓰고, 감싸지 않았으면 뒤에 붙은 주석을 잘라낸다.
 *
 *   KEY=값 # 설명     -> "값"
 *   KEY="값 # 설명"   -> "값 # 설명"
 *
 * 주석을 잘라내지 않으면 CLIENT_ID 같은 값에 설명이 섞여도 기동은 성공하고
 * 토큰만 전부 401 이 된다. 원인을 찾기 어려운 실패라 여기서 막는다.
 */
fun parseDotenvValue(rawValue: String): String {
    val raw = rawValue.trim()

    if (raw.length >= 2 && (raw.startsWith("\"") || raw.startsWith("'"))) {
        val quote = raw[0]
        val closing = raw.indexOf(quote, startIndex = 1)
        if (closing > 0) {
            return raw.substring(1, closing)
        }
    }

    return raw.substringBefore(" #").substringBefore("\t#").trim()
}

fun loadDotenv(): Map<String, String> {
    val envFile = rootProject.file(".env")
    if (!envFile.exists()) {
        return emptyMap()
    }

    return envFile.readLines()
        .map(String::trim)
        // 주석과 빈 줄을 건너뛴다.
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .associate { line ->
            val (key, value) = line.split("=", limit = 2)
            key.trim() to parseDotenvValue(value)
        }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    val dotenv = loadDotenv()
    dotenv.forEach { (key, value) -> environment(key, value) }

    doFirst {
        if (dotenv.isNotEmpty()) {
            // 값은 찍지 않는다. 어떤 키를 읽었는지만 알린다.
            logger.lifecycle(".env 에서 ${dotenv.keys.sorted().joinToString(", ")} 를 읽었습니다.")
        }
    }
}

// ---------------------------------------------------------------------------
// 로컬 개발 DB 접속 정보
//   - jOOQ 코드 생성은 반드시 로컬 Docker Postgres를 대상으로 한다. (RDS 금지)
//   - docker compose up -d 로 먼저 DB를 띄운 뒤 codegen 을 실행한다.
// ---------------------------------------------------------------------------
val dbUrl = providers.gradleProperty("db.url").orElse("jdbc:postgresql://localhost:5432/slash").get()
val dbUser = providers.gradleProperty("db.user").orElse("slash").get()
val dbPassword = providers.gradleProperty("db.password").orElse("slash").get()

/**
 * 로컬 DB 를 가리키는 접속 주소인지 판별한다.
 *
 * db.url 을 덮어쓰면 원격 DB 를 대상으로 flywayClean 을 실행할 수 있으므로,
 * 로컬이 아닌 주소에서는 파괴적인 작업을 막는다.
 */
val isLocalDatabase = Regex("^jdbc:postgresql://(localhost|127\\.0\\.0\\.1|\\[::1])[:/]")
    .containsMatchIn(dbUrl)

flyway {
    url = dbUrl
    user = dbUser
    password = dbPassword
    locations = arrayOf("filesystem:src/main/resources/db/migration")
    // 로컬 Docker 에서만 clean 을 허용한다. 원격 주소에서는 항상 차단한다.
    cleanDisabled = !isLocalDatabase
}

// db.url 을 원격으로 덮어쓴 채 파괴적인 작업을 실행하는 것을 막는다.
// 시연 DB(slash_demo) 를 실수로 비우는 사고를 방지하기 위한 안전장치다.
listOf("flywayClean", "flywayUndo").forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        doFirst {
            if (!isLocalDatabase) {
                throw GradleException(
                    """
                    로컬이 아닌 데이터베이스에는 $taskName 을 실행할 수 없습니다.
                      대상: $dbUrl
                    이 작업은 로컬 Docker(localhost) 에서만 허용됩니다.
                    """.trimIndent()
                )
            }
        }
    }
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
