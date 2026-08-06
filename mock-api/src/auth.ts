import type { Request, Response, NextFunction } from "express";
import { store, UserRecord } from "./store.js";
import { randomToken, uuid } from "./ids.js";
import { fail } from "./envelope.js";

export interface AuthedRequest extends Request {
  user?: UserRecord;
}

/** 실제 Cognito를 흉내내지 않는 opaque 토큰만 발급한다 (지시문 7절). 기존 시험 코드 호환용으로 유지. */
export function issueTestLogin(email: string, displayName: string): { user: UserRecord; token: string } {
  const existing = [...store.users.values()].find((u) => u.email === email);
  const user: UserRecord = existing ?? { userId: uuid(), email, displayName };
  store.users.set(user.userId, user);

  const token = randomToken();
  store.sessions.set(token, { token, userId: user.userId });
  return { user, token };
}

/**
 * 실제 slash-api의 `local` 프로필 로컬 임시 인증과 동일한 규칙(docs/frontend-api-contract.md §5.1):
 * `Authorization: Bearer <아무 문자열>`을 보내면 그 문자열 자체가 사용자 식별자가 된다 — 같은
 * 문자열은 항상 같은 사용자, 처음 보낸 순간 자동 생성. 영문·숫자·`.` `_` `-`, 최대 64자.
 */
const LOCAL_DEV_TOKEN_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

function findOrCreateLocalDevUser(token: string): UserRecord {
  const email = `${token}@local.test`;
  const existing = [...store.users.values()].find((u) => u.email === email);
  if (existing) return existing;
  const user: UserRecord = { userId: uuid(), email, displayName: token };
  store.users.set(user.userId, user);
  return user;
}

export function requireAuth(req: AuthedRequest, res: Response, next: NextFunction): void {
  const header = req.headers.authorization;
  const token = header?.startsWith("Bearer ") ? header.slice("Bearer ".length) : undefined;
  if (!token) {
    fail(res, 401, "AUTH_REQUIRED", "로그인이 필요합니다.");
    return;
  }

  const session = store.sessions.get(token);
  if (session) {
    const user = store.users.get(session.userId);
    if (!user) {
      fail(res, 401, "AUTH_REQUIRED", "로그인이 필요합니다.");
      return;
    }
    req.user = user;
    next();
    return;
  }

  if (LOCAL_DEV_TOKEN_PATTERN.test(token)) {
    req.user = findOrCreateLocalDevUser(token);
    next();
    return;
  }

  fail(res, 401, "AUTH_REQUIRED", "로그인이 필요합니다.");
}
