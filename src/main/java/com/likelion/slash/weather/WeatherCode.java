package com.likelion.slash.weather;

import java.util.Map;

/**
 * WMO 날씨 코드를 사람이 읽을 말로 바꾼다. (Open-Meteo {@code weather_code})
 *
 * <p>숫자를 그대로 화면에 내보내면 아무 뜻도 되지 않는다. 문구를 서버에서 정하는 이유는
 * 화면마다 다르게 옮겨 적는 것을 막기 위해서다.
 *
 * <p>목록은 Open-Meteo 문서의 WMO 4677 발췌를 따른다. 이 표에 없는 코드가 와도 날씨 조회
 * 자체는 성공해야 하므로 알 수 없는 값은 빈 설명으로 둔다.
 */
public final class WeatherCode {

    private static final Map<Integer, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry(0, "맑음"),
            Map.entry(1, "대체로 맑음"),
            Map.entry(2, "구름 조금"),
            Map.entry(3, "흐림"),
            Map.entry(45, "안개"),
            Map.entry(48, "짙은 안개"),
            Map.entry(51, "약한 이슬비"),
            Map.entry(53, "이슬비"),
            Map.entry(55, "강한 이슬비"),
            Map.entry(56, "약한 어는 이슬비"),
            Map.entry(57, "어는 이슬비"),
            Map.entry(61, "약한 비"),
            Map.entry(63, "비"),
            Map.entry(65, "강한 비"),
            Map.entry(66, "약한 어는 비"),
            Map.entry(67, "어는 비"),
            Map.entry(71, "약한 눈"),
            Map.entry(73, "눈"),
            Map.entry(75, "강한 눈"),
            Map.entry(77, "싸락눈"),
            Map.entry(80, "약한 소나기"),
            Map.entry(81, "소나기"),
            Map.entry(82, "강한 소나기"),
            Map.entry(85, "약한 소낙눈"),
            Map.entry(86, "소낙눈"),
            Map.entry(95, "뇌우"),
            Map.entry(96, "우박을 동반한 뇌우"),
            Map.entry(99, "우박을 동반한 강한 뇌우"));

    private WeatherCode() {
    }

    /** 모르는 코드면 빈 문자열. 조회 자체를 실패로 만들지 않는다. */
    public static String describe(Integer code) {
        return code == null ? "" : DESCRIPTIONS.getOrDefault(code, "");
    }
}
