import { WebSocketServer, WebSocket } from "ws";
import type { IncomingMessage } from "node:http";
import type { Duplex } from "node:stream";
import {
  AgentMessage,
  agentMessageSchema,
  AGENT_SCHEMA_VERSION,
  ChallengeMessage,
  ProtocolErrorMessage,
  ProtocolErrorCode,
  ResultAckMessage,
  toIsoKst,
} from "@slash-api-mock/contracts";
import { store, DeviceRecord } from "./store.js";
import { uuid, nowIso } from "./ids.js";
import { verifyChallengeSignature } from "./ed25519.js";
import { broadcastToUser } from "./userWss.js";
import { onAgentAck, onAgentResult, onAgentProgress } from "./taskOrchestrator.js";

const CHALLENGE_TTL_MS = 30_000;
const HEARTBEAT_TIMEOUT_MS = Number(process.env.MOCK_API_HEARTBEAT_TIMEOUT_MS ?? 90_000);

type ConnectionPhase = "CONNECTED" | "CHALLENGED" | "AUTHENTICATED" | "READY";

interface ConnectionState {
  phase: ConnectionPhase;
  deviceId: string | null;
  challengeId: string | null;
  nonce: string | null;
  heartbeatTimer: ReturnType<typeof setTimeout> | null;
}

const connectionState = new WeakMap<WebSocket, ConnectionState>();
const socketsByDevice = new Map<string, WebSocket>();

export function getAgentSocket(deviceId: string): WebSocket | undefined {
  return socketsByDevice.get(deviceId);
}

function send(socket: WebSocket, message: Omit<AgentMessage, "schemaVersion" | "eventId" | "sentAt">): void {
  const full = {
    schemaVersion: AGENT_SCHEMA_VERSION,
    eventId: uuid(),
    sentAt: nowIso(),
    ...message,
  } as AgentMessage;
  socket.send(JSON.stringify(full));
}

function sendProtocolError(
  socket: WebSocket,
  code: ProtocolErrorCode,
  message: string,
  opts: { relatedEventId?: string | null; closeConnection?: boolean } = {}
): void {
  console.log(`[mock-api][agent-wss] PROTOCOL_ERROR ${code}: ${message}`);
  const errorMessage: Omit<ProtocolErrorMessage, "schemaVersion" | "eventId" | "sentAt"> = {
    type: "PROTOCOL_ERROR",
    code,
    message,
    relatedEventId: opts.relatedEventId ?? null,
    closeConnection: opts.closeConnection ?? false,
  };
  send(socket, errorMessage);
  if (opts.closeConnection) socket.close(4400, code);
}

function markDeviceStatus(device: DeviceRecord, status: DeviceRecord["status"]): void {
  device.status = status;
  broadcastToUser(device.userId, {
    type: "DEVICE_STATUS_CHANGED",
    deviceId: device.deviceId,
    status,
    lastSeenAt: device.lastSeenAt,
  });
}

function resetHeartbeatTimer(socket: WebSocket, device: DeviceRecord): void {
  const state = connectionState.get(socket);
  if (!state) return;
  if (state.heartbeatTimer) clearTimeout(state.heartbeatTimer);
  state.heartbeatTimer = setTimeout(() => {
    markDeviceStatus(device, "OFFLINE");
  }, HEARTBEAT_TIMEOUT_MS);
}

export function createAgentWss(): WebSocketServer {
  const wss = new WebSocketServer({ noServer: true });

  wss.on("connection", (socket, request) => {
    const authHeader = request.headers["authorization"];
    const url = new URL(request.url ?? "", "http://localhost");
    const bearerToken =
      typeof authHeader === "string" && authHeader.startsWith("Bearer ")
        ? authHeader.slice("Bearer ".length)
        : url.searchParams.get("deviceToken");

    const device = bearerToken
      ? [...store.devices.values()].find((d) => d.deviceToken === bearerToken)
      : undefined;

    if (!device) {
      sendProtocolError(socket, "AUTHENTICATION_FAILED", "invalid or missing device token", {
        closeConnection: true,
      });
      return;
    }

    connectionState.set(socket, {
      phase: "CONNECTED",
      deviceId: device.deviceId,
      challengeId: null,
      nonce: null,
      heartbeatTimer: null,
    });

    socket.on("message", (raw) => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(raw.toString());
      } catch {
        sendProtocolError(socket, "INVALID_MESSAGE", "malformed JSON");
        return;
      }

      const result = agentMessageSchema.safeParse(parsed);
      if (!result.success) {
        const maybeEventId =
          typeof parsed === "object" && parsed !== null && "eventId" in parsed
            ? String((parsed as Record<string, unknown>).eventId)
            : null;
        sendProtocolError(socket, "INVALID_MESSAGE", result.error.issues[0]?.message ?? "invalid message", {
          relatedEventId: maybeEventId,
        });
        return;
      }

      handleAgentMessage(socket, device, result.data);
    });

    socket.on("close", () => {
      const state = connectionState.get(socket);
      if (state?.heartbeatTimer) clearTimeout(state.heartbeatTimer);
      if (socketsByDevice.get(device.deviceId) === socket) {
        socketsByDevice.delete(device.deviceId);
        markDeviceStatus(device, "OFFLINE");
      }
    });
  });

  return wss;
}

export function handleAgentUpgrade(
  wss: WebSocketServer,
  request: IncomingMessage,
  socket: Duplex,
  head: Buffer
): void {
  wss.handleUpgrade(request, socket, head, (ws) => {
    wss.emit("connection", ws, request);
  });
}

function handleAgentMessage(socket: WebSocket, device: DeviceRecord, message: AgentMessage): void {
  const state = connectionState.get(socket);
  if (!state) return;

  switch (message.type) {
    case "HELLO": {
      if (message.deviceId !== device.deviceId) {
        sendProtocolError(socket, "AUTHENTICATION_FAILED", "deviceId mismatch", { closeConnection: true });
        return;
      }
      const challengeId = uuid();
      const nonce = Buffer.from(uuid()).toString("base64");
      state.phase = "CHALLENGED";
      state.challengeId = challengeId;
      state.nonce = nonce;
      const challenge: Omit<ChallengeMessage, "schemaVersion" | "eventId" | "sentAt"> = {
        type: "CHALLENGE",
        challengeId,
        nonce,
        expiresAt: toIsoKst(new Date(Date.now() + CHALLENGE_TTL_MS)),
      };
      send(socket, challenge);
      return;
    }

    case "AUTH": {
      if (state.phase !== "CHALLENGED" || message.challengeId !== state.challengeId || !state.nonce) {
        sendProtocolError(socket, "CHALLENGE_EXPIRED", "no matching challenge", { closeConnection: true });
        return;
      }
      const valid = verifyChallengeSignature({
        challengeId: message.challengeId,
        nonce: state.nonce,
        deviceId: device.deviceId,
        signatureBase64: message.signature,
        publicKeyBase64: device.publicKeyBase64,
      });
      if (!valid) {
        sendProtocolError(socket, "AUTHENTICATION_FAILED", "signature verification failed", {
          closeConnection: true,
        });
        return;
      }
      state.phase = "AUTHENTICATED";
      return;
    }

    case "READY": {
      if (state.phase !== "AUTHENTICATED") {
        sendProtocolError(socket, "INVALID_CONNECTION_STATE", "READY before AUTH", { closeConnection: true });
        return;
      }
      state.phase = "READY";
      device.maxConcurrentTasks = message.maxConcurrentTasks;
      device.supportedTaskTypes = message.supportedTaskTypes;
      device.searchFolders = message.searchFolders;
      device.projectWorkspaces = message.projectWorkspaces;
      device.lastSeenAt = nowIso();
      socketsByDevice.set(device.deviceId, socket);
      markDeviceStatus(device, "READY");
      resetHeartbeatTimer(socket, device);
      return;
    }

    case "HEARTBEAT": {
      device.lastSeenAt = nowIso();
      if (device.status === "OFFLINE") markDeviceStatus(device, "READY");
      resetHeartbeatTimer(socket, device);
      return;
    }

    case "ACK": {
      onAgentAck(message);
      return;
    }

    case "PROGRESS": {
      onAgentProgress(message);
      return;
    }

    case "RESULT": {
      onAgentResult(message, (resultAck: Omit<ResultAckMessage, "schemaVersion" | "eventId" | "sentAt">) => {
        send(socket, resultAck);
      });
      return;
    }

    case "PROTOCOL_ERROR": {
      // Agent가 우리 메시지를 거부한 경우: 시험 로그로만 남기고 연결은 유지한다.
      return;
    }

    default:
      return;
  }
}

export function dispatchTaskToDevice(
  deviceId: string,
  taskMessage: Omit<Extract<AgentMessage, { type: "TASK" }>, "schemaVersion" | "eventId" | "sentAt">
): boolean {
  const socket = socketsByDevice.get(deviceId);
  if (!socket || socket.readyState !== WebSocket.OPEN) return false;
  send(socket, taskMessage);
  return true;
}
