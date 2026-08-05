import type { Response } from "express";
import { uuid, nowIso } from "./ids.js";

/** 모든 REST 응답을 {data,meta}/{error,meta} 봉투로 감싼다 (메시지 프로토콜 문서 §3.3). */

function meta(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { requestId: uuid(), serverTime: nowIso(), ...extra };
}

export function ok<T>(res: Response, status: number, data: T): void {
  res.status(status).json({ data, meta: meta() });
}

export function okList<T>(res: Response, status: number, data: T[], nextCursor: string | null = null): void {
  res.status(status).json({ data, meta: meta({ nextCursor }) });
}

export function fail(
  res: Response,
  status: number,
  code: string,
  message: string,
  details?: Record<string, unknown>
): void {
  res.status(status).json({ error: { code, message, ...(details ? { details } : {}) }, meta: meta() });
}
