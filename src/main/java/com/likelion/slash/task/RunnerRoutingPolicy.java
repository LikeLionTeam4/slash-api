package com.likelion.slash.task;

import com.likelion.slash.common.enums.TaskType;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 어떤 작업을 등록 PC 로 보내지 않을지 정한다. (slash-docs#3 보안 게이트)
 *
 * <p><b>기능을 없애는 것이 아니라 잠가 두는 것이다.</b> 실행기가 Claude Code·Codex 로 하는
 * 일은 도구 화이트리스트·OS 격리·감사 기록이 갖춰진 뒤에 열기로 했고, 그때까지 이미 열려
 * 있던 경로를 닫아 둔다. 조건이 갖춰지면 설정만 바꿔 다시 연다.
 *
 * <p><b>설정으로 둔 이유는 되켜는 데 배포가 필요하지 않게 하기 위해서다.</b> 게이트는
 * "무엇이 끝나면 연다" 는 약속이라, 그 시점에 코드를 다시 고치는 것보다 설정 한 줄을
 * 바꾸는 편이 낫다. {@link com.likelion.slash.approval.ApprovalPolicy} 와 같은 이유다.
 *
 * <p><b>유형마다 막았을 때의 결과가 다르다.</b>
 * <ul>
 *   <li>{@code TEXT_SUMMARY} 는 서버에도 경로가 있어 잠가도 조용히 서버가 처리한다 —
 *       사용자는 아무것도 잃지 않는다</li>
 *   <li>{@code CODE_ANALYSIS} 처럼 PC 밖에 갈 곳이 없는 유형은 <b>기능 자체가 멈춘다.</b>
 *       그래서 목록에 넣는 것은 기능을 내리는 결정이고, 기본값에 두지 않는다</li>
 * </ul>
 *
 * <p>목록에 없는 값을 적으면 기동을 막지 않고 경고만 남긴다 — 설정 오타 하나로 서비스가
 * 뜨지 않는 것보다, 잠기지 않았다는 것을 로그로 알리는 편이 낫다.
 */
@Component
public class RunnerRoutingPolicy {

    private static final Logger log = LoggerFactory.getLogger(RunnerRoutingPolicy.class);

    private final Set<TaskType> blocked;

    public RunnerRoutingPolicy(@Value("${slash.runner.blocked-task-types}") List<String> taskTypes) {
        this.blocked = parse(taskTypes);

        if (!blocked.isEmpty()) {
            log.warn("실행기로 보내지 않는 작업: {} (slash-docs#3 보안 게이트)", blocked);
        }
    }

    private static Set<TaskType> parse(List<String> taskTypes) {
        Set<TaskType> parsed = EnumSet.noneOf(TaskType.class);
        if (taskTypes == null) {
            return parsed;
        }

        for (String name : taskTypes) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                parsed.add(TaskType.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("실행기 차단 목록에 알 수 없는 작업 유형이 있어 건너뛴다: {}", name);
            }
        }
        return parsed;
    }

    /** 이 작업을 지금 실행기로 보낼 수 있는가. */
    public boolean isBlocked(TaskType taskType) {
        return blocked.contains(taskType);
    }
}
