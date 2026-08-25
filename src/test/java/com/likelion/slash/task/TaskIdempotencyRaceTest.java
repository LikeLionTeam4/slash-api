package com.likelion.slash.task;

import static com.likelion.slash.jooq.Tables.IDEMPOTENCY_RECORDS;
import static com.likelion.slash.jooq.Tables.TASKS;
import static com.likelion.slash.jooq.Tables.TASK_EVENTS;
import static com.likelion.slash.jooq.Tables.USERS;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.auth.AuthenticatedUser;
import com.likelion.slash.common.Sha256;
import com.likelion.slash.common.SlashTime;
import com.likelion.slash.jooq.tables.records.TasksRecord;
import com.likelion.slash.task.dto.BrowserSummaryResultRequest;
import com.likelion.slash.task.dto.CreateRequestResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 같은 {@code Idempotency-Key} 로 <b>동시에</b> 들어온 요청이 이력을 늘리지 않는지 확인. (#70)
 *
 * <p>순차 재전송은 {@code replay} 가 걸러서 예전부터 잘 동작했다. 문제는 동시였다 — 작업을
 * 먼저 만들고 그다음 키를 선점하던 순서 때문에, 경쟁에서 진 요청이 만든 작업이 그대로 남아
 * <b>응답은 하나를 가리키는데 이력에는 요청 수만큼 보였다.</b> 그 유령들은 {@code CREATED} 라
 * 미완료 스윕이 나중에 {@code EXPIRED} 로 마감해 이력에 영구히 남는다.
 *
 * <p><b>이 시험은 {@code @Transactional} 이 아니다.</b> 스레드마다 트랜잭션이 따로여야 경쟁이
 * 생기고, 시험 자체가 트랜잭션이면 안쪽 되돌림이 바깥에 묻혀 무엇이 남았는지 볼 수 없다.
 * 대신 만든 자료를 뒤에서 지운다.
 */
@SpringBootTest
class TaskIdempotencyRaceTest {

    /** 이슈 재현에 쓴 수와 같게 둔다. 커넥션 풀(10)보다 크지만 대기했다 풀린다. */
    private static final int 동시요청수 = 20;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskStateWriter stateWriter;

    @Autowired
    private DSLContext dsl;

    private long userId;
    private AuthenticatedUser 사용자;

    @BeforeEach
    void setUp() {
        userId = 사용자(dsl);
        this.사용자 = new AuthenticatedUser(
                userId, UUID.randomUUID(), "tester@example.com", "시험 사용자",
                "Asia/Seoul", "ACTIVE", SlashTime.now());
    }

    @AfterEach
    void tearDown() {
        // task_events·idempotency_records 는 tasks 에 ON DELETE CASCADE 로 매달려 있다.
        dsl.deleteFrom(TASKS).where(TASKS.USER_ID.eq(userId)).execute();
        dsl.deleteFrom(USERS).where(USERS.ID.eq(userId)).execute();
    }

    @Test
    @DisplayName("같은 키로 동시에 결과를 보내도 이력은 한 줄만 남는다")
    void 동시_재전송이_이력을_늘리지_않는다() throws Exception {
        String key = UUID.randomUUID().toString();

        List<CreateRequestResponse> 응답들 = 동시에(동시요청수,
                () -> taskService.submitBrowserSummaryResult(사용자, 성공_결과(), key));

        // 응답이 멱등한 것은 고치기 전에도 그랬다. 이력이 따라오는지가 이 시험의 요점이다.
        assertThat(응답들).extracting(CreateRequestResponse::taskId)
                .containsOnly(응답들.get(0).taskId());
        assertThat(작업수()).isEqualTo(1);
        assertThat(멱등기록수()).isEqualTo(1);
    }

    @Test
    @DisplayName("경쟁에서 진 요청은 작업도 최초 기록도 남기지 않는다")
    void 진_요청은_아무것도_남기지_않는다() throws Exception {
        IdempotencyClaim 같은_키 = new IdempotencyClaim(
                UUID.randomUUID().toString(),
                "/api/v1/requests",
                Sha256.hex("/status"),
                SlashTime.now().plus(Duration.ofHours(24)));

        List<Optional<TasksRecord>> 결과 = 동시에(동시요청수,
                () -> stateWriter.create(userId, "/status", UUID.randomUUID(), "요청을 접수했습니다.", 같은_키));

        assertThat(결과.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(작업수()).isEqualTo(1);
        // 작업과 함께 되돌아가야 한다. 최초 기록만 남으면 타임라인이 두 번 그려진다.
        assertThat(상태기록수()).isEqualTo(1);
    }

    @Test
    @DisplayName("멱등 키가 없으면 요청마다 작업이 만들어진다")
    void 키가_없으면_저마다_만든다() throws Exception {
        동시에(3, () -> stateWriter.create(userId, "/status", UUID.randomUUID(), "요청을 접수했습니다.", null));

        assertThat(작업수()).isEqualTo(3);
    }

    /**
     * 같은 순간에 출발시킨다.
     *
     * <p>차례로 부르면 앞선 요청이 이미 커밋돼 있어 {@code replay} 가 먼저 걸러낸다 — 그러면
     * 경쟁 자체가 일어나지 않아 이 결함이 드러나지 않는다.
     */
    private <T> List<T> 동시에(int 횟수, Callable<T> 일) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(횟수);
        CountDownLatch 출발 = new CountDownLatch(1);
        List<Future<T>> 진행중 = new ArrayList<>();
        try {
            for (int i = 0; i < 횟수; i++) {
                진행중.add(pool.submit(() -> {
                    출발.await();
                    return 일.call();
                }));
            }
            출발.countDown();

            List<T> 결과 = new ArrayList<>();
            for (Future<T> f : 진행중) {
                결과.add(f.get(30, TimeUnit.SECONDS));
            }
            return 결과;
        } finally {
            pool.shutdownNow();
        }
    }

    private BrowserSummaryResultRequest 성공_결과() {
        return new BrowserSummaryResultRequest(
                1200, "Qwen2.5-1.5B-Instruct-q4f16_1-MLC", "v1",
                BrowserSummaryResultRequest.Status.SUCCEEDED,
                "브라우저에서 만든 요약문.", 2400, null);
    }

    private int 작업수() {
        return dsl.fetchCount(TASKS, TASKS.USER_ID.eq(userId));
    }

    private int 멱등기록수() {
        return dsl.fetchCount(IDEMPOTENCY_RECORDS, IDEMPOTENCY_RECORDS.USER_ID.eq(userId));
    }

    private int 상태기록수() {
        return dsl.fetchCount(TASK_EVENTS, TASK_EVENTS.TASK_ID.in(
                dsl.select(TASKS.ID).from(TASKS).where(TASKS.USER_ID.eq(userId))));
    }
}
