package com.likelion.slash.weather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Open-Meteo 지오코딩 응답. ({@code GET geocoding-api.open-meteo.com/v1/search})
 *
 * <p>찾지 못하면 {@code results} 가 아예 없다. 빈 배열이 아니라 필드가 빠지므로 {@code null} 을
 * 함께 다룬다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeocodingResponse(List<Place> results) {

    /**
     * @param name        찾아낸 정식 지명. 사용자가 입력한 말과 다를 수 있어 결과에 함께 싣는다.
     * @param admin1      상위 행정구역. "수원시(경기도)" 처럼 어디인지 분명히 하는 데 쓴다.
     * @param countryCode 국가 코드. 같은 이름이 여러 나라에 있을 때 무엇이 걸렸는지 남긴다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(String name,
                        double latitude,
                        double longitude,
                        String country,
                        String countryCode,
                        String admin1,
                        String timezone) {
    }

    public List<Place> resultsOrEmpty() {
        return results == null ? List.of() : results;
    }
}
