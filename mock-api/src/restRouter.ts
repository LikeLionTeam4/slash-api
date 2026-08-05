import { Router } from "express";
import { createHash } from "node:crypto";
import {
  createTaskRequestSchema,
  devicePatchRequestSchema,
  taskTypeInfoSchema,
  TASK_TYPES,
  TASK_TYPE_ROUTE,
  TASK_TYPE_REQUIRED_PARAMETERS,
  TASK_TYPE_TIER,
  SLASH_COMMAND_TASK_TYPE,
  IDEMPOTENCY_KEY_HEADER,
  CORRELATION_ID_HEADER,
  toIsoKst,
  type TaskType,
  type TaskEvent,
} from "@slash-api-mock/contracts";
import { store, TaskRecord, TaskEventRecord } from "./store.js";
import { requireAuth, AuthedRequest, issueTestLogin } from "./auth.js";
import { createTask } from "./taskOrchestrator.js";
import { nowIso, uuid, randomToken } from "./ids.js";
import { ok, okList, fail } from "./envelope.js";

export const restRouter = Router();
export const testRouter = Router();

const WS_TICKET_TTL_MS = 30_000;

function toPublicTask(task: TaskRecord) {
  return {
    taskId: task.taskId,
    userId: task.userId,
    deviceId: task.deviceId,
    inputText: task.inputText,
    taskType: task.taskType,
    processingRoute: task.processingRoute,
    status: task.status,
    parameters: task.parameters,
    missingRequiredParameters: task.missingRequiredParameters,
    result: task.result,
    errorCode: task.errorCode,
    correlationId: task.correlationId,
    createdAt: task.createdAt,
    updatedAt: task.updatedAt,
  };
}

/**
 * 내부 이벤트(source/eventType/sequence, 컴포넌트별 디버그 로그)를 공개 계약(메시지 프로토콜
 * 문서 §4.6: eventId/fromStatus/toStatus/reasonCode/message)으로 변환한다. source/eventType는
 * 공개 스키마에 없는 필드라 message 문자열 접두사로 접어 넣어 "통합 흐름" 타임라인 정보를 보존한다.
 */
function toPublicTaskEvent(event: TaskEventRecord, reasonCode: string | null): TaskEvent {
  return {
    eventId: event.id,
    taskId: event.taskId,
    fromStatus: event.fromStatus,
    toStatus: event.toStatus,
    reasonCode,
    message: `[${event.source}] ${event.eventType}${event.detail ? `: ${event.detail}` : ""}`,
    occurredAt: event.occurredAt,
  };
}

function bodyHash(body: unknown): string {
  return createHash("sha256").update(JSON.stringify(body ?? {})).digest("hex");
}

// ---- 시험 전용 엔드포인트 (지시문 7절, 이 문서 범위 밖이라 봉투 미적용) ----

testRouter.post("/reset", (_req, res) => {
  store.reset();
  res.json({ ok: true });
});

testRouter.post("/login", (req, res) => {
  const email = typeof req.body?.email === "string" ? req.body.email : "tester@example.com";
  const displayName = typeof req.body?.displayName === "string" ? req.body.displayName : "시험 사용자";
  const { user, token } = issueTestLogin(email, displayName);
  res.json({ userId: user.userId, email: user.email, displayName: user.displayName, token });
});

/**
 * 정상 Ed25519 페어링(POST /agent/pair, /agent/pair/verify)을 우회해 알려진 deviceToken(+선택적
 * publicKeyBase64)으로 기기 레코드만 만들어주는 시험 전용 엔드포인트다. 두 가지 용도로 쓴다:
 * 1) legacy-agent(slash-agent/legacy-macos, 실 Ed25519 서명 없음) 연결 격차 시험(CONTRACT_GAPS.md)
 * 2) 통합 시험에서 ACK를 일부러 보내지 않는 "무응답 Agent"를 만들 때(실제 서명 검증은 그대로 통과시켜
 *    READY까지 도달하게 하고, TASK ACK 타임아웃·재전송 동작만 분리해서 시험하기 위해)
 */
testRouter.post("/register-legacy-device", requireAuth, (req: AuthedRequest, res) => {
  const deviceToken = typeof req.body?.deviceToken === "string" ? req.body.deviceToken : null;
  if (!deviceToken) {
    res.status(400).json({ error: "deviceToken이 필요합니다" });
    return;
  }
  const publicKeyBase64 = typeof req.body?.publicKeyBase64 === "string" ? req.body.publicKeyBase64 : "";
  const supportedTaskTypes = Array.isArray(req.body?.supportedTaskTypes) ? req.body.supportedTaskTypes : [];
  const deviceId = uuid();
  store.devices.set(deviceId, {
    deviceId,
    userId: req.user!.userId,
    name: "legacy-agent (slash-agent/legacy-macos)",
    os: "MACOS",
    architecture: "ARM64",
    osVersion: "unknown",
    agentVersion: "legacy-mock",
    status: "OFFLINE",
    publicKeyBase64,
    deviceToken,
    supportedTaskTypes,
    maxConcurrentTasks: 1,
    searchFolders: [],
    projectWorkspaces: [],
    lastSeenAt: null,
    connectionId: null,
    version: 1,
    usedRefreshNonces: new Set(),
  });
  res.status(201).json({ deviceId });
});

// ---- 표준 REST (메시지 프로토콜 문서 §4 기준, {data,meta}/{error,meta} 봉투 적용) ----

restRouter.get("/me", requireAuth, (req: AuthedRequest, res) => {
  ok(res, 200, req.user);
});

restRouter.post("/ws-tickets", requireAuth, (req: AuthedRequest, res) => {
  const ticket = randomToken(24);
  const expiresAt = toIsoKst(new Date(Date.now() + WS_TICKET_TTL_MS));
  store.wsTickets.set(ticket, { ticket, userId: req.user!.userId, expiresAt, used: false });
  const port = process.env.MOCK_API_HTTP_PORT ?? "4000";
  ok(res, 201, { ticket, expiresIn: WS_TICKET_TTL_MS / 1000, wsUrl: `ws://localhost:${port}/ws/user` });
});

restRouter.get("/task-types", (_req, res) => {
  const list = TASK_TYPES.map((code) =>
    taskTypeInfoSchema.parse({
      code,
      slashCommand:
        "/" + (Object.entries(SLASH_COMMAND_TASK_TYPE).find(([, v]) => v === code)?.[0] ?? code.toLowerCase()),
      processingRoute: TASK_TYPE_ROUTE[code],
      requiredParameters: TASK_TYPE_REQUIRED_PARAMETERS[code],
      enabled: TASK_TYPE_TIER[code] === "P0",
    })
  );
  ok(res, 200, list);
});

restRouter.get("/llm-readiness", (_req, res) => {
  ok(res, 200, { state: "READY", acceptingJobs: true, model: "mock-llm-fixture-v1", checkedAt: nowIso() });
});

restRouter.get("/devices", requireAuth, (req: AuthedRequest, res) => {
  const devices = [...store.devices.values()].filter((d) => d.userId === req.user!.userId);
  ok(
    res,
    200,
    devices.map((d) => ({
      deviceId: d.deviceId,
      name: d.name,
      os: d.os,
      status: d.status,
      supportedTaskTypes: d.supportedTaskTypes,
      lastSeenAt: d.lastSeenAt,
    }))
  );
});

restRouter.get("/devices/:id", requireAuth, (req, res) => {
  const device = store.devices.get(req.params.id);
  if (!device) {
    fail(res, 404, "RESOURCE_NOT_FOUND", "해당 PC를 찾을 수 없습니다.");
    return;
  }
  res.setHeader("ETag", `"${device.version}"`);
  ok(res, 200, {
    deviceId: device.deviceId,
    name: device.name,
    os: device.os,
    status: device.status,
    supportedTaskTypes: device.supportedTaskTypes,
    lastSeenAt: device.lastSeenAt,
    searchFolders: device.searchFolders,
    projectWorkspaces: device.projectWorkspaces,
  });
});

restRouter.patch("/devices/:id", requireAuth, (req, res) => {
  const device = store.devices.get(req.params.id);
  if (!device) {
    fail(res, 404, "RESOURCE_NOT_FOUND", "해당 PC를 찾을 수 없습니다.");
    return;
  }
  const parsed = devicePatchRequestSchema.safeParse(req.body);
  if (!parsed.success) {
    fail(res, 400, "VALIDATION_ERROR", "PC 이름 입력값을 확인해 주세요.", { issues: parsed.error.issues });
    return;
  }
  const ifMatch = req.headers["if-match"];
  if (typeof ifMatch !== "string" || ifMatch !== `"${device.version}"`) {
    fail(res, 412, "RESOURCE_VERSION_MISMATCH", "최신 PC 정보를 다시 조회한 뒤 다시 시도해 주세요.");
    return;
  }
  device.name = parsed.data.name;
  device.version += 1;
  res.setHeader("ETag", `"${device.version}"`);
  ok(res, 200, {
    deviceId: device.deviceId,
    name: device.name,
    os: device.os,
    status: device.status,
    supportedTaskTypes: device.supportedTaskTypes,
    lastSeenAt: device.lastSeenAt,
  });
});

restRouter.delete("/devices/:id", requireAuth, (req, res) => {
  const existed = store.devices.delete(req.params.id);
  if (!existed) {
    fail(res, 404, "RESOURCE_NOT_FOUND", "해당 PC를 찾을 수 없습니다.");
    return;
  }
  res.status(204).end();
});

restRouter.get("/devices/:id/task-availability", (req, res) => {
  const device = store.devices.get(req.params.id);
  const taskType = req.query.taskType as string | undefined;
  if (taskType && !TASK_TYPES.includes(taskType as TaskType)) {
    fail(res, 422, "TASK_TYPE_NOT_SUPPORTED", `지원하지 않는 기능입니다: ${taskType}`);
    return;
  }
  if (!device) {
    ok(res, 200, { available: false, reasonCode: "DEVICE_BUSY", supportedTaskTypes: [] });
    return;
  }
  const available =
    device.status === "READY" && (!taskType || device.supportedTaskTypes.includes(taskType as TaskType));
  ok(res, 200, {
    available,
    reasonCode: available ? null : device.status !== "READY" ? "DEVICE_BUSY" : "TASK_TYPE_NOT_SUPPORTED",
    supportedTaskTypes: device.supportedTaskTypes,
  });
});

// ---- Task 생성/조회 (지시문 6절: 브라우저는 오직 이 엔드포인트로만 작업을 접수한다) ----

restRouter.post("/requests", requireAuth, (req: AuthedRequest, res) => {
  const parsed = createTaskRequestSchema.safeParse(req.body);
  if (!parsed.success) {
    fail(res, 400, "VALIDATION_ERROR", "요청 문장·시간대 형식을 확인해 주세요.", { issues: parsed.error.issues });
    return;
  }
  const idempotencyKey = (req.headers[IDEMPOTENCY_KEY_HEADER.toLowerCase()] as string | undefined) ?? null;
  const correlationIdHeader = (req.headers[CORRELATION_ID_HEADER.toLowerCase()] as string | undefined) ?? null;

  if (idempotencyKey) {
    const existingTaskId = store.idempotencyKeys.get(idempotencyKey);
    if (existingTaskId) {
      const existing = store.tasks.get(existingTaskId);
      if (existing && existing.idempotencyBodyHash !== bodyHash(req.body)) {
        fail(res, 409, "IDEMPOTENCY_CONFLICT", "이 Idempotency-Key로 이미 다른 요청을 처리했습니다.");
        return;
      }
    }
  }

  const task = createTask({
    userId: req.user!.userId,
    deviceId: parsed.data.selectedDeviceId ?? null,
    text: parsed.data.text,
    idempotencyKey,
    idempotencyBodyHash: idempotencyKey ? bodyHash(req.body) : null,
    correlationId: correlationIdHeader,
  });

  res.setHeader("Location", `/api/v1/tasks/${task.taskId}`);
  ok(res, 202, {
    taskId: task.taskId,
    status: "ANALYZING",
    statusUrl: `/api/v1/tasks/${task.taskId}`,
  });
});

restRouter.get("/tasks/:taskId", requireAuth, (req, res) => {
  const task = store.tasks.get(req.params.taskId);
  if (!task) {
    fail(res, 404, "RESOURCE_NOT_FOUND", "해당 작업을 찾을 수 없습니다.");
    return;
  }
  ok(res, 200, toPublicTask(task));
});

/**
 * `size`는 두 목록 Endpoint가 공유하는 규칙(기본 20, 최대 100). 잘못된 값(정수가 아니거나 범위 밖)이면
 * 조용히 기본값으로 대체하지 않고 400 VALIDATION_ERROR로 막는다 — 프론트가 그 응답을 받으면 Cursor·크기
 * 값을 초기화하고 처음부터 다시 조회한다 (메시지 프로토콜 문서 §4.7).
 */
function parseSize(raw: unknown): number | null {
  if (raw === undefined) return 20;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 1 || n > 100) return null;
  return n;
}

restRouter.get("/tasks/:taskId/events", requireAuth, (req, res) => {
  if (!store.tasks.has(req.params.taskId)) {
    fail(res, 404, "RESOURCE_NOT_FOUND", "해당 작업을 찾을 수 없습니다.");
    return;
  }
  const size = parseSize(req.query.size);
  if (size === null) {
    fail(res, 400, "VALIDATION_ERROR", "size는 1~100 사이의 정수여야 합니다.");
    return;
  }
  let startSequence = 0;
  if (req.query.cursor !== undefined) {
    const cursor = Number(req.query.cursor);
    if (typeof req.query.cursor !== "string" || !Number.isInteger(cursor) || cursor < 0) {
      fail(res, 400, "VALIDATION_ERROR", "cursor 값을 확인해 주세요.");
      return;
    }
    startSequence = cursor;
  }

  const events = (store.taskEvents.get(req.params.taskId) ?? []).filter((e) => e.sequence >= startSequence);
  const page = events.slice(0, size);
  const nextCursor = events.length > size ? String(page[page.length - 1].sequence + 1) : null;

  const task = store.tasks.get(req.params.taskId)!;
  okList(
    res,
    200,
    page.map((e) => toPublicTaskEvent(e, e.toStatus === "FAILED" || e.toStatus === "EXPIRED" ? task.errorCode : null)),
    nextCursor
  );
});

restRouter.get("/history", requireAuth, (req: AuthedRequest, res) => {
  const size = parseSize(req.query.size);
  if (size === null) {
    fail(res, 400, "VALIDATION_ERROR", "size는 1~100 사이의 정수여야 합니다.");
    return;
  }

  const all = [...store.tasks.values()]
    .filter((t) => t.userId === req.user!.userId)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.taskId.localeCompare(a.taskId));

  let startIndex = 0;
  if (req.query.cursor !== undefined) {
    if (typeof req.query.cursor !== "string") {
      fail(res, 400, "VALIDATION_ERROR", "cursor 값을 확인해 주세요.");
      return;
    }
    const foundIndex = all.findIndex((t) => t.taskId === req.query.cursor);
    if (foundIndex < 0) {
      fail(res, 400, "VALIDATION_ERROR", "cursor 값을 확인해 주세요.");
      return;
    }
    startIndex = foundIndex + 1;
  }
  const page = all.slice(startIndex, startIndex + size);
  const nextCursor = startIndex + size < all.length ? page[page.length - 1]?.taskId ?? null : null;

  okList(
    res,
    200,
    page.map((t) => ({
      taskId: t.taskId,
      taskType: t.taskType,
      status: t.status,
      requestSummary: t.inputText.slice(0, 80),
      createdAt: t.createdAt,
    })),
    nextCursor
  );
});
