import {
  TaskType,
  TaskStatus,
  TASK_TYPE_ROUTE,
  TASK_TYPE_REQUIRED_PARAMETERS,
  TASK_TYPE_TIER,
  AckMessage,
  ProgressMessage,
  ResultMessage,
  ResultAckMessage,
  toIsoKst,
} from "@slash-api-mock/contracts";
import { createHash } from "node:crypto";
import { analyze } from "@slash-api-mock/mock-nlu";
import { runSummaryJob } from "@slash-api-mock/mock-llm";
import { store, TaskRecord, TaskEventRecord, AgentDispatchRecord, AsyncJobRecord } from "./store.js";
import { uuid, nowIso } from "./ids.js";
import { broadcastToUser } from "./userWss.js";
import { lookupWeather } from "./weatherService.js";
import { dispatchTaskToDevice } from "./agentWss.js";

const ACK_TIMEOUT_MS = Number(process.env.MOCK_API_ACK_TIMEOUT_MS ?? 5000);
const DISPATCH_TTL_MS = 60_000;
const MAX_TASK_SEND_ATTEMPTS = 2;

/** CODE_ANALYSIS는 지시문상 P1 기본 비활성 — 명시적으로 켜야 시험할 수 있다. */
function isTaskTypeEnabled(taskType: TaskType): boolean {
  if (TASK_TYPE_TIER[taskType] === "P0") return true;
  return process.env.ENABLE_P1_CODE_ANALYSIS === "true";
}

function transition(
  task: TaskRecord,
  to: TaskStatus,
  source: TaskEventRecord["source"],
  eventType: string,
  detail?: string
): void {
  const from = task.status;
  task.status = to;
  task.updatedAt = nowIso();
  store.appendTaskEvent(task.taskId, {
    source,
    eventType,
    fromStatus: from,
    toStatus: to,
    detail: detail ?? null,
  });
  broadcastToUser(task.userId, {
    type: "TASK_STATUS_CHANGED",
    taskId: task.taskId,
    from,
    to,
    occurredAt: nowIso(),
  });
}

function logOnly(task: TaskRecord, source: TaskEventRecord["source"], eventType: string, detail?: string): void {
  store.appendTaskEvent(task.taskId, {
    source,
    eventType,
    fromStatus: task.status,
    toStatus: task.status,
    detail: detail ?? null,
  });
}

function notifyResultAvailable(task: TaskRecord): void {
  broadcastToUser(task.userId, {
    type: "TASK_RESULT_AVAILABLE",
    taskId: task.taskId,
    status: task.status,
    resultPreview: task.result ? JSON.stringify(task.result).slice(0, 200) : null,
  });
}

export interface CreateTaskInput {
  userId: string;
  deviceId: string | null;
  text: string;
  idempotencyKey: string | null;
  /** 같은 Idempotency-Key 재요청 시 본문이 달라졌는지 판정하는 해시. 충돌 시 restRouter가 이미 409로 막는다. */
  idempotencyBodyHash: string | null;
  /** X-Correlation-Id 헤더 값 — 지정되면 그대로 쓰고, 없으면 서버가 생성한다 (메시지 프로토콜 문서 §3.2). */
  correlationId: string | null;
}

export function createTask(input: CreateTaskInput): TaskRecord {
  if (input.idempotencyKey) {
    const existingTaskId = store.idempotencyKeys.get(input.idempotencyKey);
    if (existingTaskId) {
      const existing = store.tasks.get(existingTaskId);
      if (existing) return existing;
    }
  }

  const taskId = uuid();
  const task: TaskRecord = {
    taskId,
    userId: input.userId,
    deviceId: input.deviceId,
    inputText: input.text,
    taskType: null,
    processingRoute: null,
    status: "CREATED",
    parameters: {},
    missingRequiredParameters: [],
    result: null,
    errorCode: null,
    correlationId: input.correlationId ?? uuid(),
    idempotencyKey: input.idempotencyKey,
    idempotencyBodyHash: input.idempotencyBodyHash,
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
  store.tasks.set(taskId, task);
  if (input.idempotencyKey) store.idempotencyKeys.set(input.idempotencyKey, taskId);

  logOnly(task, "slash-web", "REQUEST_CREATED", input.text);
  transition(task, "ANALYZING", "slash-api", "ANALYZING");

  runPipeline(task).catch((error) => {
    task.status = "FAILED";
    task.errorCode = "INTERNAL_ERROR";
    logOnly(task, "slash-api", "PIPELINE_ERROR", String(error));
  });

  return task;
}

async function runPipeline(task: TaskRecord): Promise<void> {
  const nlu = analyze(task.inputText);
  logOnly(
    task,
    "slash-nlu",
    "NLU_RESULT",
    `${nlu.taskType ?? "UNRECOGNIZED"} / confidence=${nlu.confidence.toFixed(2)}`
  );

  if (!nlu.taskType) {
    task.errorCode = "UNRECOGNIZED_COMMAND";
    transition(task, "FAILED", "slash-api", "UNRECOGNIZED_COMMAND", task.inputText);
    return;
  }

  task.taskType = nlu.taskType;
  task.parameters = { ...nlu.parameters };

  fillStructuralParameters(task);

  const missing = TASK_TYPE_REQUIRED_PARAMETERS[nlu.taskType].filter((key) => {
    const value = task.parameters[key];
    return value === undefined || value === null || value === "";
  });
  task.missingRequiredParameters = missing;

  if (missing.length > 0) {
    transition(task, "NEEDS_CLARIFICATION", "slash-api", "NEEDS_CLARIFICATION", missing.join(","));
    return;
  }

  if (!isTaskTypeEnabled(nlu.taskType)) {
    task.errorCode = "TASK_TYPE_DISABLED";
    transition(task, "FAILED", "slash-api", "TASK_TYPE_DISABLED", nlu.taskType);
    return;
  }

  task.processingRoute = TASK_TYPE_ROUTE[nlu.taskType];
  logOnly(task, "slash-api", "PROCESSING_ROUTE_DECIDED", `ProcessingRoute=${task.processingRoute}`);

  if (task.processingRoute === "BACKEND_SERVICE") {
    await runBackendService(task);
  } else if (task.processingRoute === "LLM_SERVICE") {
    await runLlmService(task);
  } else {
    runLocalAgent(task);
  }
}

function fillStructuralParameters(task: TaskRecord): void {
  if (!task.deviceId) return;
  const device = store.devices.get(task.deviceId);
  if (!device || device.status !== "READY") return;

  if (task.taskType === "FILE_SEARCH" && !task.parameters.searchFolderId) {
    const folder = device.searchFolders[0];
    if (folder) task.parameters.searchFolderId = folder.searchFolderId;
  }
  if (task.taskType === "CODE_ANALYSIS" && !task.parameters.workspaceId) {
    const workspace = device.projectWorkspaces.find((w) => w.availableCodeAdapters.length > 0);
    if (workspace) task.parameters.workspaceId = workspace.workspaceId;
  }
}

async function runBackendService(task: TaskRecord): Promise<void> {
  transition(task, "RUNNING", "slash-api", "RUNNING");
  const location = String(task.parameters.location ?? "");
  task.result = { ...lookupWeather(location) };
  transition(task, "SUCCEEDED", "slash-api", "RESULT_PERSISTED", "BACKEND_SERVICE result");
  logOnly(task, "slash-web", "SUCCEEDED");
  notifyResultAvailable(task);
}

async function runLlmService(task: TaskRecord): Promise<void> {
  const jobId = uuid();
  const asyncJob: AsyncJobRecord = {
    jobId,
    taskId: task.taskId,
    jobType: "TEXT_SUMMARY",
    status: "PENDING",
    input: { text: task.parameters.text },
    result: null,
    attemptCount: 1,
    deadlineAt: toIsoKst(new Date(Date.now() + 60_000)),
  };
  store.asyncJobs.set(jobId, asyncJob);

  const text = String(task.parameters.text ?? "");
  const jobResult = await runSummaryJob(text, {
    onStatus: (status) => {
      asyncJob.status = status;
      if (status === "QUEUED") transition(task, "QUEUED", "slash-llm", "QUEUED");
      if (status === "RUNNING") transition(task, "RUNNING", "slash-llm", "RUNNING");
    },
  });

  asyncJob.status = "SUCCEEDED";
  asyncJob.result = { ...jobResult };
  task.result = { ...jobResult };
  logOnly(task, "slash-llm", "RESULT", "SUCCEEDED");
  transition(task, "SUCCEEDED", "slash-api", "RESULT_PERSISTED", "LLM_SERVICE result");
  logOnly(task, "slash-web", "SUCCEEDED");
  notifyResultAvailable(task);
}

function runLocalAgent(task: TaskRecord): void {
  const device = task.deviceId ? store.devices.get(task.deviceId) : undefined;

  if (!device || device.status !== "READY" || !device.supportedTaskTypes.includes(task.taskType!)) {
    task.errorCode = "DEVICE_OFFLINE";
    transition(task, "FAILED", "slash-api", "DEVICE_OFFLINE", "선택한 PC가 READY 상태가 아닙니다. TASK를 전송하지 않았습니다.");
    logOnly(task, "slash-web", "FAILED");
    notifyResultAvailable(task);
    return;
  }

  transition(task, "QUEUED", "slash-api", "QUEUED");

  const dispatchId = uuid();
  const dispatch: AgentDispatchRecord = {
    dispatchId,
    taskId: task.taskId,
    deviceId: device.deviceId,
    status: "PENDING",
    attemptCount: 0,
    expiresAt: toIsoKst(new Date(Date.now() + DISPATCH_TTL_MS)),
    dispatchedAt: null,
    acknowledgedAt: null,
    completedAt: null,
    ackTimer: null,
  };
  store.agentDispatches.set(dispatchId, dispatch);

  sendTask(task, dispatch, device);
}

function sendTask(task: TaskRecord, dispatch: AgentDispatchRecord, device: { deviceId: string }): void {
  dispatch.attemptCount += 1;
  dispatch.dispatchedAt = nowIso();
  dispatch.status = "DISPATCHED";

  const sent = dispatchTaskToDevice(device.deviceId, {
    type: "TASK",
    taskId: task.taskId,
    dispatchId: dispatch.dispatchId,
    correlationId: task.correlationId,
    taskType: task.taskType!,
    parameters: task.parameters,
    expiresAt: dispatch.expiresAt,
    // 정규화 알고리즘이 아직 확정되지 않아(지시문 11절) JSON.stringify 기반 임시 해시만 사용한다.
    // 존재 + 64자리 hex 형식만 검사 대상이며 무결성 증명으로 취급하지 않는다.
    payloadSha256: createHash("sha256")
      .update(JSON.stringify({ taskId: task.taskId, dispatchId: dispatch.dispatchId, taskType: task.taskType, parameters: task.parameters }))
      .digest("hex"),
  });

  logOnly(task, "slash-api", "TASK_DISPATCHED", `attempt=${dispatch.attemptCount} sent=${sent}`);

  dispatch.ackTimer = setTimeout(() => {
    if (dispatch.status !== "DISPATCHED") return; // 이미 ACK 받음
    if (dispatch.attemptCount < MAX_TASK_SEND_ATTEMPTS) {
      logOnly(task, "slash-api", "ACK_TIMEOUT_RETRY", `attempt=${dispatch.attemptCount}`);
      sendTask(task, dispatch, device);
    } else {
      dispatch.status = "EXPIRED";
      task.errorCode = "ACK_TIMEOUT";
      transition(task, "EXPIRED", "slash-api", "ACK_TIMEOUT", "5초 내 ACK를 받지 못했습니다 (2회 시도)");
      logOnly(task, "slash-web", "EXPIRED");
      notifyResultAvailable(task);
    }
  }, ACK_TIMEOUT_MS);
}

function findDispatch(taskId: string, dispatchId: string): AgentDispatchRecord | undefined {
  const dispatch = store.agentDispatches.get(dispatchId);
  if (dispatch && dispatch.taskId === taskId) return dispatch;
  return undefined;
}

export function onAgentAck(message: AckMessage): void {
  const task = store.tasks.get(message.taskId);
  const dispatch = findDispatch(message.taskId, message.dispatchId);
  if (!task || !dispatch) return;
  if (dispatch.ackTimer) clearTimeout(dispatch.ackTimer);

  logOnly(task, "contract-agent", "TASK_RECEIVED", "TASK received");

  if (!message.accepted) {
    dispatch.status = "FAILED";
    task.errorCode = message.reasonCode ?? "AGENT_REJECTED";
    logOnly(task, "contract-agent", "ACK_REJECTED", message.reasonCode ?? undefined);
    transition(task, "FAILED", "slash-api", "AGENT_REJECTED", message.reasonCode ?? undefined);
    logOnly(task, "slash-web", "FAILED");
    notifyResultAvailable(task);
    return;
  }

  dispatch.status = "ACKNOWLEDGED";
  dispatch.acknowledgedAt = message.acknowledgedAt;
  logOnly(task, "contract-agent", "ACK_ACCEPTED", "ACK accepted");
  transition(task, "RUNNING", "slash-api", "RUNNING", "Agent가 작업을 수락했습니다");
}

export function onAgentProgress(message: ProgressMessage): void {
  const task = store.tasks.get(message.taskId);
  if (!task) return;
  logOnly(task, "contract-agent", "PROGRESS", `${message.stage}${message.percent !== undefined ? ` ${message.percent}%` : ""}`);
}

export function onAgentResult(
  message: ResultMessage,
  reply: (resultAck: Omit<ResultAckMessage, "schemaVersion" | "eventId" | "sentAt">) => void
): void {
  const task = store.tasks.get(message.taskId);
  const dispatch = findDispatch(message.taskId, message.dispatchId);
  if (!task || !dispatch) return;

  dispatch.status = message.status === "SUCCEEDED" ? "COMPLETED" : "FAILED";
  dispatch.completedAt = message.finishedAt;

  logOnly(task, "contract-agent", "RESULT", `RESULT ${message.status}`);

  if (message.status === "SUCCEEDED") {
    task.result = message.result;
    transition(task, "SUCCEEDED", "slash-api", "RESULT_PERSISTED", "RESULT persisted");
  } else {
    task.errorCode = message.error?.code ?? "AGENT_TASK_FAILED";
    task.result = null;
    transition(task, "FAILED", "slash-api", "RESULT_PERSISTED", message.error?.message ?? "RESULT persisted (실패)");
  }

  reply({
    type: "RESULT_ACK",
    taskId: task.taskId,
    dispatchId: dispatch.dispatchId,
    correlationId: task.correlationId,
    persisted: true,
    taskStatus: task.status,
  });
  logOnly(task, "slash-api", "RESULT_ACK_SENT", "RESULT_ACK sent");
  logOnly(task, "slash-web", task.status);
  notifyResultAvailable(task);
}
