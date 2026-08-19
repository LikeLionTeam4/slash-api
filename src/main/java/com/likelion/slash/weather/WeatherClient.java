package com.likelion.slash.weather;

import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.weather.dto.ForecastResponse;
import com.likelion.slash.weather.dto.GeocodingResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Open-Meteo 날씨 조회. (P0 {@code WEATHER_LOOKUP})
 *
 * <p><b>API 키가 없다.</b> 그래서 Secrets Manager → ESO → env 배선 없이 붙는다. 기능 동결
 * 전에 붙일 수 있는 이유가 대체로 이것이다. 비상업적 사용에 한해 키 없이 열려 있다.
 *
 * <p>두 번 부른다 — 지명을 좌표로 바꾸고(geocoding), 그 좌표의 현재 날씨를 받는다(forecast).
 * 지오코딩이 한국 지명을 정식 행정명으로만 찾아서, 무엇을 물어볼지는 {@link PlaceName} 이 정한다.
 *
 * <p><b>자동 재시도를 하지 않는다.</b> 사용자가 다시 누르는 것이 가장 싸다. NLU 와 같은 판단이다.
 */
@Component
public class WeatherClient {

    private static final Logger log = LoggerFactory.getLogger(WeatherClient.class);

    private static final String GEOCODING_PATH = "/v1/search";
    private static final String FORECAST_PATH = "/v1/forecast";

    /** 받아 올 현재 값. 화면이 한 줄로 안내할 만큼만 고른다. */
    private static final String CURRENT_FIELDS =
            "temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m";

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);

    private final RestClient geocodingClient;
    private final RestClient forecastClient;

    public WeatherClient(RestClient.Builder builder,
                         @Value("${slash.weather.geocoding-base-url}") String geocodingBaseUrl,
                         @Value("${slash.weather.forecast-base-url}") String forecastBaseUrl,
                         @Value("${slash.weather.timeout}") Duration timeout) {

        this.geocodingClient = build(builder, geocodingBaseUrl, timeout);
        this.forecastClient = build(builder, forecastBaseUrl, timeout);
    }

    /**
     * 지명의 현재 날씨를 조회한다.
     *
     * @return 성공이면 찾아낸 지명과 현재 값, 실패면 사용자에게 보일 사유. <b>예외를 던지지 않는다.</b>
     */
    public WeatherOutcome lookup(String location) {
        Optional<GeocodingResponse.Place> found = geocode(location);
        if (found.isEmpty()) {
            log.info("날씨를 조회할 지역을 찾지 못했다 location={}", location);
            return new WeatherOutcome.Failure(ErrorCode.LOCATION_NOT_FOUND,
                    "'" + location + "' 의 날씨를 찾지 못했습니다. 시·군 이름으로 다시 말씀해 주세요.");
        }

        GeocodingResponse.Place place = found.get();
        try {
            ForecastResponse forecast = forecastClient.get()
                    .uri(builder -> builder.path(FORECAST_PATH)
                            .queryParam("latitude", place.latitude())
                            .queryParam("longitude", place.longitude())
                            .queryParam("current", CURRENT_FIELDS)
                            .queryParam("timezone", "auto")
                            .build())
                    .retrieve()
                    .body(ForecastResponse.class);

            if (forecast == null || forecast.current() == null) {
                log.warn("날씨 응답이 비어 있다 location={}", location);
                return 이용할_수_없음();
            }
            return new WeatherOutcome.Success(place, forecast.current());

        } catch (Exception e) {
            log.warn("날씨 조회 실패 location={}: {}", location, e.toString());
            return 이용할_수_없음();
        }
    }

    /**
     * 지명을 좌표로 바꾼다. 후보를 순서대로 물어보고 처음 걸리는 것을 쓴다.
     *
     * <p>후보가 여럿인 이유는 {@link PlaceName} 에 적었다 — 원문을 그대로 물으면 엉뚱한 곳이
     * 걸리는 지명이 있다.
     */
    private Optional<GeocodingResponse.Place> geocode(String location) {
        for (String candidate : PlaceName.candidatesOf(location)) {
            try {
                GeocodingResponse response = geocodingClient.get()
                        .uri(builder -> builder.path(GEOCODING_PATH)
                                .queryParam("name", candidate)
                                .queryParam("count", 1)
                                .queryParam("language", "ko")
                                .queryParam("format", "json")
                                .build())
                        .retrieve()
                        .body(GeocodingResponse.class);

                List<GeocodingResponse.Place> places =
                        response == null ? List.of() : response.resultsOrEmpty();

                if (!places.isEmpty()) {
                    return Optional.of(places.get(0));
                }

            } catch (Exception e) {
                // 한 후보의 실패로 나머지를 포기하지 않는다. 모두 실패하면 아래에서 빈 값이 된다.
                log.warn("지역 조회 실패 candidate={}: {}", candidate, e.toString());
            }
        }
        return Optional.empty();
    }

    private WeatherOutcome 이용할_수_없음() {
        return new WeatherOutcome.Failure(ErrorCode.UPSTREAM_UNAVAILABLE,
                "날씨를 가져오지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
    }

    private static RestClient build(RestClient.Builder builder, String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) timeout.minus(CONNECT_TIMEOUT).toMillis());

        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
