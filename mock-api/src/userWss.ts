import { WebSocketServer, WebSocket } from "ws";
import type { IncomingMessage } from "node:http";
import type { Duplex } from "node:stream";
import { UserEvent } from "@slash-api-mock/contracts";
import { store } from "./store.js";
import { nowIso, uuid } from "./ids.js";

const connectionsByUser = new Map<string, Set<WebSocket>>();

export function broadcastToUser(userId: string, event: UserEvent): void {
  const sockets = connectionsByUser.get(userId);
  if (!sockets) return;
  const payload = JSON.stringify(event);
  for (const socket of sockets) {
    if (socket.readyState === WebSocket.OPEN) socket.send(payload);
  }
}

/**
 * `ws`는 같은 HTTP 서버에 `path`가 다른 WebSocketServer를 두 개 이상 `{server}`로 직접 붙이면
 * 두 번째부터 업그레이드가 400으로 거부된다 (라이브러리 자체 제약). 그래서 `noServer: true`로 만들고
 * server.ts에서 pathname을 직접 라우팅해 handleUpgrade를 호출한다.
 */
export function createUserWss(): WebSocketServer {
  const wss = new WebSocketServer({ noServer: true });

  wss.on("connection", (socket, request) => {
    const url = new URL(request.url ?? "", "http://localhost");
    const ticket = url.searchParams.get("ticket") ?? "";
    const ticketRecord = store.wsTickets.get(ticket);

    // 30초·1회용 Ticket만 인정한다 — 장기 세션 토큰을 WS URL 쿼리스트링에 남기지 않기 위해서다
    // (메시지 프로토콜 문서 §4.4: URL에 노출되는 값은 반드시 단기·1회용이어야 한다).
    if (!ticketRecord || ticketRecord.used || new Date(ticketRecord.expiresAt).getTime() < Date.now()) {
      socket.close(4401, "unauthenticated");
      return;
    }
    ticketRecord.used = true;

    const { userId } = ticketRecord;
    if (!connectionsByUser.has(userId)) connectionsByUser.set(userId, new Set());
    connectionsByUser.get(userId)!.add(socket);

    const connected: UserEvent = {
      type: "CONNECTED",
      connectionId: uuid(),
      serverTime: nowIso(),
    };
    socket.send(JSON.stringify(connected));

    socket.on("close", () => {
      connectionsByUser.get(userId)?.delete(socket);
    });

    socket.on("message", (raw) => {
      try {
        const parsed = JSON.parse(raw.toString());
        if (parsed?.type === "PING") {
          const pong: UserEvent = { type: "PONG", sentAt: nowIso() };
          socket.send(JSON.stringify(pong));
        }
      } catch {
        // 사용자 WSS는 알림 전용이므로 잘못된 메시지는 조용히 무시한다.
      }
    });
  });

  return wss;
}

export function handleUserUpgrade(
  wss: WebSocketServer,
  request: IncomingMessage,
  socket: Duplex,
  head: Buffer
): void {
  wss.handleUpgrade(request, socket, head, (ws) => {
    wss.emit("connection", ws, request);
  });
}
