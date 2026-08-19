package com.likelion.slash.weather;

import java.util.List;
import java.util.Map;

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
     * 광역 단위. 17개로 고정이라 표로 둔다.
     *
     * <p>아래 접미사 규칙으로는 풀 수 없다 — "서울" 에 "시" 를 붙여도 "서울시" 는 정식명이 아니다.
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
            Map.entry("경기", "경기도"),
            Map.entry("강원", "강원특별자치도"),
            Map.entry("충북", "충청북도"),
            Map.entry("충남", "충청남도"),
            Map.entry("전북", "전북특별자치도"),
            Map.entry("전남", "전라남도"),
            Map.entry("경북", "경상북도"),
            Map.entry("경남", "경상남도"));

    private PlaceName() {
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

        if (trimmed.endsWith("시") || trimmed.endsWith("군") || trimmed.endsWith("구")) {
            return List.of(trimmed);
        }

        return List.of(trimmed + "시", trimmed);
    }
}
