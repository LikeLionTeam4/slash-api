package com.likelion.slash.device;

import static com.likelion.slash.support.TestFixtures.준비된_기기;
import static com.likelion.slash.support.TestFixtures.사용자;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link DeviceSearchFolderRepository} 확인. (WBS W1-03)
 *
 * <p>여기서 보는 것은 두 가지다.
 * <ul>
 *   <li><b>보고에서 빠진 폴더가 남지 않는가</b> — 남으면 서버가 이미 없는 폴더를 골라
 *       작업을 보내고, 사용자는 이유를 알 수 없는 실패를 반복해서 본다</li>
 *   <li><b>거르는 기준이 Agent 와 같은가</b> — slash-agent 의 {@code is_searchable()} 은
 *       {@code UNAVAILABLE} 만 거른다. 서버가 더 좁히면 Agent 가 할 수 있는 검색을 우리가 막는다</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class DeviceSearchFolderRepositoryTest {

    @Autowired
    private DeviceSearchFolderRepository repository;

    @Autowired
    private DSLContext dsl;

    private static SearchFolder 폴더(String id, String 이름, String 상태) {
        return new SearchFolder(id, 이름, 상태);
    }

    @Test
    @DisplayName("보고한 폴더를 저장한다")
    void 저장한다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));

        repository.replaceAll(기기_PK, List.of(폴더("sf-1", "문서", SearchFolder.INDEXED)));

        assertThat(repository.findAllByDeviceId(기기_PK))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getSearchFolderId()).isEqualTo("sf-1");
                    assertThat(saved.getDisplayName()).isEqualTo("문서");
                    assertThat(saved.getIndexStatus()).isEqualTo(SearchFolder.INDEXED);
                });
    }

    @Test
    @DisplayName("다시 보고하면 이름과 색인 상태를 갱신한다")
    void 갱신한다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));
        repository.replaceAll(기기_PK, List.of(폴더("sf-1", "문서", SearchFolder.INDEXING)));

        repository.replaceAll(기기_PK, List.of(폴더("sf-1", "내 문서", SearchFolder.INDEXED)));

        assertThat(repository.findAllByDeviceId(기기_PK))
                .singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getDisplayName()).isEqualTo("내 문서");
                    assertThat(saved.getIndexStatus()).isEqualTo(SearchFolder.INDEXED);
                });
    }

    @Test
    @DisplayName("보고에서 빠진 폴더는 지운다 — 사용자가 Agent 에서 뺀 폴더다")
    void 빠진_폴더를_지운다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));
        repository.replaceAll(기기_PK, List.of(
                폴더("sf-1", "문서", SearchFolder.INDEXED),
                폴더("sf-2", "사진", SearchFolder.INDEXED)));

        repository.replaceAll(기기_PK, List.of(폴더("sf-2", "사진", SearchFolder.INDEXED)));

        // 남겨 두면 없는 폴더를 골라 보내고 Agent 가 매번 SEARCH_FOLDER_NOT_FOUND 로 거절한다.
        assertThat(repository.findAllByDeviceId(기기_PK))
                .singleElement()
                .satisfies(saved -> assertThat(saved.getSearchFolderId()).isEqualTo("sf-2"));
    }

    @Test
    @DisplayName("폴더를 모두 뺐다고 보고하면 하나도 남지 않는다")
    void 빈_보고는_전부_지운다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));
        repository.replaceAll(기기_PK, List.of(폴더("sf-1", "문서", SearchFolder.INDEXED)));

        repository.replaceAll(기기_PK, List.of());

        assertThat(repository.findAllByDeviceId(기기_PK)).isEmpty();
        assertThat(repository.pickSearchable(기기_PK)).isEmpty();
    }

    @Test
    @DisplayName("계약에 없는 값은 버린다 — READY 전체가 실패하면 안 된다")
    void 모르는_값을_버린다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));

        repository.replaceAll(기기_PK, List.of(
                폴더("sf-1", "문서", SearchFolder.INDEXED),
                폴더("sf-2", "사진", "미래에생길상태"),
                폴더(null, "이름만", SearchFolder.INDEXED),
                폴더("sf-4", " ", SearchFolder.INDEXED)));

        // 제약 위반으로 트랜잭션이 깨지면 READY 처리가 통째로 실패해 연결이 끊긴다.
        assertThat(repository.findAllByDeviceId(기기_PK))
                .singleElement()
                .satisfies(saved -> assertThat(saved.getSearchFolderId()).isEqualTo("sf-1"));
    }

    @Test
    @DisplayName("이름이 아주 긴 폴더도 저장한다 — 이름 때문에 연결이 끊기면 안 된다")
    void 긴_이름을_잘라_저장한다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));

        // 저장이 제약을 어기면 예외가 handleTextMessage 밖으로 나가 소켓이 닫힌다.
        // Agent 는 재접속해서 같은 READY 를 다시 보내므로 그 PC 는 영영 붙지 못한다.
        repository.replaceAll(기기_PK, List.of(폴더("sf-1", "가".repeat(500), SearchFolder.INDEXED)));

        assertThat(repository.findAllByDeviceId(기기_PK))
                .singleElement()
                .satisfies(saved -> assertThat(saved.getDisplayName()).hasSize(200));
        // 폴더 자체는 살아 있어야 한다. 이름이 길다고 검색을 못 하게 만들 이유가 없다.
        assertThat(repository.pickSearchable(기기_PK)).contains("sf-1");
    }

    @Test
    @DisplayName("이모지가 잘리는 자리에 걸려도 저장한다")
    void 서로게이트를_쪼개지_않는다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));

        // 199자 뒤에 이모지를 두면 잘리는 지점이 서로게이트 쌍 한가운데다.
        // 쪼개면 짝 잃은 서로게이트가 남고 PostgreSQL 이 올바른 UTF-8 로 보지 않아 저장이 실패한다.
        repository.replaceAll(기기_PK, List.of(폴더("sf-1", "가".repeat(199) + "📁폴더", SearchFolder.INDEXED)));

        assertThat(repository.findAllByDeviceId(기기_PK))
                .singleElement()
                .satisfies(saved -> assertThat(saved.getDisplayName()).isEqualTo("가".repeat(199)));
    }

    @Test
    @DisplayName("식별자가 열 길이를 넘으면 버린다 — 잘라내면 다른 폴더를 가리킨다")
    void 긴_식별자는_버린다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));

        repository.replaceAll(기기_PK, List.of(
                폴더("sf-" + "0".repeat(200), "긴 식별자", SearchFolder.INDEXED),
                폴더("sf-1", "문서", SearchFolder.INDEXED)));

        assertThat(repository.findAllByDeviceId(기기_PK))
                .singleElement()
                .satisfies(saved -> assertThat(saved.getSearchFolderId()).isEqualTo("sf-1"));
    }

    @Test
    @DisplayName("색인이 끝난 폴더를 먼저 고른다")
    void 색인된_폴더를_먼저_고른다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));

        repository.replaceAll(기기_PK, List.of(
                폴더("sf-1", "가나다", SearchFolder.INDEXING),
                폴더("sf-2", "하하하", SearchFolder.INDEXED)));

        // 이름 순이면 sf-1 이 먼저지만 색인 중이라 결과가 불완전할 수 있다. 끝난 쪽이 낫다.
        assertThat(repository.pickSearchable(기기_PK)).contains("sf-2");
    }

    @Test
    @DisplayName("색인 중인 폴더뿐이어도 고른다 — Agent 가 검색해 준다")
    void 색인_중인_폴더도_고른다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));

        repository.replaceAll(기기_PK, List.of(폴더("sf-1", "문서", SearchFolder.INDEXING)));

        // Agent 는 색인을 기다리지 않고 READY 를 보내므로 큰 폴더는 INDEXING 으로 보고된다.
        // 끝났다고 알리는 메시지가 계약에 없어, 여기서 버리면 재접속 전까지 /file 이 전부 죽는다.
        assertThat(repository.pickSearchable(기기_PK)).contains("sf-1");
    }

    @Test
    @DisplayName("읽을 수 없는 폴더뿐이면 고르지 않는다")
    void 고를_수_없으면_비운다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));

        // Agent 의 is_searchable() 이 거르는 유일한 상태다. 보내 봐야 거절당한다.
        repository.replaceAll(기기_PK, List.of(폴더("sf-1", "사진", SearchFolder.UNAVAILABLE)));

        assertThat(repository.pickSearchable(기기_PK)).isEmpty();
    }

    @Test
    @DisplayName("고를 수 있는 것이 여럿이면 이름 순으로 정한다 — 같은 명령이 같은 폴더를 본다")
    void 순서가_흔들리지_않는다() {
        long 기기_PK = 준비된_기기(dsl, 사용자(dsl));

        repository.replaceAll(기기_PK, List.of(
                폴더("sf-9", "하하하", SearchFolder.INDEXED),
                폴더("sf-1", "가나다", SearchFolder.INDEXED)));

        assertThat(repository.pickSearchable(기기_PK)).contains("sf-1");
        // 기준이 없으면 같은 명령이 매번 다른 폴더를 뒤진다.
        assertThat(repository.pickSearchable(기기_PK)).contains("sf-1");
    }

    @Test
    @DisplayName("남의 기기 폴더를 고르지 않는다")
    void 기기별로_갈린다() {
        long 사용자_PK = 사용자(dsl);
        long 내_기기 = 준비된_기기(dsl, 사용자_PK);
        long 남의_기기 = 준비된_기기(dsl, 사용자_PK);

        repository.replaceAll(남의_기기, List.of(폴더("sf-1", "문서", SearchFolder.INDEXED)));

        assertThat(repository.pickSearchable(내_기기)).isEmpty();
    }

    @Test
    @DisplayName("여러 기기의 폴더를 한 번에 읽어 기기별로 나눠 준다")
    void 여러_기기를_한_번에_읽는다() {
        long 사용자_PK = 사용자(dsl);
        long 첫_기기 = 준비된_기기(dsl, 사용자_PK);
        long 둘째_기기 = 준비된_기기(dsl, 사용자_PK);
        long 폴더가_없는_기기 = 준비된_기기(dsl, 사용자_PK);

        // 식별자와 이름의 순서를 일부러 어긋나게 둔다. 정렬 기준이 표시 이름이라는 것을
        // 식별자 순서로 착각하면 이 시험이 잡는다.
        repository.replaceAll(첫_기기, List.of(
                폴더("sf-b", "가 폴더", SearchFolder.INDEXED),
                폴더("sf-a", "나 폴더", SearchFolder.INDEXED)));
        repository.replaceAll(둘째_기기, List.of(폴더("sf-c", "사진", SearchFolder.INDEXING)));

        var 기기별 = repository.findAllByDeviceIds(List.of(첫_기기, 둘째_기기, 폴더가_없는_기기));

        assertThat(기기별.get(첫_기기))
                .extracting(record -> record.getDisplayName())
                .containsExactly("가 폴더", "나 폴더");
        assertThat(기기별.get(둘째_기기))
                .extracting(record -> record.getDisplayName())
                .containsExactly("사진");

        // 폴더가 없는 기기는 열쇠 자체가 없다. 부르는 쪽이 빈 목록으로 메운다.
        assertThat(기기별).doesNotContainKey(폴더가_없는_기기);
    }

    @Test
    @DisplayName("기기 목록이 비어 있으면 질의하지 않는다")
    void 빈_목록은_그대로_돌려준다() {
        assertThat(repository.findAllByDeviceIds(List.of())).isEmpty();
    }
}
