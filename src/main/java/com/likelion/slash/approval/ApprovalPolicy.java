package com.likelion.slash.approval;

import com.likelion.slash.common.enums.TaskType;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 어떤 작업에 사용자 확인이 필요한지 정한다. (P0-C · 계획 문서 §1.5)
 *
 * <p><b>지금은 아무 작업도 승인을 요구하지 않는다.</b> 계획 문서 §1.4 가 "패치 적용, 시험
 * 명령 실행, 임의 코드 수정은 별도 승인 정책이 완성되기 전에는 포함하지 않는다" 고 못박았고,
 * §12.2 는 "승인형 파일·코드 변경" 을 조건부 승인으로 두었다. 지금 있는 작업은 모두
 * 읽기 전용이거나 서버가 하는 일이라 물어볼 것이 없다.
 *
 * <p><b>작업 유형에 붙이지 않고 설정으로 둔 이유.</b> 승인이 필요한지는 <b>그 기능이 무엇을
 * 하느냐</b>가 아니라 <b>운영이 무엇을 위험하다고 보느냐</b>로 정해진다. 같은 코드 분석이라도
 * 읽기만 할 때와 고칠 수 있을 때가 다르고, 그 판단은 배포마다 다를 수 있다.
 * {@code TaskType} 에 박아 두면 그것을 바꾸는 데 배포가 필요하다.
 *
 * <p>목록에 없는 값을 적으면 <b>기동을 막지 않고 무시하며 경고만 남긴다.</b> 설정 오타 하나로
 * 서비스가 뜨지 않는 것보다, 승인이 걸리지 않는 것을 로그로 알리는 편이 낫다.
 */
@Component
public class ApprovalPolicy {

    private static final Logger log = LoggerFactory.getLogger(ApprovalPolicy.class);

    private final Set<TaskType> requiresApproval;
    private final Duration ttl;

    public ApprovalPolicy(@Value("${slash.approval.required-task-types}") List<String> taskTypes,
                          @Value("${slash.approval.ttl}") Duration ttl) {
        this.requiresApproval = parse(taskTypes);
        this.ttl = ttl;

        if (!requiresApproval.isEmpty()) {
            log.info("승인이 필요한 작업: {}", requiresApproval);
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
                log.warn("승인 정책에 알 수 없는 작업 유형이 있어 건너뛴다: {}", name);
            }
        }
        return parsed;
    }

    /** 실행하기 전에 사용자에게 물어야 하는 작업인지. */
    public boolean requiresApproval(TaskType taskType) {
        return requiresApproval.contains(taskType);
    }

    /** 사용자 응답 기한. */
    public Duration ttl() {
        return ttl;
    }
}
