import { AsyncJobStatus } from "@slash-api-mock/contracts";

export interface SummaryJobResult {
  summary: string;
  model: string;
  durationMs: number;
  inputTokenCount: number;
  outputTokenCount: number;
  totalTokenCount: number;
}

export interface SummaryJobOptions {
  onStatus?: (status: AsyncJobStatus) => void;
  minDelayMs?: number;
  maxDelayMs?: number;
}

/** 실제 Gemma 추론 없이 고정 규칙으로 요약 Fixture를 만든다 (지시문: 실 Gemma 사용처럼 표현 금지). */
export const MOCK_LLM_MODEL_ID = "mock-llm-fixture-v1";

const MAX_INPUT_TOKENS = 4096;
const MAX_OUTPUT_TOKENS = 512;

/** 한국어·영어 혼용 텍스트에 대한 대략적 토큰 추정치(실 토크나이저 아님, 시험용). */
function estimateTokenCount(text: string): number {
  return Math.max(1, Math.ceil(text.trim().length / 2.5));
}

function buildFixtureSummary(text: string): string {
  const trimmed = text.trim();
  const sentences = trimmed.split(/(?<=[.?!]|다\.)\s+/).filter(Boolean);
  const picked = sentences.slice(0, 2).join(" ");
  const summary = picked.length > 0 ? picked : trimmed;
  return summary.length > 240 ? `${summary.slice(0, 240)}…` : summary;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function randomDelay(min: number, max: number): number {
  return Math.floor(min + Math.random() * Math.max(0, max - min));
}

export async function runSummaryJob(
  text: string,
  options: SummaryJobOptions = {}
): Promise<SummaryJobResult> {
  const minDelayMs = options.minDelayMs ?? Number(process.env.MOCK_LLM_MIN_DELAY_MS ?? 500);
  const maxDelayMs = options.maxDelayMs ?? Number(process.env.MOCK_LLM_MAX_DELAY_MS ?? 1500);
  const startedAt = Date.now();

  options.onStatus?.("QUEUED");
  await delay(randomDelay(minDelayMs, maxDelayMs));

  options.onStatus?.("RUNNING");
  await delay(randomDelay(minDelayMs, maxDelayMs));

  const summary = buildFixtureSummary(text);
  const inputTokenCount = Math.min(MAX_INPUT_TOKENS, estimateTokenCount(text));
  const outputTokenCount = Math.min(MAX_OUTPUT_TOKENS, estimateTokenCount(summary));

  options.onStatus?.("SUCCEEDED");

  return {
    summary,
    model: MOCK_LLM_MODEL_ID,
    durationMs: Date.now() - startedAt,
    inputTokenCount,
    outputTokenCount,
    totalTokenCount: inputTokenCount + outputTokenCount,
  };
}
