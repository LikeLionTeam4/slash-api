package com.likelion.slash.weather;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 사람이 말하는 지명을 Open-Meteo 가 찾을 수 있는 이름으로 바꾸는 규칙.
 *
 * <p>여기서 지키려는 것은 <b>엉뚱한 좌표를 답으로 내놓지 않는 것</b>이다. 없다고 답하면
 * 다음 후보로 넘어가면 그만이지만, 틀린 좌표는 그대로 사용자의 날씨가 된다.
 */
class PlaceNameTest {

    @Test
    @DisplayName("광역 단위는 정식명으로만 물어본다")
    void 광역은_정식명으로_바꾼다() {
        // "서울" 은 결과가 없고 "서울특별시" 라야 나온다. (실측)
        assertThat(PlaceName.candidatesOf("서울")).containsExactly("서울특별시");
        assertThat(PlaceName.candidatesOf("부산")).containsExactly("부산광역시");
        assertThat(PlaceName.candidatesOf("제주")).containsExactly("제주시");
    }

    @Test
    @DisplayName("시를 붙인 쪽을 먼저 물어본다")
    void 시를_먼저_물어본다() {
        // "천안" 은 북한의 동명 지역이, "수원" 은 다른 좌표가 걸린다. (실측)
        assertThat(PlaceName.candidatesOf("천안")).containsExactly("천안시", "천안");
        assertThat(PlaceName.candidatesOf("수원")).containsExactly("수원시", "수원");
    }

    @Test
    @DisplayName("해외 지명은 원문으로도 물어본다")
    void 해외_지명도_통과시킨다() {
        // "도쿄시" 는 없다고 나오므로 두 번째 후보인 원문이 쓰인다.
        assertThat(PlaceName.candidatesOf("도쿄")).containsExactly("도쿄시", "도쿄");
    }

    @Test
    @DisplayName("이미 행정 단위가 붙어 있으면 그대로 물어본다")
    void 행정단위가_있으면_그대로_쓴다() {
        assertThat(PlaceName.candidatesOf("수원시")).containsExactly("수원시");
        assertThat(PlaceName.candidatesOf("양평군")).containsExactly("양평군");
        assertThat(PlaceName.candidatesOf("강남구")).containsExactly("강남구");
    }

    @Test
    @DisplayName("앞뒤 공백은 떼고 물어본다")
    void 공백을_떼어낸다() {
        assertThat(PlaceName.candidatesOf("  서울 ")).containsExactly("서울특별시");
    }
}
