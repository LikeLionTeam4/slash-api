import express from "express";
import { createServer } from "node:http";
import { restRouter, testRouter } from "./restRouter.js";
import { pairingRouter } from "./pairing.js";
import { createUserWss, handleUserUpgrade } from "./userWss.js";
import { createAgentWss, handleAgentUpgrade } from "./agentWss.js";

const PORT = Number(process.env.MOCK_API_HTTP_PORT ?? 4000);

const app = express();
app.use(express.json());

// 브라우저(slash-web, 다른 포트)에서 호출하므로 시험 목적의 개방형 CORS만 허용한다.
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", req.headers.origin ?? "*");
  res.setHeader("Access-Control-Allow-Credentials", "true");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,PATCH,DELETE,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Idempotency-Key");
  if (req.method === "OPTIONS") {
    res.status(204).end();
    return;
  }
  next();
});

app.use("/api/v1", restRouter);
app.use("/api/v1", pairingRouter);
app.use("/test", testRouter); // POST /test/reset, /test/login (지시문 표기 그대로 루트 경로)

app.get("/health", (_req, res) => res.json({ ok: true }));

const httpServer = createServer(app);

// `ws`는 같은 서버에 path가 다른 WebSocketServer를 여러 개 `{server}`로 직접 붙이면
// 두 번째부터 업그레이드가 400으로 거부되므로, noServer + 수동 라우팅으로 처리한다.
const userWss = createUserWss();
const agentWss = createAgentWss();
httpServer.on("upgrade", (request, socket, head) => {
  const { pathname } = new URL(request.url ?? "", "http://localhost");
  if (pathname === "/ws/user") {
    handleUserUpgrade(userWss, request, socket, head);
  } else if (pathname === "/ws/agent") {
    handleAgentUpgrade(agentWss, request, socket, head);
  } else {
    socket.destroy();
  }
});

httpServer.listen(PORT, () => {
  console.log(`[mock-api] listening on :${PORT} (REST /api/v1, WS /ws/user, /ws/agent)`);
});
