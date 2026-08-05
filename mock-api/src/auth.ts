import type { Request, Response, NextFunction } from "express";
import { store, UserRecord } from "./store.js";
import { randomToken, uuid } from "./ids.js";

export interface AuthedRequest extends Request {
  user?: UserRecord;
}

/** 실제 Cognito를 흉내내지 않는 opaque 토큰만 발급한다 (지시문 7절). */
export function issueTestLogin(email: string, displayName: string): { user: UserRecord; token: string } {
  const existing = [...store.users.values()].find((u) => u.email === email);
  const user: UserRecord = existing ?? { userId: uuid(), email, displayName };
  store.users.set(user.userId, user);

  const token = randomToken();
  store.sessions.set(token, { token, userId: user.userId });
  return { user, token };
}

export function requireAuth(req: AuthedRequest, res: Response, next: NextFunction): void {
  const header = req.headers.authorization;
  const token = header?.startsWith("Bearer ") ? header.slice("Bearer ".length) : undefined;
  const session = token ? store.sessions.get(token) : undefined;
  if (!session) {
    res.status(401).json({ error: "UNAUTHENTICATED" });
    return;
  }
  const user = store.users.get(session.userId);
  if (!user) {
    res.status(401).json({ error: "UNAUTHENTICATED" });
    return;
  }
  req.user = user;
  next();
}
