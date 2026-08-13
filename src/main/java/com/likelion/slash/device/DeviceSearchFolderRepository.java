package com.likelion.slash.device;

import static com.likelion.slash.jooq.Tables.DEVICE_SEARCH_FOLDERS;

import com.likelion.slash.common.SlashTime;
import com.likelion.slash.jooq.tables.records.DeviceSearchFoldersRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * {@code device_search_folders} 접근. (WBS W1-03)
 *
 * <p>Agent 가 READY 프레임의 {@code searchFolders} 로 보고한 검색 폴더다.
 * {@code FILE_SEARCH}(/file) 의 필수 인자 {@code searchFolderId} 를 서버가 여기서 고른다.
 *
 * <p>PK 가 {@code (device_id, search_folder_id)} 라 최신 보고가 이전 보고를 덮어쓴다.
 * {@link DeviceCapabilityRepository} 와 같은 방식이다.
 *
 * <p>관련 문서: 3.4.4 · WBS W1-03
 */
@Repository
public class DeviceSearchFolderRepository {

    private final DSLContext dsl;

    public DeviceSearchFolderRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * READY 보고 내용으로 기기의 검색 폴더 목록을 통째로 맞춘다.
     *
     * <p>보고에 없는 폴더는 지운다. 사용자가 Agent 에서 폴더를 뺐는데 목록에 남아 있으면,
     * 서버가 <b>더 이상 없는 폴더를 골라</b> 작업을 보내고 Agent 는 그때마다
     * {@code SEARCH_FOLDER_NOT_FOUND} 로 거절한다. 사용자 눈에는 이유 없는 실패로 보인다.
     *
     * <p>계약에 없는 값은 {@link SearchFolder#isStorable()} 이 먼저 거른다. 제약 위반으로
     * 트랜잭션이 깨지면 READY 처리가 통째로 실패해 연결이 끊긴다.
     */
    public void replaceAll(long deviceId, Collection<SearchFolder> reported) {
        List<SearchFolder> storable = reported.stream().filter(SearchFolder::isStorable).toList();
        List<String> ids = storable.stream().map(SearchFolder::searchFolderId).toList();

        dsl.transaction(configuration -> {
            DSLContext tx = DSL.using(configuration);

            tx.deleteFrom(DEVICE_SEARCH_FOLDERS)
                    .where(DEVICE_SEARCH_FOLDERS.DEVICE_ID.eq(deviceId))
                    .and(ids.isEmpty()
                            ? DSL.noCondition()
                            : DEVICE_SEARCH_FOLDERS.SEARCH_FOLDER_ID.notIn(ids))
                    .execute();

            for (SearchFolder folder : storable) {
                tx.insertInto(DEVICE_SEARCH_FOLDERS)
                        .set(DEVICE_SEARCH_FOLDERS.DEVICE_ID, deviceId)
                        .set(DEVICE_SEARCH_FOLDERS.SEARCH_FOLDER_ID, folder.searchFolderId())
                        .set(DEVICE_SEARCH_FOLDERS.DISPLAY_NAME, folder.displayNameForStorage())
                        .set(DEVICE_SEARCH_FOLDERS.INDEX_STATUS, folder.indexStatus())
                        .set(DEVICE_SEARCH_FOLDERS.REPORTED_AT, SlashTime.now())
                        .onConflict(DEVICE_SEARCH_FOLDERS.DEVICE_ID, DEVICE_SEARCH_FOLDERS.SEARCH_FOLDER_ID)
                        .doUpdate()
                        .set(DEVICE_SEARCH_FOLDERS.DISPLAY_NAME, folder.displayNameForStorage())
                        .set(DEVICE_SEARCH_FOLDERS.INDEX_STATUS, folder.indexStatus())
                        .set(DEVICE_SEARCH_FOLDERS.REPORTED_AT, SlashTime.now())
                        .execute();
            }
        });
    }

    /**
     * 이 기기에서 지금 검색할 수 있는 폴더 하나를 고른다.
     *
     * <p><b>거르는 기준은 Agent 와 같아야 한다.</b> slash-agent 의 {@code file_index.py}
     * {@code is_searchable()} 은 {@code UNAVAILABLE} 만 거르고 {@code INDEXING} 은 검색해 준다.
     * 서버가 여기서 더 좁히면 Agent 가 처리할 수 있는 요청을 우리가 막는 꼴이 된다.
     *
     * <p><b>{@code INDEXING} 을 버리면 안 되는 이유가 하나 더 있다.</b> Agent 는 색인을 기다리지
     * 않고 READY 를 보내므로({@code agent.py} {@code start()}) 폴더가 크면 READY 시점에는
     * {@code INDEXING} 이다. 그런데 색인이 끝났다고 알리는 메시지가 계약에 없다 — READY 는 접속
     * 직후 한 번뿐이고 HEARTBEAT 에는 폴더가 실리지 않는다. {@code INDEXED} 만 고르면 그 PC 는
     * <b>재접속 전까지 {@code /file} 이 전부 실패한다.</b>
     *
     * <p>대신 {@code INDEXED} 를 먼저 고른다. 색인 중인 폴더는 결과가 불완전할 수 있어 끝난 폴더가
     * 있으면 그쪽이 낫다. 같은 등급이 여럿이면 이름 순이다 — 폴더를 고르는 화면이 아직 없어 서버가
     * 정해야 하는데, 순서가 매번 달라지면 같은 명령이 다른 폴더를 뒤진다.
     * <b>사용자가 고르는 화면이 생기면 이 메서드는 사라진다.</b>
     *
     * @return 고른 폴더의 식별자. 비어 있으면 검색할 수 있는 폴더가 하나도 없다
     */
    public Optional<String> pickSearchable(long deviceId) {
        return dsl.select(DEVICE_SEARCH_FOLDERS.SEARCH_FOLDER_ID)
                .from(DEVICE_SEARCH_FOLDERS)
                .where(DEVICE_SEARCH_FOLDERS.DEVICE_ID.eq(deviceId))
                .and(DEVICE_SEARCH_FOLDERS.INDEX_STATUS.ne(SearchFolder.UNAVAILABLE))
                .orderBy(
                        DSL.when(DEVICE_SEARCH_FOLDERS.INDEX_STATUS.eq(SearchFolder.INDEXED), 0).otherwise(1),
                        DEVICE_SEARCH_FOLDERS.DISPLAY_NAME,
                        DEVICE_SEARCH_FOLDERS.SEARCH_FOLDER_ID)
                .limit(1)
                .fetchOptional(DEVICE_SEARCH_FOLDERS.SEARCH_FOLDER_ID);
    }

    public List<DeviceSearchFoldersRecord> findAllByDeviceId(long deviceId) {
        return dsl.selectFrom(DEVICE_SEARCH_FOLDERS)
                .where(DEVICE_SEARCH_FOLDERS.DEVICE_ID.eq(deviceId))
                .orderBy(DEVICE_SEARCH_FOLDERS.DISPLAY_NAME, DEVICE_SEARCH_FOLDERS.SEARCH_FOLDER_ID)
                .fetch();
    }
}
