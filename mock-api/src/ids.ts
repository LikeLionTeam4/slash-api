import { randomUUID, randomBytes } from "node:crypto";
import { nowIsoKst } from "@slash-api-mock/contracts";

export function uuid(): string {
  return randomUUID();
}

export function randomToken(byteLength = 24): string {
  return randomBytes(byteLength).toString("base64url");
}

export function randomPairingCode(): string {
  return String(Math.floor(100000 + Math.random() * 900000));
}

/** 메시지 프로토콜 문서 §3.5: API·WSS로 나가는 타임스탬프는 KST(+09:00)로 표기한다. */
export function nowIso(): string {
  return nowIsoKst();
}
