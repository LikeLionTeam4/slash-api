import { nowIsoKst } from "@slash-api-mock/contracts";

/** BACKEND_SERVICE 경로 Fixture: 실제 기상 API 호출 없이 위치 문자열을 결정적으로 해싱해 값을 만든다. */
export interface WeatherResult {
  location: string;
  temperatureCelsius: number;
  condition: string;
  precipitationProbability: number;
  observedAt: string;
  source: string;
}

const CONDITIONS = ["맑음", "구름 조금", "흐림", "비", "눈"];

function hashString(input: string): number {
  let hash = 0;
  for (let i = 0; i < input.length; i += 1) {
    hash = (hash * 31 + input.charCodeAt(i)) >>> 0;
  }
  return hash;
}

export function lookupWeather(location: string): WeatherResult {
  const hash = hashString(location);
  return {
    location,
    temperatureCelsius: (hash % 350) / 10 - 5, // -5.0 ~ 30.0
    condition: CONDITIONS[hash % CONDITIONS.length],
    precipitationProbability: hash % 101,
    observedAt: nowIsoKst(),
    source: "mock-weather-fixture",
  };
}
