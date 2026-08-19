package com.likelion.slash.weather;

import com.likelion.slash.common.error.ErrorCode;
import com.likelion.slash.weather.dto.ForecastResponse;
import com.likelion.slash.weather.dto.GeocodingResponse;

/**
 * 날씨 조회의 결말.
 *
 * <p>실패도 Task 에 남겨야 하는 정상적인 결말이라 값으로 돌려준다. 요약과 같은 짜임이다.
 * ({@link com.likelion.slash.llm.LlmSummaryOutcome})
 */
public sealed interface WeatherOutcome {

    record Success(GeocodingResponse.Place place, ForecastResponse.Current current)
            implements WeatherOutcome {
    }

    /**
     * @param errorCode 사용자에게 보일 우리 쪽 코드
     * @param message   사용자에게 보일 말
     */
    record Failure(ErrorCode errorCode, String message) implements WeatherOutcome {
    }
}
