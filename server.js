require("dotenv").config();
const express = require("express");
const path = require("path");

const app = express();
const PORT = process.env.PORT || 3002;
const GROQ_API_KEY = process.env.GROQ_API_KEY;
const APP_ACCESS_CODE = process.env.APP_ACCESS_CODE; // opcional: protege seu site/chave
const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

app.use(express.json({ limit: "4mb" }));

// CORS: necessário para o APK (Capacitor) chamar o servidor hospedado
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type, x-access-code");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

app.use(express.static(path.join(__dirname, "public")));
app.get("/api/health", (req, res) => res.json({ ok: true, needsCode: !!APP_ACCESS_CODE }));

app.post("/api/chat", async (req, res) => {
  if (APP_ACCESS_CODE && req.get("x-access-code") !== APP_ACCESS_CODE) {
    return res.status(401).json({ error: "Código de acesso inválido. Informe-o em ⚙️ Configurações." });
  }
  if (!GROQ_API_KEY) {
    return res.status(500).json({ error: "GROQ_API_KEY não configurada no servidor (veja o README)." });
  }
  const { messages, model } = req.body || {};
  if (!Array.isArray(messages) || !messages.length) {
    return res.status(400).json({ error: "Campo 'messages' inválido." });
  }

  // Cancela a chamada à Groq se o usuário apertar "Parar" / fechar o app
  const ctrl = new AbortController();
  res.on("close", () => ctrl.abort());

  try {
    const groqRes = await fetch(GROQ_URL, {
      method: "POST",
      signal: ctrl.signal,
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${GROQ_API_KEY}` },
      body: JSON.stringify({ model: model || "openai/gpt-oss-20b", messages, temperature: 0.4, stream: true }),
    });

    if (!groqRes.ok || !groqRes.body) {
      const detail = await groqRes.text().catch(() => "");
      let message = detail || "Erro ao chamar a API da Groq.";
      try { message = JSON.parse(detail).error?.message || message; } catch {}
      return res.status(groqRes.status || 500).json({ error: message });
    }

    res.setHeader("Content-Type", "text/event-stream");
    res.setHeader("Cache-Control", "no-cache");
    res.setHeader("Connection", "keep-alive");
    const reader = groqRes.body.getReader();
    const decoder = new TextDecoder();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      res.write(decoder.decode(value, { stream: true }));
    }
    res.end();
  } catch (err) {
    if (err.name === "AbortError") return res.end();
    console.error("Erro /api/chat:", err);
    if (!res.headersSent) res.status(500).json({ error: "Falha ao contatar a API da Groq." });
    else res.end();
  }
});

app.get("*", (req, res) => res.sendFile(path.join(__dirname, "public", "index.html")));
app.listen(PORT, () => console.log(`Servidor rodando na porta ${PORT}`));
