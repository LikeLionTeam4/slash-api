package com.likelion.slash.weather;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 사람이 말하는 지명을 Open-Meteo 가 찾을 수 있는 이름으로 바꾼다.
 *
 * <p><b>왜 필요한가.</b> Open-Meteo 지오코딩은 한국 지명을 <b>정식 행정명으로만</b> 찾는다.
 * 실측 결과는 이렇다.
 *
 * <pre>
 * 서울      → 없음          서울특별시 → 서울특별시(KR) 37.57,126.98
 * 제주      → 없음          제주시     → 제주시(KR)     33.51,126.52
 * 부산      → Pusan 36.38   부산광역시 → 부산광역시(KR) 35.10,129.03
 * 수원      → 35.36(틀림)   수원시     → 수원시(KR)     37.29,127.01
 * </pre>
 *
 * <p>"부산" 과 "수원" 이 특히 나쁘다 — <b>없다고 답하지 않고 엉뚱한 곳을 준다.</b>
 * 사용자는 다른 동네 날씨를 자기 동네 날씨로 읽게 된다.
 *
 * <p>해외 지명은 한글로도 정확해서(도쿄·뉴욕·파리·런던 확인) 손대지 않는다.
 */
final class PlaceName {

    /**
     * 특별시·광역시. 접미사 규칙으로는 풀 수 없어 표로 둔다 — "서울" 에 "시" 를 붙여도
     * "서울시" 는 정식명이 아니다.
     *
     * <p><b>도 여덟 개는 여기 없다.</b> 정식명으로 물어도 지오코딩이 제대로 답하지 않기
     * 때문이다 — 일곱은 "없음" 이고 <b>경기도만 김포시 좌표를 준다</b>(실측). 도 이름만
     * 말한 경우는 {@link #PROVINCES} 쪽에서 되묻는 것으로 다룬다. (#89)
     *
     * <p>"제주" 는 도이면서 시라 여기 남는다 — "제주 날씨" 는 제주시로 답하는 것이 맞다.
     */
    private static final Map<String, String> WIDE_AREAS = Map.ofEntries(
            Map.entry("서울", "서울특별시"),
            Map.entry("부산", "부산광역시"),
            Map.entry("대구", "대구광역시"),
            Map.entry("인천", "인천광역시"),
            Map.entry("광주", "광주광역시"),
            Map.entry("대전", "대전광역시"),
            Map.entry("울산", "울산광역시"),
            Map.entry("세종", "세종특별자치시"),
            Map.entry("제주", "제주시"),
            // "제주도" 도 사람들이 그대로 쓴다. 지오코딩은 이 이름을 찾지 못한다(실측).
            Map.entry("제주도", "제주시"));

    /**
     * 앞에 붙는 도 이름.
     *
     * <p>NLU 는 대부분 도 이름을 떼고 시·군만 넘기지만, <b>같은 이름이 두 곳에 있으면 떼지
     * 못한다</b> — "경기도 광주"(경기도 광주시)와 "광주"(광주광역시)를 가르는 것이 그 값의
     * 유일한 근거이기 때문이다. (slash-nlu#22 · #85 에서 정한 경계 — NLU 는 의미를,
     * 여기서는 조회용 이름을 맡는다)
     *
     * <p><b>"제주도" 는 이 표와 {@link #WIDE_AREAS} 양쪽에 있다.</b> 혼자 오면 지명이고
     * ("제주도 날씨" → 제주시), 뒤에 지명이 따라오면 도 이름이다("제주도 한림" → 한림).
     * {@code WIDE_AREAS} 를 먼저 보므로 두 쓰임이 부딪히지 않는다.
     */
    private static final Set<String> PROVINCES = Set.of(
            "경기", "경기도",
            "강원", "강원도", "강원특별자치도",
            "충북", "충청북도", "충남", "충청남도", "충청도",
            "전북", "전라북도", "전북특별자치도", "전남", "전라남도", "전라도",
            "경북", "경상북도", "경남", "경상남도", "경상도",
            "제주", "제주도", "제주특별자치도");

    /**
     * 축약 도 이름을 정식명과 같은 열쇠로 맞춘다. 나머지는 앞 두 글자를 그대로 쓴다.
     *
     * <p>{@code 충북}·{@code 전남} 처럼 두 글자를 줄여 쓴 것은 앞 두 글자만 잘라서는
     * 정식명과 맞출 수 없다 — {@code 충북} 은 {@code 충청} 이 아니라 {@code 충북} 이 된다.
     */
    private static final Map<String, String> PROVINCE_KEY_ALIASES = Map.of(
            "충북", "충청", "충남", "충청",
            "전북", "전라", "전남", "전라",
            "경북", "경상", "경남", "경상");

    private PlaceName() {
    }

    /**
     * 사용자가 말한 지명에서 도 이름을 뽑아 대조용 열쇠로 돌려준다. 도 이름이 없으면 비어 있다.
     *
     * <p><b>지오코딩이 엉뚱한 곳을 주는 것을 막기 위한 값이다.</b> "제주도 성산" 을 물으면
     * 제공자는 <b>강원도 홍천군의 성산</b>을 준다(실측). 사용자가 도를 말했으면 결과의
     * {@code admin1} 과 맞는지 볼 수 있다. (#91)
     */
    static Optional<String> provinceKeyOf(String location) {
        String[] words = location.trim().split("\\s+");
        if (words.length != 2 || !PROVINCES.contains(words[0])) {
            return Optional.empty();
        }
        return Optional.of(provinceKey(words[0]));
    }

    /**
     * 도 이름을 대조용 열쇠로 줄인다.
     *
     * <pre>
     * 경기도 · 경기          → 경기
     * 충청남도 · 충남        → 충청
     * 제주특별자치도 · 제주도  → 제주
     * 전라도 · 전라남도 · 전남 → 전라
     * </pre>
     *
     * <p>{@code 전라도} 처럼 남북을 가르지 않는 말도 {@code 전라} 가 되어 전라남도·전라북도
     * 어느 쪽과도 맞는다. 사용자가 그만큼만 말했으므로 그 이상 좁히지 않는 것이 맞다.
     */
    static String provinceKey(String provinceName) {
        String trimmed = provinceName.trim();
        String alias = PROVINCE_KEY_ALIASES.get(trimmed);
        if (alias != null) {
            return alias;
        }
        return trimmed.length() <= 2 ? trimmed : trimmed.substring(0, 2);
    }

    /**
     * 찾아볼 이름을 순서대로 돌려준다. 앞의 것이 걸리면 뒤는 시도하지 않는다.
     *
     * <p><b>"시" 를 붙인 쪽을 먼저 본다.</b> 원문을 먼저 보면 엉뚱한 곳이 걸린다 —
     * 실측한 것만 이렇다.
     *
     * <pre>
     * 입력   "OO시"                    원문
     * 천안   천안시 36.81,127.15 (맞음)  천안(<b>북한</b>) 38.50,126.90
     * 수원   수원시 37.29,127.01 (맞음)  수원 35.36,126.53
     * 안양   안양시 37.39,126.93 (맞음)  Anyang 36.96,127.15
     * 창원   창원시 35.23,128.68 (맞음)  창원 35.42,127.68
     * </pre>
     *
     * <p>없다고 답하면 다음 후보로 넘어가면 그만이지만, <b>엉뚱한 좌표는 그대로 답이 된다.</b>
     * 사용자는 다른 동네 날씨를 자기 동네 날씨로 읽는다.
     *
     * <p>해외 지명은 이 순서로도 다치지 않는다 — "도쿄시"·"뉴욕시"·"파리시" 는 모두 없다고
     * 나와서 원문으로 넘어간다. "포항" 처럼 "포항시" 가 없는 경우도 같다.
     */
    static List<String> candidatesOf(String location) {
        String trimmed = location.trim();

        String wideArea = WIDE_AREAS.get(trimmed);
        if (wideArea != null) {
            // 광역 단위는 정식명이 확실하므로 원문을 시도하지 않는다.
            return List.of(wideArea);
        }

        // 도 이름이 앞에 붙었으면 그것을 떼고 뒤만 남긴다. 지오코딩은 그 조합을 못 찾는다.
        //
        //   경기도 광주    → 광주시 37.41,127.26 (경기도 광주시)  ← 맞음
        //   경기도 광주    그대로 → 없음
        //   경기도 광주시  그대로 → 없음
        //
        // 뗀 뒤에 WIDE_AREAS 를 다시 보지 않는 것이 요점이다 — "경기도 광주" 의 "광주" 를
        // 그 표로 보내면 광주광역시가 되어, 도 이름을 붙여 말한 사용자의 뜻과 정반대가
        // 된다. 도 이름이 앞에 있다는 것 자체가 "그 광역시가 아니다" 라는 신호다.
        // 도 이름만 말했으면 조회하지 않는다. 도 전체의 날씨를 한 좌표로 답할 수 없고,
        // 실제로 "경기도" 는 김포시 좌표가 걸린다 — 사용자는 그것이 김포시인 줄 모른다.
        // 빈 후보를 돌려주면 WeatherClient 가 LOCATION_NOT_FOUND 로 마감하고, 화면에는
        // "시·군 이름으로 다시 말씀해 주세요" 가 나간다. (#89)
        if (PROVINCES.contains(trimmed)) {
            return List.of();
        }

        String[] words = trimmed.split("\\s+");
        if (words.length == 2 && PROVINCES.contains(words[0])) {
            return suffixed(words[1]);
        }

        return suffixed(trimmed);
    }

    /**
     * {@code 시} 를 붙인 쪽을 먼저, 원문을 뒤에 둔다.
     *
     * <p>{@code 시} 가 없는 곳도 있어서(봉화군 — {@code 봉화시} 는 없고 {@code 봉화} 로 걸린다)
     * 원문을 폴백으로 남긴다.
     */
    private static List<String> suffixed(String name) {
        if (name.endsWith("시") || name.endsWith("군") || name.endsWith("구")) {
            return List.of(name);
        }
        return List.of(name + "시", name);
    }
}
