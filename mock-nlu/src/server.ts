import express from "express";
import { analyze } from "./analyze.js";

const PORT = Number(process.env.MOCK_NLU_HTTP_PORT ?? 4010);

const app = express();
app.use(express.json());

app.post("/internal/v1/nlu/analyze", (req, res) => {
  const text = typeof req.body?.text === "string" ? req.body.text : "";
  res.json(analyze(text));
});

app.get("/health", (_req, res) => res.json({ ok: true }));

app.listen(PORT, () => {
  console.log(`[mock-nlu] listening on :${PORT}`);
});
