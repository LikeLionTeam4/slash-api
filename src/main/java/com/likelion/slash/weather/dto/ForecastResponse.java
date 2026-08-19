package com.likelion.slash.weather.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Open-Meteo 현재 날씨 응답. ({@code GET api.open-meteo.com/v1/forecast})
 *
 * <p>필드 이름이 {@code temperature_2m} 처럼 지면에서의 높이를 달고 온다. 우리 쪽 이름과 달라
 * {@link JsonProperty} 로 명시한다 — Mapper 설정에 기대면 다른 응답까지 영향을 받는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ForecastResponse(Current current) {

    /**
     * @param temperature         기온(°C)
     * @param apparentTemperature 체감온도(°C). 여름·겨울에 실제 기온보다 이쪽이 더 와닿는다.
     * @param weatherCode         WMO 날씨 코드. 사람이 읽을 말로 바꿔서 내보낸다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Current(
            String time,
            @JsonProperty("temperature_2m") Double temperature,
            @JsonProperty("apparent_temperature") Double apparentTemperature,
            @JsonProperty("relative_humidity_2m") Integer humidity,
            @JsonProperty("precipitation") Double precipitation,
            @JsonProperty("weather_code") Integer weatherCode,
            @JsonProperty("wind_speed_10m") Double windSpeed) {
    }
}
