import { Router } from "express";
import {
  agentPairRequestSchema,
  agentPairVerifyRequestSchema,
  agentSessionRefreshRequestSchema,
  buildRefreshSigningPayload,
  toIsoKst,
} from "@slash-api-mock/contracts";
import { store, DeviceRecord, PairingSessionRecord } from "./store.js";
import { uuid, randomPairingCode, randomToken, nowIso } from "./ids.js";
import { verifyChallengeSignature, verifyEd25519Signature } from "./ed25519.js";
import { requireAuth, AuthedRequest } from "./auth.js";
import { ok, fail } from "./envelope.js";

const PAIRING_CODE_TTL_MS = 5 * 60_000;
const CHALLENGE_TTL_MS = 30_000;
const DEVICE_TOKEN_EXPIRES_IN_SECONDS = 86_400;
/** requestedAt이 이 범위를 벗어나면 재전송(replay) 공격으로 간주한다 (메시지 프로토콜 문서 §8.1 3단계). */
const REFRESH_REQUESTED_AT_SKEW_MS = 120_000;

export const pairingRouter = Router();

// 사용자 화면에서 "PC 기기 등록" 시 코드 발급 (메시지 프로토콜 문서 §4.1 POST /pairing-requests)
pairingRouter.post("/pairing-requests", requireAuth, (req: AuthedRequest, res) => {
  const pairingRequestId = uuid();
  const pairingCode = randomPairingCode();
  const expiresAt = toIsoKst(new Date(Date.now() + PAIRING_CODE_TTL_MS));

  store.pairingRequests.set(pairingRequestId, {
    pairingRequestId,
    userId: req.user!.userId,
    pairingCode,
    expiresAt,
    deviceId: null,
  });

  ok(res, 201, { pairingRequestId, pairingCode, expiresAt });
});

pairingRouter.get("/pairing-requests/:id", requireAuth, (req, res) => {
  const record = store.pairingRequests.get(req.params.id);
  if (!record) {
    fail(res, 404, "RESOURCE_NOT_FOUND", "만료되었거나 잘못된 등록 요청입니다.");
    return;
  }
  ok(res, 200, { status: record.deviceId ? "CLAIMED" : "PENDING", deviceId: record.deviceId });
});

// Agent가 등록 코드로 페어링을 시작 (메시지 프로토콜 문서 §8.1 1단계)
pairingRouter.post("/agent/pair", (req, res) => {
  const parsed = agentPairRequestSchema.safeParse(req.body);
  if (!parsed.success) {
    fail(res, 400, "VALIDATION_ERROR", "OS·아키텍처·버전·공개키 입력값을 확인해 주세요.", {
      issues: parsed.error.issues,
    });
    return;
  }
  const body = parsed.data;

  const pairingRequest = [...store.pairingRequests.values()].find(
    (p) => p.pairingCode === body.pairingCode && !p.deviceId
  );
  if (!pairingRequest || new Date(pairingRequest.expiresAt).getTime() < Date.now()) {
    fail(res, 422, "PAIRING_CODE_INVALID", "사용자에게 새 등록 코드를 요청하세요.");
    return;
  }

  const deviceId = uuid();
  const device: DeviceRecord = {
    deviceId,
    userId: pairingRequest.userId,
    name: body.device.name,
    os: body.device.os,
    architecture: body.device.architecture,
    osVersion: body.device.osVersion,
    agentVersion: body.device.agentVersion,
    status: "OFFLINE",
    publicKeyBase64: body.publicKey,
    deviceToken: null,
    supportedTaskTypes: body.supportedTaskTypes,
    maxConcurrentTasks: 1,
    searchFolders: [],
    projectWorkspaces: [],
    lastSeenAt: null,
    connectionId: null,
    version: 1,
    usedRefreshNonces: new Set(),
  };
  store.devices.set(deviceId, device);

  const pairingSessionId = uuid();
  const challengeId = uuid();
  const nonce = Buffer.from(uuid()).toString("base64");
  const expiresAt = toIsoKst(new Date(Date.now() + CHALLENGE_TTL_MS));

  const session: PairingSessionRecord = {
    pairingSessionId,
    userId: pairingRequest.userId,
    pairingCode: body.pairingCode,
    deviceId,
    challengeId,
    nonce,
    publicKeyBase64: body.publicKey,
    expiresAt,
    verified: false,
  };
  store.pairingSessions.set(pairingSessionId, session);

  ok(res, 201, { pairingSessionId, deviceId, challengeId, nonce, expiresAt });
});

// Agent가 Ed25519 서명으로 소유 증명 (메시지 프로토콜 문서 §8.1 2단계)
pairingRouter.post("/agent/pair/verify", (req, res) => {
  const parsed = agentPairVerifyRequestSchema.safeParse(req.body);
  if (!parsed.success) {
    fail(res, 400, "VALIDATION_ERROR", "요청 본문을 확인해 주세요.", { issues: parsed.error.issues });
    return;
  }
  const body = parsed.data;
  const session = store.pairingSessions.get(body.pairingSessionId);
  if (!session || session.challengeId !== body.challengeId) {
    fail(res, 404, "RESOURCE_NOT_FOUND", "등록 세션을 찾을 수 없습니다.");
    return;
  }
  if (new Date(session.expiresAt).getTime() < Date.now()) {
    fail(res, 409, "AGENT_AUTH_FAILED", "인증 요청이 만료되었습니다. 등록을 다시 시작하세요.");
    return;
  }

  const valid = verifyChallengeSignature({
    challengeId: session.challengeId,
    nonce: session.nonce,
    deviceId: session.deviceId,
    signatureBase64: body.signature,
    publicKeyBase64: session.publicKeyBase64,
  });
  if (!valid) {
    fail(res, 422, "AGENT_AUTH_FAILED", "서명 검증에 실패했습니다.");
    return;
  }

  const device = store.devices.get(session.deviceId);
  if (!device) {
    fail(res, 404, "RESOURCE_NOT_FOUND", "등록 대상 기기를 찾을 수 없습니다.");
    return;
  }

  const deviceToken = randomToken(32);
  device.deviceToken = deviceToken;
  session.verified = true;

  const pairingRequest = [...store.pairingRequests.values()].find((p) => p.pairingCode === session.pairingCode);
  if (pairingRequest) pairingRequest.deviceId = device.deviceId;

  const port = process.env.MOCK_API_HTTP_PORT ?? "4000";
  ok(res, 200, {
    deviceToken,
    expiresIn: DEVICE_TOKEN_EXPIRES_IN_SECONDS,
    issuedAt: nowIso(),
    wsUrl: `ws://localhost:${port}/ws/agent`,
  });
});

/**
 * 기존 deviceToken을 그대로 제시하는 방식(bearer 신뢰)이 아니라, 매번 새 refreshNonce에 대한
 * Ed25519 서명으로 개인키 보유를 재증명해야 한다 (메시지 프로토콜 문서 §8.1 3단계). 서명 대상은
 * `deviceId:refreshNonce:requestedAt`. requestedAt 스큐와 refreshNonce 재사용을 모두 거부한다.
 */
pairingRouter.post("/agent/sessions/refresh", (req, res) => {
  const parsed = agentSessionRefreshRequestSchema.safeParse(req.body);
  if (!parsed.success) {
    fail(res, 400, "VALIDATION_ERROR", "요청 본문을 확인해 주세요.", { issues: parsed.error.issues });
    return;
  }
  const body = parsed.data;

  const device = store.devices.get(body.deviceId);
  if (!device || !device.deviceToken) {
    fail(res, 401, "AUTH_REQUIRED", "등록되지 않은 기기입니다. 다시 등록해 주세요.");
    return;
  }
  if (device.status === "REVOKED") {
    fail(res, 409, "FORBIDDEN", "등록이 해지된 기기입니다.");
    return;
  }

  const skewMs = Math.abs(Date.now() - new Date(body.requestedAt).getTime());
  if (!Number.isFinite(skewMs) || skewMs > REFRESH_REQUESTED_AT_SKEW_MS) {
    fail(res, 403, "AGENT_AUTH_FAILED", "requestedAt이 허용 범위를 벗어났습니다.");
    return;
  }
  if (device.usedRefreshNonces.has(body.refreshNonce)) {
    fail(res, 403, "AGENT_AUTH_FAILED", "이미 사용된 refreshNonce입니다.");
    return;
  }

  const valid = verifyEd25519Signature({
    payload: buildRefreshSigningPayload(body),
    signatureBase64: body.signature,
    publicKeyBase64: device.publicKeyBase64,
  });
  if (!valid) {
    fail(res, 403, "AGENT_AUTH_FAILED", "서명·기기 ID를 확인해 주세요.");
    return;
  }

  device.usedRefreshNonces.add(body.refreshNonce);
  const deviceToken = randomToken(32);
  device.deviceToken = deviceToken;
  ok(res, 200, { deviceToken, expiresIn: DEVICE_TOKEN_EXPIRES_IN_SECONDS, issuedAt: nowIso() });
});
