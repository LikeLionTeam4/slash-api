import {
  TaskType,
  ProcessingRoute,
  TaskStatus,
  DeviceStatus,
  DeviceOs,
  AsyncJobStatus,
  AgentDispatchStatus,
  SearchFolder,
  ProjectWorkspace,
} from "@slash-api-mock/contracts";
import { uuid, nowIso } from "./ids.js";

/**
 * 내부 이벤트 기록 — "통합 흐름" 디버그 타임라인(TaskResultPanel)이 쓰는 컴포넌트별 상세 로그다.
 * 공개 REST 응답(`GET /tasks/{id}/events`)은 이 레코드를 메시지 프로토콜 문서 §4.6 형태로 변환해
 * 내보낸다 — source/eventType/sequence는 공개 계약에 없는 필드라 message 문자열로 접어 넣는다
 * (restRouter.ts의 toPublicTaskEvent 참고).
 */
export interface TaskEventRecord {
  id: string;
  taskId: string;
  sequence: number;
  source: "slash-web" | "slash-api" | "slash-nlu" | "slash-llm" | "contract-agent" | "legacy-agent";
  eventType: string;
  fromStatus: TaskStatus | null;
  toStatus: TaskStatus | null;
  detail: string | null;
  occurredAt: string;
}

export interface UserRecord {
  userId: string;
  email: string;
  displayName: string;
}

export interface SessionRecord {
  token: string;
  userId: string;
}

export interface DeviceRecord {
  deviceId: string;
  userId: string;
  name: string;
  os: DeviceOs;
  architecture: string;
  osVersion: string;
  agentVersion: string;
  status: DeviceStatus;
  publicKeyBase64: string;
  deviceToken: string | null;
  supportedTaskTypes: TaskType[];
  maxConcurrentTasks: number;
  searchFolders: SearchFolder[];
  projectWorkspaces: ProjectWorkspace[];
  lastSeenAt: string | null;
  connectionId: string | null;
  /** PATCH If-Match 낙관적 동시성 버전 — GET/PATCH 응답의 ETag로 그대로 노출된다. */
  version: number;
  /** POST /agent/sessions/refresh replay 방지 (메시지 프로토콜 문서 §8.1 3단계 refreshNonce). */
  usedRefreshNonces: Set<string>;
}

export interface WsTicketRecord {
  ticket: string;
  userId: string;
  expiresAt: string;
  used: boolean;
}

export interface PairingRequestRecord {
  pairingRequestId: string;
  userId: string;
  pairingCode: string;
  expiresAt: string;
  deviceId: string | null;
}

export interface PairingSessionRecord {
  pairingSessionId: string;
  userId: string;
  pairingCode: string;
  deviceId: string;
  challengeId: string;
  nonce: string;
  publicKeyBase64: string;
  expiresAt: string;
  verified: boolean;
}

export interface TaskRecord {
  taskId: string;
  userId: string;
  deviceId: string | null;
  inputText: string;
  taskType: TaskType | null;
  processingRoute: ProcessingRoute | null;
  status: TaskStatus;
  parameters: Record<string, unknown>;
  missingRequiredParameters: string[];
  result: Record<string, unknown> | null;
  errorCode: string | null;
  correlationId: string;
  idempotencyKey: string | null;
  /** 같은 Idempotency-Key로 다른 본문이 재요청되면 409 IDEMPOTENCY_CONFLICT를 내기 위한 원본 본문 해시. */
  idempotencyBodyHash: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AgentDispatchRecord {
  dispatchId: string;
  taskId: string;
  deviceId: string;
  status: AgentDispatchStatus;
  attemptCount: number;
  expiresAt: string;
  dispatchedAt: string | null;
  acknowledgedAt: string | null;
  completedAt: string | null;
  ackTimer: ReturnType<typeof setTimeout> | null;
}

export interface AsyncJobRecord {
  jobId: string;
  taskId: string;
  jobType: "TEXT_SUMMARY";
  status: AsyncJobStatus;
  input: Record<string, unknown>;
  result: Record<string, unknown> | null;
  attemptCount: number;
  deadlineAt: string;
}

class InMemoryStore {
  users = new Map<string, UserRecord>();
  sessions = new Map<string, SessionRecord>();
  devices = new Map<string, DeviceRecord>();
  pairingRequests = new Map<string, PairingRequestRecord>();
  pairingSessions = new Map<string, PairingSessionRecord>();
  tasks = new Map<string, TaskRecord>();
  taskEvents = new Map<string, TaskEventRecord[]>();
  agentDispatches = new Map<string, AgentDispatchRecord>();
  asyncJobs = new Map<string, AsyncJobRecord>();
  idempotencyKeys = new Map<string, string>(); // idempotencyKey -> taskId
  wsTickets = new Map<string, WsTicketRecord>();

  reset(): void {
    this.users.clear();
    this.sessions.clear();
    this.devices.clear();
    this.pairingRequests.clear();
    this.pairingSessions.clear();
    this.tasks.clear();
    this.taskEvents.clear();
    for (const dispatch of this.agentDispatches.values()) {
      if (dispatch.ackTimer) clearTimeout(dispatch.ackTimer);
    }
    this.agentDispatches.clear();
    this.asyncJobs.clear();
    this.idempotencyKeys.clear();
    this.wsTickets.clear();
  }

  appendTaskEvent(
    taskId: string,
    fields: Omit<TaskEventRecord, "id" | "taskId" | "sequence" | "occurredAt"> & { occurredAt?: string }
  ): TaskEventRecord {
    const list = this.taskEvents.get(taskId) ?? [];
    const event: TaskEventRecord = {
      id: uuid(),
      taskId,
      sequence: list.length,
      occurredAt: fields.occurredAt ?? nowIso(),
      source: fields.source,
      eventType: fields.eventType,
      fromStatus: fields.fromStatus,
      toStatus: fields.toStatus,
      detail: fields.detail,
    };
    list.push(event);
    this.taskEvents.set(taskId, list);
    return event;
  }
}

export const store = new InMemoryStore();
