package com.likelion.slash.weather;

import static org.assertj.core.api.Assertions.assertThat;

import com.likelion.slash.common.error.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Open-Meteo 와의 계약 확인.
 *
 * <p>대역 서버를 실제로 띄워 <b>무엇을 물어보는지</b>까지 본다. 이 연동의 핵심 판단이
 * "어떤 이름으로 묻는가" 라서, 응답만 흉내 내면 정작 지키려던 것이 확인되지 않는다.
 */
class WeatherClientTest {

    private HttpServer server;

    /** 지오코딩에 실제로 실려 온 {@code name} 값. 물어본 순서대로 쌓인다. */
    private final List<String> 물어본_이름 = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("좌표를 찾아 현재 날씨를 받아 온다")
    void 날씨를_받는다() throws Exception {
        서버를_띄운다(name -> 지오코딩_결과("수원시", "경기도", 37.29, 127.01), 예보_결과());

        WeatherOutcome outcome = client().lookup("수원");

        assertThat(outcome).isInstanceOf(WeatherOutcome.Success.class);
        WeatherOutcome.Success success = (WeatherOutcome.Success) outcome;
        assertThat(success.place().name()).isEqualTo("수원시");
        assertThat(success.place().admin1()).isEqualTo("경기도");
        assertThat(success.current().temperature()).isEqualTo(27.4);
        assertThat(success.current().apparentTemperature()).isEqualTo(32.7);
        assertThat(success.current().weatherCode()).isEqualTo(2);
    }

    @Test
    @DisplayName("시를 붙인 이름을 먼저 물어보고 걸리면 원문은 묻지 않는다")
    void 시를_먼저_묻는다() throws Exception {
        서버를_띄운다(name -> 지오코딩_결과("수원시", "경기도", 37.29, 127.01), 예보_결과());

        client().lookup("수원");

        // 원문을 먼저 물으면 다른 좌표가 걸린다. 순서가 이 연동의 핵심이다.
        assertThat(물어본_이름).containsExactly("수원시");
    }

    @Test
    @DisplayName("첫 후보가 없으면 원문으로 다시 물어본다")
    void 없으면_원문으로_다시_묻는다() throws Exception {
        // "도쿄시" 는 없고 "도쿄" 는 있다. (실측)
        서버를_띄운다(
                name -> name.equals("도쿄") ? 지오코딩_결과("도쿄", null, 35.69, 139.69) : "{}",
                예보_결과());

        WeatherOutcome outcome = client().lookup("도쿄");

        assertThat(물어본_이름).containsExactly("도쿄시", "도쿄");
        assertThat(outcome).isInstanceOf(WeatherOutcome.Success.class);
    }

    @Test
    @DisplayName("어느 후보로도 못 찾으면 지역을 못 찾았다고 알린다")
    void 못_찾으면_지역_오류다() throws Exception {
        서버를_띄운다(name -> "{}", 예보_결과());

        WeatherOutcome outcome = client().lookup("없는동네");

        WeatherOutcome.Failure failure = (WeatherOutcome.Failure) outcome;

        // 외부 서비스가 멈춘 것과 나눈다 — 사용자가 할 수 있는 일이 다르다.
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
        assertThat(failure.message()).contains("없는동네");
    }

    @Test
    @DisplayName("예보를 받지 못하면 외부 서비스 오류로 알린다")
    void 예보를_못_받으면_서비스_오류다() throws Exception {
        서버를_띄운다(name -> 지오코딩_결과("수원시", "경기도", 37.29, 127.01), "{}");

        WeatherOutcome outcome = client().lookup("수원");

        WeatherOutcome.Failure failure = (WeatherOutcome.Failure) outcome;
        assertThat(failure.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
    }

    @Test
    @DisplayName("모르는 날씨 코드가 와도 조회는 성공한다")
    void 모르는_코드도_통과시킨다() throws Exception {
        서버를_띄운다(name -> 지오코딩_결과("수원시", "경기도", 37.29, 127.01), """
                {"current":{"time":"2026-08-19T11:00","temperature_2m":27.4,"weather_code":9999}}""");

        WeatherOutcome outcome = client().lookup("수원");

        assertThat(outcome).isInstanceOf(WeatherOutcome.Success.class);
        assertThat(WeatherCode.describe(9999)).isEmpty();
    }

    private WeatherClient client() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new WeatherClient(RestClient.builder(), baseUrl, baseUrl, Duration.ofSeconds(5));
    }

    /**
     * 한 서버가 두 경로를 다 받는다. 실제로는 호스트가 다르지만 경로가 갈려서 구분에는 문제가 없다.
     *
     * @param 지오코딩 물어본 이름을 받아 돌려줄 본문을 정한다. 후보에 따라 다르게 답하는 데 쓴다.
     */
    @Test
    @DisplayName("말한 도와 다른 곳이 걸리면 답으로 쓰지 않는다")
    void 다른_도의_동명이지를_거른다() throws Exception {
        // "제주도 성산" 을 물으면 제공자가 강원도 홍천군의 성산을 준다. (실측 · #91)
        서버를_띄운다(name -> 지오코딩_결과("성산", "강원도", 37.77, 127.97), 예보_결과());

        WeatherOutcome outcome = client().lookup("제주도 성산");

        // 없다고 답하면 사용자가 다시 말하면 그만이지만, 저 좌표는 그대로 답이 된다.
        assertThat(outcome).isInstanceOf(WeatherOutcome.Failure.class);
        assertThat(((WeatherOutcome.Failure) outcome).errorCode())
                .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
    }

    @Test
    @DisplayName("말한 도와 같은 곳이면 그대로 쓴다")
    void 같은_도면_통과시킨다() throws Exception {
        서버를_띄운다(name -> 지오코딩_결과("광주시", "경기도", 37.41, 127.26), 예보_결과());

        WeatherOutcome outcome = client().lookup("경기도 광주");

        assertThat(outcome).isInstanceOf(WeatherOutcome.Success.class);
        assertThat(((WeatherOutcome.Success) outcome).place().name()).isEqualTo("광주시");
    }

    @Test
    @DisplayName("찾아낸 곳이 광역시면 도끼리 맞춰 보지 않는다")
    void 광역시는_도로_대조하지_않는다() throws Exception {
        // 광주광역시는 전라남도에서 갈라져 나온 광역시라 "전라도 광주" 라고 부르는데
        // admin1 은 "광주광역시" 다. 도끼리 맞춰 보면 맞는 답을 버리게 된다. (#91)
        서버를_띄운다(name -> 지오코딩_결과("광주광역시", "광주광역시", 35.15, 126.92), 예보_결과());

        WeatherOutcome outcome = client().lookup("전라도 광주");

        assertThat(outcome).isInstanceOf(WeatherOutcome.Success.class);
        assertThat(((WeatherOutcome.Success) outcome).place().name()).isEqualTo("광주광역시");
    }

    @Test
    @DisplayName("도 이름만 말하면 조회하지 않는다")
    void 도_이름만_말하면_묻지_않는다() throws Exception {
        서버를_띄운다(name -> 지오코딩_결과("경기도", "경기도", 37.59, 126.77), 예보_결과());

        WeatherOutcome outcome = client().lookup("경기도");

        // 물어보면 김포시 좌표가 걸린다. 아예 묻지 않는다. (#89)
        assertThat(물어본_이름).isEmpty();
        assertThat(outcome).isInstanceOf(WeatherOutcome.Failure.class);
        assertThat(((WeatherOutcome.Failure) outcome).errorCode())
                .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
    }

    private void 서버를_띄운다(Function<String, String> 지오코딩, String 예보) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/v1/search", exchange -> {
            String name = 질의값(exchange, "name");
            물어본_이름.add(name);
            보낸다(exchange, 지오코딩.apply(name));
        });
        server.createContext("/v1/forecast", exchange -> 보낸다(exchange, 예보));
        server.start();
    }

    private String 질의값(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getRawQuery();
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private String 지오코딩_결과(String name, String admin1, double lat, double lon) {
        String admin = admin1 == null ? "null" : "\"" + admin1 + "\"";
        return """
                {"results":[{"name":"%s","latitude":%s,"longitude":%s,"country":"대한민국",
                "country_code":"KR","admin1":%s,"timezone":"Asia/Seoul"}]}"""
                .formatted(name, lat, lon, admin);
    }

    private String 예보_결과() {
        return """
                {"current":{"time":"2026-08-19T11:00","temperature_2m":27.4,
                "apparent_temperature":32.7,"relative_humidity_2m":78,
                "precipitation":0.0,"weather_code":2,"wind_speed_10m":2.6}}""";
    }

    private void 보낸다(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
