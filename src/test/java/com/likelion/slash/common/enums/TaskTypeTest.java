package com.likelion.slash.common.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 메시지 프로토콜 정의 5.3 · 8.10 의 계약을 고정한다.
 *
 * <p>이 값들은 프론트·NLU·Agent 가 함께 사용하므로 조용히 바뀌면 안 된다.
 * 값을 바꾸려면 계약 변경 절차를 거치고 이 시험도 함께 수정한다.
 */
class TaskTypeTest {

    @Test
    @DisplayName("작업 유형과 Slash 명령이 계약과 일치한다")
    void 작업_유형과_slash_명령이_계약과_일치한다() {
        assertThat(TaskType.WEATHER_LOOKUP.slashCommand()).isEqualTo("/weather");
        assertThat(TaskType.FILE_SEARCH.slashCommand()).isEqualTo("/file");
        assertThat(TaskType.SYSTEM_STATUS.slashCommand()).isEqualTo("/status");
        assertThat(TaskType.TEXT_SUMMARY.slashCommand()).isEqualTo("/summary");
        assertThat(TaskType.CODE_ANALYSIS.slashCommand()).isEqualTo("/code");
        assertThat(TaskType.AI_AGENT_USAGE.slashCommand()).isEqualTo("/usage");
    }

    @Test
    @DisplayName("처리 경로가 계약과 일치한다")
    void 처리_경로가_계약과_일치한다() {
        assertThat(TaskType.WEATHER_LOOKUP.processingRoute()).isEqualTo(ProcessingRoute.BACKEND_SERVICE);
        assertThat(TaskType.FILE_SEARCH.processingRoute()).isEqualTo(ProcessingRoute.LOCAL_AGENT);
        assertThat(TaskType.SYSTEM_STATUS.processingRoute()).isEqualTo(ProcessingRoute.LOCAL_AGENT);
        assertThat(TaskType.TEXT_SUMMARY.processingRoute()).isEqualTo(ProcessingRoute.LLM_SERVICE);
        assertThat(TaskType.CODE_ANALYSIS.processingRoute()).isEqualTo(ProcessingRoute.LOCAL_AGENT);
        assertThat(TaskType.AI_AGENT_USAGE.processingRoute()).isEqualTo(ProcessingRoute.LOCAL_AGENT);
    }

    @Test
    @DisplayName("Slash 명령은 서로 겹치지 않는다")
    void slash_명령은_중복되지_않는다() {
        long distinct = Arrays.stream(TaskType.values())
                .map(TaskType::slashCommand)
                .distinct()
                .count();

        assertThat(distinct).isEqualTo(TaskType.values().length);
    }

    @Test
    @DisplayName("Slash 명령 문자열로 작업 유형을 찾을 수 있다")
    void slash_명령으로_작업_유형을_찾는다() {
        assertThat(TaskType.fromSlashCommand("/file")).contains(TaskType.FILE_SEARCH);
        assertThat(TaskType.fromSlashCommand("/nope")).isEmpty();
    }

    @Test
    @DisplayName("로컬 PC 선택이 필요한 작업이 구분된다")
    void 로컬_작업만_pc_선택이_필요하다() {
        // /weather 와 /summary 는 selectedDeviceId 가 null 이어도 된다.
        assertThat(TaskType.WEATHER_LOOKUP.requiresDevice()).isFalse();
        assertThat(TaskType.TEXT_SUMMARY.requiresDevice()).isFalse();

        assertThat(TaskType.FILE_SEARCH.requiresDevice()).isTrue();
        assertThat(TaskType.SYSTEM_STATUS.requiresDevice()).isTrue();
        assertThat(TaskType.CODE_ANALYSIS.requiresDevice()).isTrue();
        assertThat(TaskType.AI_AGENT_USAGE.requiresDevice()).isTrue();
    }

    @Test
    @DisplayName("P0 작업 유형 — AI_AGENT_USAGE·FILE_OPEN 이 P0-B 로 편입됐다")
    void p0_작업_유형() {
        // 계획 문서 §1.4 가 "파일 열기와 Explorer·Finder 위치 표시" 와 "Claude Code·Codex
        // 로컬 세션 로그의 토큰 수 조회" 를 제품 본체로 옮겼다. PC 실행기는 둘 다 이미 지원한다.
        assertThat(TaskType.p0Values()).containsExactlyInAnyOrder(
                TaskType.WEATHER_LOOKUP,
                TaskType.FILE_SEARCH,
                TaskType.FILE_OPEN,
                TaskType.SYSTEM_STATUS,
                TaskType.TEXT_SUMMARY,
                TaskType.AI_AGENT_USAGE);
    }

    @Test
    @DisplayName("필수 입력값 목록이 계약과 일치한다")
    void 필수_입력값이_계약과_일치한다() {
        assertThat(TaskType.WEATHER_LOOKUP.requiredParameters()).containsExactly("location");
        assertThat(TaskType.FILE_SEARCH.requiredParameters()).containsExactly("query", "searchFolderId");
        // fileRef 는 FILE_SEARCH 결과가 준 값을 그대로 돌려보내는 것이다. 서버는 채우지 않는다.
        assertThat(TaskType.FILE_OPEN.requiredParameters()).containsExactly("fileRef");
        assertThat(TaskType.FILE_OPEN.backendProvidedParameters()).isEmpty();
        assertThat(TaskType.SYSTEM_STATUS.requiredParameters()).isEmpty();
        assertThat(TaskType.TEXT_SUMMARY.requiredParameters()).containsExactly("text");
        assertThat(TaskType.AI_AGENT_USAGE.requiredParameters()).containsExactly("provider");
    }

    @Test
    @DisplayName("서버가 채우는 입력값은 NLU 몫에서 빠진다")
    void 서버가_채우는_값은_nlu_몫이_아니다() {
        // searchFolderId 는 Agent 의 READY.searchFolders 에서 서버가 고른다.
        // NLU 가 이것까지 채우려 하면 자연어에 없는 값이라 매번 되묻게 된다.
        // (slash-nlu docs/BACKEND_CONTRACT.md · 소유권)
        assertThat(TaskType.FILE_SEARCH.backendProvidedParameters()).containsExactly("searchFolderId");
        assertThat(TaskType.FILE_SEARCH.nluRequiredParameters()).containsExactly("query");

        assertThat(TaskType.CODE_ANALYSIS.backendProvidedParameters()).containsExactly("workspaceId");
        assertThat(TaskType.CODE_ANALYSIS.nluRequiredParameters()).isEmpty();
    }

    @Test
    @DisplayName("나머지 작업은 NLU 가 필수 입력값을 모두 채운다")
    void 나머지는_nlu_가_모두_채운다() {
        assertThat(TaskType.WEATHER_LOOKUP.nluRequiredParameters()).containsExactly("location");
        assertThat(TaskType.SYSTEM_STATUS.nluRequiredParameters()).isEmpty();
        assertThat(TaskType.TEXT_SUMMARY.nluRequiredParameters()).containsExactly("text");
        assertThat(TaskType.AI_AGENT_USAGE.nluRequiredParameters()).containsExactly("provider");
    }

    @Test
    @DisplayName("서버가 채우는 값은 반드시 필수 입력값 안에 있다")
    void 서버가_채우는_값은_필수_입력값의_부분집합이다() {
        // 오타로 필수 목록에 없는 이름을 적으면 그 값은 아무도 채우지 않는 채로 남는다.
        for (TaskType taskType : TaskType.values()) {
            assertThat(taskType.requiredParameters())
                    .as("%s 의 backendProvidedParameters", taskType.name())
                    .containsAll(taskType.backendProvidedParameters());
        }
    }
}
