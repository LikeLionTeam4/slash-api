package com.likelion.slash.device;

import static com.likelion.slash.support.TestFixtures.사용자;
import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DeviceProjectWorkspaceRepository} 확인. (P0-B · CODE_ANALYSIS)
 *
 * <p>{@link DeviceSearchFolderRepositoryTest} 와 같은 것을 본다.
 * <ul>
 *   <li><b>보고에서 빠진 폴더가 남지 않는가</b> — 남으면 서버가 이미 없는 폴더를 골라
 *       {@code WORKSPACE_NOT_FOUND} 를 반복해서 받는다</li>
 *   <li><b>도구가 없는 폴더를 고르지 않는가</b> — Agent 가
 *       {@code CODE_AGENT_NOT_CONFIGURED} 로 거절하므로 골라 봐야 실패한다</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class DeviceProjectWorkspaceRepositoryTest {

    @Autowired
    private DeviceProjectWorkspaceRepository repository;

    @Autowired
    private DSLContext dsl;

    private static ProjectWorkspace 폴더(String id, String 이름, String... 도구) {
        return new ProjectWorkspace(id, 이름, ProjectWorkspace.GIT_REPOSITORY, List.of(도구));
    }

    @Test
    @DisplayName("보고한 폴더를 도구 목록과 함께 저장한다")
    void 저장한다() {
        long 기기 = 준비된_기기(dsl, 사용자(dsl));

        repository.replaceAll(기기, List.of(폴더("ws-1", "slash-api", "CLAUDE_CODE", "CODEX")));

        var 저장된 = repository.findAllByDeviceId(기기);
        assertThat(저장된).hasSize(1);
        assertThat(저장된.get(0).getWorkspaceId()).isEqualTo("ws-1");
        assertThat(저장된.get(0).getDisplayName()).isEqualTo("slash-api");
        assertThat(저장된.get(0).getWorkspaceType()).isEqualTo(ProjectWorkspace.GIT_REPOSITORY);
        assertThat(저장된.get(0).getAvailableCodeAdapters()).containsExactly("CLAUDE_CODE", "CODEX");
    }

    @Test
    @DisplayName("보고에서 빠진 폴더는 지운다")
    void 빠진_폴더는_지운다() {
        long 기기 = 준비된_기기(dsl, 사용자(dsl));
        repository.replaceAll(기기, List.of(폴더("ws-1", "가", "CODEX"), 폴더("ws-2", "나", "CODEX")));

        repository.replaceAll(기기, List.of(폴더("ws-2", "나", "CODEX")));

        assertThat(repository.findAllByDeviceId(기기))
                .extracting(record -> record.getWorkspaceId())
                .containsExactly("ws-2");
    }

    @Test
    @DisplayName("도구가 없는 폴더는 고르지 않는다")
    void 도구가_없으면_고르지_않는다() {
        long 기기 = 준비된_기기(dsl, 사용자(dsl));

        // CLI 가 설치되지 않은 PC 다. 폴더는 등록돼 있지만 분석할 수단이 없다.
        repository.replaceAll(기기, List.of(폴더("ws-1", "slash-api")));

        assertThat(repository.findAllByDeviceId(기기)).hasSize(1);
        assertThat(repository.pickAnalyzable(기기)).isEmpty();
    }

    @Test
    @DisplayName("고를 때는 이름 순으로 정해 같은 명령이 매번 같은 폴더를 본다")
    void 이름_순으로_고른다() {
        long 기기 = 준비된_기기(dsl, 사용자(dsl));
        repository.replaceAll(기기, List.of(
                폴더("ws-2", "나 프로젝트", "CODEX"),
                폴더("ws-1", "가 프로젝트", "CLAUDE_CODE")));

        assertThat(repository.pickAnalyzable(기기)).contains("ws-1");
    }

    @Test
    @DisplayName("계약에 없는 도구 이름은 버리고 나머지로 저장한다")
    void 모르는_도구는_버린다() {
        long 기기 = 준비된_기기(dsl, 사용자(dsl));

        // 제약이 목록 밖의 값을 거부한다. 걸러내지 않으면 폴더 하나 때문에 READY 전체가 실패하고,
        // Agent 는 재접속해 같은 READY 를 다시 보내므로 그 PC 가 영영 붙지 못한다.
        repository.replaceAll(기기, List.of(
                new ProjectWorkspace("ws-1", "slash-api", ProjectWorkspace.GIT_REPOSITORY,
                        List.of("CLAUDE_CODE", "GEMINI_CLI"))));

        assertThat(repository.findAllByDeviceId(기기).get(0).getAvailableCodeAdapters())
                .containsExactly("CLAUDE_CODE");
        assertThat(repository.pickAnalyzable(기기)).contains("ws-1");
    }

    @Test
    @DisplayName("모양이 어긋난 보고는 저장하지 않는다")
    void 어긋난_보고는_버린다() {
        long 기기 = 준비된_기기(dsl, 사용자(dsl));

        repository.replaceAll(기기, List.of(
                new ProjectWorkspace(null, "이름만 있다", ProjectWorkspace.DIRECTORY, List.of("CODEX")),
                new ProjectWorkspace("ws-2", "  ", ProjectWorkspace.DIRECTORY, List.of("CODEX")),
                new ProjectWorkspace("ws-3", "종류가 없다", null, List.of("CODEX")),
                폴더("ws-4", "멀쩡한 것", "CODEX")));

        assertThat(repository.findAllByDeviceId(기기))
                .extracting(record -> record.getWorkspaceId())
                .containsExactly("ws-4");
    }
}
