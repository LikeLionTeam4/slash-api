import {
  TaskType,
  TASK_TYPE_REQUIRED_PARAMETERS,
  SLASH_COMMAND_TASK_TYPE,
} from "@slash-api-mock/contracts";

export interface NluResult {
  taskType: TaskType | null;
  parameters: Record<string, unknown>;
  confidence: number;
  missingRequiredParameters: string[];
}

/**
 * mock-nlu는 실제 Kiwi/LLM 없이 결정적 규칙만으로 TaskType을 분류한다 (지시문 7절).
 * `searchFolderId`/`workspaceId`처럼 텍스트에서 유도할 수 없는 구조적 parameter는
 * 여기서는 판단하지 않고 mock-api가 연결된 Agent의 READY 정보로 보완한다.
 */
const STRUCTURAL_PARAMETERS = new Set(["searchFolderId", "workspaceId"]);

function textDerivedRequired(taskType: TaskType): string[] {
  return TASK_TYPE_REQUIRED_PARAMETERS[taskType].filter(
    (key) => !STRUCTURAL_PARAMETERS.has(key)
  );
}

function missingFrom(taskType: TaskType, parameters: Record<string, unknown>): string[] {
  return textDerivedRequired(taskType).filter((key) => {
    const value = parameters[key];
    return value === undefined || value === null || value === "";
  });
}

const SLASH_COMMAND_PATTERN = /^\/(\S+)\s*(.*)$/s;

const NATURAL_LANGUAGE_RULES: Array<{ taskType: TaskType; keywords: string[] }> = [
  { taskType: "WEATHER_LOOKUP", keywords: ["날씨", "기온", "weather"] },
  { taskType: "FILE_SEARCH", keywords: ["파일 찾", "파일 검색", "파일 좀"] },
  { taskType: "SYSTEM_STATUS", keywords: ["시스템 상태", "cpu", "메모리 사용량", "pc 상태"] },
  { taskType: "TEXT_SUMMARY", keywords: ["요약해", "요약해줘", "summarize"] },
];

function analyzeSlashCommand(command: string, rest: string): NluResult {
  const taskType = SLASH_COMMAND_TASK_TYPE[command.toLowerCase()];
  if (!taskType) {
    return { taskType: null, parameters: {}, confidence: 0, missingRequiredParameters: [] };
  }

  const parameters: Record<string, unknown> = {};
  const trimmedRest = rest.trim();

  switch (taskType) {
    case "WEATHER_LOOKUP":
      if (trimmedRest) parameters.location = trimmedRest;
      break;
    case "FILE_SEARCH":
      if (trimmedRest) parameters.query = trimmedRest;
      break;
    case "TEXT_SUMMARY":
      if (trimmedRest) parameters.text = trimmedRest;
      break;
    case "SYSTEM_STATUS":
      if (trimmedRest === "--explain") parameters.explain = true;
      break;
    case "CODE_ANALYSIS":
      if (trimmedRest) parameters.workspaceHint = trimmedRest;
      break;
  }

  return {
    taskType,
    parameters,
    confidence: 1.0,
    missingRequiredParameters: missingFrom(taskType, parameters),
  };
}

function analyzeNaturalLanguage(text: string): NluResult {
  const lower = text.toLowerCase();
  for (const rule of NATURAL_LANGUAGE_RULES) {
    if (rule.keywords.some((keyword) => lower.includes(keyword.toLowerCase()))) {
      const parameters: Record<string, unknown> = {};
      if (rule.taskType === "WEATHER_LOOKUP") {
        const match = text.match(/^(.*?)(?:\s*의)?\s*날씨/);
        const location = match?.[1]?.trim();
        if (location) parameters.location = location;
      }
      if (rule.taskType === "TEXT_SUMMARY") {
        parameters.text = text;
      }
      return {
        taskType: rule.taskType,
        parameters,
        confidence: 0.6,
        missingRequiredParameters: missingFrom(rule.taskType, parameters),
      };
    }
  }
  return { taskType: null, parameters: {}, confidence: 0, missingRequiredParameters: [] };
}

export function analyze(rawText: string): NluResult {
  const text = rawText.trim();
  const slashMatch = text.match(SLASH_COMMAND_PATTERN);
  if (slashMatch) {
    const [, command, rest] = slashMatch;
    return analyzeSlashCommand(command, rest);
  }
  return analyzeNaturalLanguage(text);
}
