package com.likelion.slash.llm;

import com.likelion.slash.llm.dto.LlmReadinessResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 요약 모델이 작업을 받을 수 있는 상태인지 미리 확인해 둔다. (문서 3.7 · P0 보완)
 *
 * <p><b>왜 미리 묻는가.</b> GPU EC2 는 근무 시간에만 켜 두는 자원이라 꺼져 있는 시간이 있다.
 * 그때 요약을 접수하면 원장을 만들고 호출했다가 실패로 마감하는 일을 반복한다. 사용자에게는
 * 같은 "요약하지 못했습니다" 가 한참 뒤에 도착한다. 받을 수 없는 상태라면 <b>접수 시점에</b>
 * 그렇게 답하는 편이 낫다.
 *
 * <p><b>요청마다 묻지 않는다.</b> {@code /ready} 는 slash-llm 이 Ollama 를 실제로 찔러 보는
 * 경로라, 요청마다 부르면 접수가 그만큼 느려지고 모델 쪽에도 부담이 된다. 주기적으로 물어
 * 답을 들고 있는다.
 *
 * <p><b>모르는 동안에는 막지 않는다.</b> 기동 직후처럼 아직 한 번도 묻지 못했거나 slash-llm 에
 * 닿지 못한 경우는 "준비되지 않았다" 가 아니라 "모른다" 다. 그것으로 기능을 막으면 멀쩡한
 * 모델을 두고 요약이 거부된다. 그때는 통과시키고 실제 호출이 판단하게 둔다.
 */
@Component
public class LlmReadiness {

    private static final Logger log = LoggerFactory.getLogger(LlmReadiness.class);

    private final LlmClient llmClient;

    /**
     * 마지막으로 확인한 상태. 주기 작업이 쓰고 요청 처리 스레드가 읽으므로 {@code volatile} 이다.
     *
     * <p>비어 있으면 아직 모른다는 뜻이다. {@code false} 와 구분한다 — 앞은 통과시키고
     * 뒤는 막는다.
     */
    private volatile Boolean ready;

    /** 상태가 바뀔 때만 로그를 남긴다. 회차마다 같은 줄을 쌓지 않는다. */
    private volatile String lastReason;

    public LlmReadiness(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 지금 요약을 맡겨도 되는가.
     *
     * @return 아직 모르면 참. 모른다는 이유로 막지 않는다.
     */
    public boolean canAccept() {
        return ready == null || ready;
    }

    /** 마지막으로 확인한 이유. 준비된 상태이거나 아직 모르면 비어 있다. */
    public Optional<String> reason() {
        return Optional.ofNullable(lastReason);
    }

    /**
     * 밀리초 단위로 받는 이유는 다른 주기 작업과 같다 — {@code @Scheduled} 의 문자열 값은
     * 설정 파일의 {@code 30s} 표기를 그대로 해석하지 못한다.
     *
     * <p>{@code initialDelayString} 을 0 으로 두어 기동하자마자 한 번 묻는다. 모르는 구간을
     * 짧게 하기 위해서다.
     */
    @Scheduled(
            fixedDelayString = "${slash.llm.readiness-check.interval-ms}",
            initialDelayString = "0")
    public void check() {
        Optional<LlmReadinessResponse> answer = llmClient.ready();

        if (answer.isEmpty()) {
            // 닿지 못한 것은 "준비되지 않았다" 가 아니다. 마지막으로 알던 값을 그대로 둔다.
            log.debug("요약 모델 준비 상태를 확인하지 못했다. 이전 값을 유지한다: {}", ready);
            return;
        }

        LlmReadinessResponse response = answer.get();
        boolean nowReady = response.isReady();
        String nowReason = nowReady ? null : response.reason();

        if (!Boolean.valueOf(nowReady).equals(ready)) {
            if (nowReady) {
                log.info("요약 모델이 준비됐다 model={}", response.model());
            } else {
                log.warn("요약 모델이 작업을 받을 수 없다 reason={} model={}", nowReason, response.model());
            }
        }

        this.ready = nowReady;
        this.lastReason = nowReason;
    }
}
