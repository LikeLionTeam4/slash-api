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
    @DisplayName("도 이름이 앞에 붙으면 그것을 떼고 물어본다")
    void 도_이름을_떼어낸다() {
        // 지오코딩은 "경기도 광주"·"경기도 광주시" 를 찾지 못하고, "광주시" 라야
        // 경기도 광주시(37.41,127.26)가 나온다. (실측)
        assertThat(PlaceName.candidatesOf("경기도 광주")).containsExactly("광주시", "광주");
        assertThat(PlaceName.candidatesOf("경상북도 안동")).containsExactly("안동시", "안동");

        // 이미 행정 단위가 붙어 있으면 그것만 쓴다.
        assertThat(PlaceName.candidatesOf("경기도 광주시")).containsExactly("광주시");
    }

    @Test
    @DisplayName("도 이름을 뗀 뒤에는 광역 표를 다시 보지 않는다")
    void 도를_뗀_뒤에는_광역으로_바꾸지_않는다() {
        // 여기가 이 규칙의 요점이다. "광주" 를 광역 표로 보내면 광주광역시가 되어
        // 도 이름을 붙여 말한 사용자의 뜻과 정반대가 된다.
        assertThat(PlaceName.candidatesOf("경기도 광주")).doesNotContain("광주광역시");

        // 도 이름 없이 "광주" 만 말하면 그때는 광주광역시가 맞다.
        assertThat(PlaceName.candidatesOf("광주")).containsExactly("광주광역시");
    }

    @Test
    @DisplayName("제주도라고 말해도 찾는다")
    void 제주도를_제주시로_바꾼다() {
        // "제주도" 는 지오코딩에 없다. (실측)
        assertThat(PlaceName.candidatesOf("제주도")).containsExactly("제주시");
    }

    @Test
    @DisplayName("제주도는 혼자 오면 지명, 뒤에 지명이 따라오면 도 이름이다")
    void 제주도는_두_쓰임을_가진다() {
        // 혼자 오면 지명이다.
        assertThat(PlaceName.candidatesOf("제주도")).containsExactly("제주시");

        // 뒤에 지명이 따라오면 도 이름이다. 떼지 않으면 "제주도 한림" 을 그대로 물어보게
        // 되는데 지오코딩에 그런 이름은 없다. (실측)
        assertThat(PlaceName.candidatesOf("제주도 한림")).containsExactly("한림시", "한림");
        assertThat(PlaceName.candidatesOf("제주도 서귀포")).containsExactly("서귀포시", "서귀포");
    }

    @Test
    @DisplayName("도 이름이 아닌 두 낱말은 그대로 둔다")
    void 도가_아닌_두_낱말은_건드리지_않는다() {
        // "서울 강남구" 처럼 광역시 + 자치구는 붙여서 물어봐야 한다.
        assertThat(PlaceName.candidatesOf("서울 강남구")).containsExactly("서울 강남구");
    }

    @Test
    @DisplayName("도 이름만 말하면 조회하지 않고 되묻는다")
    void 도_이름만_말하면_되묻는다() {
        // 도 전체의 날씨를 한 좌표로 답할 수 없다. 게다가 "경기도" 는 김포시 좌표가
        // 걸린다 — 사용자는 그것이 김포시인 줄 모른다. (실측 · #89)
        assertThat(PlaceName.candidatesOf("경기도")).isEmpty();
        assertThat(PlaceName.candidatesOf("경기")).isEmpty();
        assertThat(PlaceName.candidatesOf("강원도")).isEmpty();
        assertThat(PlaceName.candidatesOf("전라도")).isEmpty();

        // "제주" 는 도이면서 시라 예외다. "제주 날씨" 는 제주시로 답하는 것이 맞다.
        assertThat(PlaceName.candidatesOf("제주")).containsExactly("제주시");
        assertThat(PlaceName.candidatesOf("제주도")).containsExactly("제주시");
    }

    @Test
    @DisplayName("사용자가 말한 도를 대조용 열쇠로 줄인다")
    void 도_대조_열쇠를_만든다() {
        // 축약형과 정식명이 같은 열쇠가 되어야 맞출 수 있다.
        assertThat(PlaceName.provinceKey("경기도")).isEqualTo("경기");
        assertThat(PlaceName.provinceKey("충청남도")).isEqualTo("충청");
        assertThat(PlaceName.provinceKey("충남")).isEqualTo("충청");
        assertThat(PlaceName.provinceKey("전남")).isEqualTo("전라");
        assertThat(PlaceName.provinceKey("전라도")).isEqualTo("전라");
        assertThat(PlaceName.provinceKey("제주특별자치도")).isEqualTo("제주");

        // 도를 말한 경우에만 뽑힌다.
        assertThat(PlaceName.provinceKeyOf("제주도 성산")).contains("제주");
        assertThat(PlaceName.provinceKeyOf("경기도 광주")).contains("경기");
        assertThat(PlaceName.provinceKeyOf("서울 강남구")).isEmpty();
        assertThat(PlaceName.provinceKeyOf("광주")).isEmpty();
    }

    @Test
    @DisplayName("앞뒤 공백은 떼고 물어본다")
    void 공백을_떼어낸다() {
        assertThat(PlaceName.candidatesOf("  서울 ")).containsExactly("서울특별시");
    }
}
