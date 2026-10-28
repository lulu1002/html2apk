const modelSelect = document.getElementById("modelSelect");
const clearBtn = document.getElementById("clearBtn");
const chatLog = document.getElementById("chatLog");
const composer = document.getElementById("composer");
const promptInput = document.getElementById("promptInput");
const sendBtn = document.getElementById("sendBtn");
const fileInput = document.getElementById("fileInput");
const attachmentsEl = document.getElementById("attachments");

let MAX_FILE_CHARS = 4000; // limite por arquivo para não estourar o contexto
let MAX_TOTAL_ATTACH_CHARS = 9000; // limite somado de todos os anexos de uma mensagem
let MAX_HISTORY_TURNS = 5;
let REQUEST_BUDGET_CHARS = 18000; // teto de caracteres enviados por requisição (≈ 3,5 car./token) // pares (usuário+assistente) mantidos como contexto enviado à API
const STORAGE_KEY = "codeia_history_v1";
const TEXT_EXTENSIONS = [
  "js", "jsx", "ts", "tsx", "py", "java", "c", "cpp", "h", "hpp", "cs", "go",
  "rb", "php", "html", "css", "json", "md", "txt", "sql", "sh", "yml", "yaml",
  "xml", "kt", "swift", "rs",
];
const IGNORED_PATH_PARTS = ["node_modules/", ".git/", "dist/", "build/", ".next/", "vendor/"];
const SYSTEM_PROMPT =
  "Você é um assistente de programação. Responda em português do Brasil, " +
  "de forma direta. Quando entregar código, use blocos de código markdown " +
  "com a linguagem indicada (ex: ```javascript). Explique brevemente o que " +
  "o código faz antes ou depois do bloco. Quando o pedido envolver gerar " +
  "vários arquivos de um projeto completo, para CADA arquivo escreva uma " +
  "linha exatamente no formato `Arquivo: caminho/nome.ext` imediatamente " +
  "antes do bloco de código correspondente, um bloco por arquivo. Ao alterar um arquivo " +
  "existente do projeto, devolva sempre o arquivo COMPLETO (nunca trechos com \"...\"), " +
  "com o mesmo caminho da árvore do projeto.";

// ---------- Configurações (servidor ou chave própria, ideal pro APK) ----------
const SETTINGS_KEY = "codeia_settings_v1";
const CTX_PRESETS = { eco: [3000, 6000, 4, 12000], normal: [5000, 10000, 6, 18000], grande: [12000, 24000, 10, 40000] };
const IS_NATIVE = !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());
let settings = { ctx: "normal", serverUrl: "", groqKey: "", accessCode: "" };
try { Object.assign(settings, JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}")); } catch {}
function applyCtx() {
  [MAX_FILE_CHARS, MAX_TOTAL_ATTACH_CHARS, MAX_HISTORY_TURNS, REQUEST_BUDGET_CHARS] = CTX_PRESETS[settings.ctx] || CTX_PRESETS.normal;
}
applyCtx();

const settingsDlg = document.getElementById("settingsDlg");
document.getElementById("settingsBtn").addEventListener("click", () => {
  ["ctx", "serverUrl", "groqKey", "accessCode"].forEach((k) => {
    document.getElementById(k === "ctx" ? "ctxSelect" : k).value = settings[k];
  });
  settingsDlg.showModal();
});
document.getElementById("saveSettings").addEventListener("click", () => {
  settings = {
    ctx: document.getElementById("ctxSelect").value,
    serverUrl: document.getElementById("serverUrl").value.trim().replace(/\/$/, ""),
    groqKey: document.getElementById("groqKey").value.trim(),
    accessCode: document.getElementById("accessCode").value,
  };
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
  applyCtx();
  settingsDlg.close();
});

function chatRequest(messages, model, signal) {
  const useServer = settings.serverUrl || !IS_NATIVE;
  if (useServer) {
    return fetch(`${settings.serverUrl}/api/chat`, {
      method: "POST", signal,
      headers: { "Content-Type": "application/json", "x-access-code": settings.accessCode },
      body: JSON.stringify({ messages, model }),
    });
  }
  if (!settings.groqKey) throw new Error("Abra ⚙️ e informe a URL do servidor ou sua chave da Groq.");
  return fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST", signal,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${settings.groqKey}` },
    body: JSON.stringify({ model, messages, temperature: 0.4, stream: true }),
  });
}

// Monta o que realmente vai pra API: anexos antigos saem do histórico e as trocas mais
// velhas são descartadas até caber no teto (evita "Request too large" no plano grátis)
function fitMessages(msgs) {
  const slim = msgs.map((m, i) => {
    if (m.role === "user" && i < msgs.length - 1) {
      const k = m.content.indexOf("\n\nArquivos anexados:");
      if (k > -1) return { ...m, content: m.content.slice(0, k) + "\n[arquivos anexados desta mensagem omitidos do histórico]" };
    }
    return m;
  });
  const size = (a) => a.reduce((s, m) => s + m.content.length, 0);
  while (slim.length > 2 && size(slim) > REQUEST_BUDGET_CHARS) slim.splice(1, 1);
  return slim;
}

// Salvar/baixar arquivo: no APK usa Filesystem + Share (downloads por <a> não funcionam no WebView)
async function saveFile(filename, blob) {
  const P = window.Capacitor && window.Capacitor.Plugins;
  if (IS_NATIVE && P && P.Filesystem && P.Share) {
    try {
      const b64 = await new Promise((res, rej) => {
        const r = new FileReader();
        r.onload = () => res(String(r.result).split(",")[1]);
        r.onerror = () => rej(r.error);
        r.readAsDataURL(blob);
      });
      const out = await P.Filesystem.writeFile({ path: filename, data: b64, directory: "CACHE" });
      await P.Share.share({ title: filename, url: out.uri });
    } catch (e) {
      if (!/cancel/i.test(String(e && e.message))) alert("Não foi possível salvar: " + (e.message || e));
    }
    return;
  }
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = filename; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

// Modos rápidos
document.getElementById("modes").addEventListener("click", (e) => {
  const p = e.target.dataset && e.target.dataset.p;
  if (!p) return;
  promptInput.value = p.replace(/\\n/g, "\n");
  promptInput.focus();
});

let abortCtl = null;
const stopBtn = document.getElementById("stopBtn");
stopBtn.addEventListener("click", () => abortCtl && abortCtl.abort());

// ---------- Projeto persistente (IndexedDB), diff e aplicar ----------
let project = null; // { name, files: { caminho: texto } }
const projectBar = document.getElementById("projectBar");
const idb = () => new Promise((res, rej) => {
  const r = indexedDB.open("codeia", 1);
  r.onupgradeneeded = () => r.result.createObjectStore("kv");
  r.onsuccess = () => res(r.result);
  r.onerror = () => rej(r.error);
});
async function kv(mode, fn) {
  const db = await idb();
  return new Promise((res, rej) => {
    const t = db.transaction("kv", mode);
    const rq = fn(t.objectStore("kv"));
    t.oncomplete = () => res(rq && rq.result);
    t.onerror = () => rej(t.error);
  });
}
const saveProject = () => kv("readwrite", (s) => (project ? s.put(project, "project") : s.delete("project"))).catch(() => {});
async function loadProject() {
  project = (await kv("readonly", (s) => s.get("project")).catch(() => null)) || null;
  renderProject();
}

function renderProject() {
  projectBar.innerHTML = "";
  projectBar.classList.toggle("hidden", !project);
  if (!project) return;
  const label = document.createElement("span");
  label.textContent = `📁 ${project.name} · ${Object.keys(project.files).length} arquivos`;
  const dl = document.createElement("button");
  dl.type = "button"; dl.textContent = "📦 Baixar";
  dl.addEventListener("click", async () => {
    const zip = new JSZip();
    Object.entries(project.files).forEach(([p, c]) => zip.file(p, c));
    await saveFile(`${project.name}-atualizado.zip`, await zip.generateAsync({ type: "blob" }));
  });
  const rm = document.createElement("button");
  rm.type = "button"; rm.textContent = "✕";
  rm.addEventListener("click", () => {
    if (!confirm("Remover o projeto carregado?")) return;
    project = null; saveProject(); renderProject();
  });
  projectBar.append(label, dl, rm);
}

// Um .zip vira o "projeto": fica salvo no aparelho e só os arquivos relevantes vão a cada pergunta
async function importZip(file, rejected) {
  if (typeof JSZip === "undefined") { rejected.push(`${file.name} (suporte a zip não carregou)`); return; }
  let zip;
  try { zip = await JSZip.loadAsync(file); } catch { rejected.push(`${file.name} (zip inválido)`); return; }
  const files = {};
  for (const entry of Object.values(zip.files)) {
    if (entry.dir || IGNORED_PATH_PARTS.some((p) => entry.name.includes(p))) continue;
    if (!TEXT_EXTENSIONS.includes(entry.name.split(".").pop().toLowerCase())) continue;
    const text = await entry.async("string").catch(() => null);
    if (text != null && text.length <= 200000 && Object.keys(files).length < 400) files[entry.name] = text;
  }
  const paths = Object.keys(files);
  if (!paths.length) { rejected.push(`${file.name} (nenhum arquivo de texto reconhecido)`); return; }
  const roots = new Set(paths.map((p) => p.split("/")[0]));
  const strip = roots.size === 1 && paths.every((p) => p.includes("/"));
  project = { name: file.name.replace(/\.zip$/i, ""), files: {} };
  paths.forEach((p) => { project.files[strip ? p.slice(p.indexOf("/") + 1) : p] = files[p]; });
  await saveProject();
  renderProject();
  addMessage("system", `📁 Projeto **${project.name}** carregado (${paths.length} arquivos). Pergunte e eu uso os arquivos relevantes; mudanças voltam com **Diff** e **Aplicar**.`);
}

// Árvore + arquivos mais relevantes para o pedido, dentro do orçamento de contexto
function projectContext(prompt) {
  if (!project) return "";
  const q = prompt.toLowerCase();
  const words = [...new Set(q.match(/[a-z0-9_.\-]{3,}/g) || [])].slice(0, 30);
  const paths = Object.keys(project.files);
  const scored = paths.map((p) => {
    const low = p.toLowerCase(), base = low.split("/").pop(), body = project.files[p].toLowerCase();
    let s = q.includes(base) ? 100 : 0;
    words.forEach((w) => { if (low.includes(w)) s += 5; if (body.includes(w)) s += 1; });
    return [p, s];
  }).filter((x) => x[1] > 0).sort((a, b) => b[1] - a[1]);
  let budget = MAX_TOTAL_ATTACH_CHARS - totalAttachedChars(), out = "";
  for (const [p] of scored) {
    const c = project.files[p];
    if (c.length > MAX_FILE_CHARS || c.length > budget) continue; // nunca manda arquivo cortado
    budget -= c.length;
    out += `Arquivo: ${p}\n\`\`\`\n${c}\n\`\`\`\n\n`;
  }
  return `\n\nProjeto "${project.name}" - árvore de arquivos:\n${paths.slice(0, 200).join("\n")}\n\nArquivos relevantes:\n${out || "(nenhum coube no contexto; peça para eu focar em um arquivo)"}`;
}

function lineDiff(a, b) {
  const A = a.split("\n"), B = b.split("\n");
  let s = 0;
  while (s < A.length && s < B.length && A[s] === B[s]) s++;
  let ea = A.length, eb = B.length;
  while (ea > s && eb > s && A[ea - 1] === B[eb - 1]) { ea--; eb--; }
  const x = A.slice(s, ea), y = B.slice(s, eb), n = x.length, m = y.length;
  if (n * m > 4e6) return [`- (${n} linhas removidas)`, `+ (${m} linhas adicionadas)`];
  const L = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
  for (let i = n - 1; i >= 0; i--)
    for (let k = m - 1; k >= 0; k--)
      L[i][k] = x[i] === y[k] ? L[i + 1][k + 1] + 1 : Math.max(L[i + 1][k], L[i][k + 1]);
  const out = [];
  let i = 0, k = 0;
  while (i < n && k < m) {
    if (x[i] === y[k]) { i++; k++; }
    else if (L[i + 1][k] >= L[i][k + 1]) out.push("- " + x[i++]);
    else out.push("+ " + y[k++]);
  }
  while (i < n) out.push("- " + x[i++]);
  while (k < m) out.push("+ " + y[k++]);
  return out;
}

function addApplyButtons(toolbar, pre, block, path) {
  const mk = (t) => { const b = document.createElement("button"); b.type = "button"; b.className = "copy-btn"; b.textContent = t; return b; };
  const diffEl = document.createElement("pre");
  diffEl.className = "diff hidden";
  const dBtn = mk("Diff"), aBtn = mk("✔ Aplicar");
  dBtn.addEventListener("click", () => {
    const old = project.files[path], nw = block.textContent;
    if (old === undefined) diffEl.textContent = `Arquivo novo: ${path} (${nw.split("\n").length} linhas)`;
    else {
      const d = lineDiff(old.trimEnd(), nw.trimEnd());
      diffEl.innerHTML = d.length
        ? d.map((l) => `<span class="${l[0] === "+" ? "add" : "del"}">${escapeHtml(l)}</span>`).join("")
        : "Sem mudanças.";
    }
    diffEl.classList.toggle("hidden");
  });
  aBtn.addEventListener("click", async () => {
    const nw = block.textContent, old = project.files[path];
    if (old && nw.split("\n").length < old.split("\n").length * 0.6 &&
        !confirm("O novo arquivo é bem menor que o original (pode estar incompleto). Aplicar mesmo assim?")) return;
    project.files[path] = nw;
    await saveProject();
    renderProject();
    aBtn.textContent = "Aplicado ✓";
  });
  toolbar.append(dBtn, aBtn);
  pre.after(diffEl);
}

let history = [{ role: "system", content: SYSTEM_PROMPT }];
let attachedFiles = []; // { name, content }

// ---------- Persistência local (localStorage) ----------
// Várias conversas salvas no aparelho (localStorage), com migração do histórico antigo
const CONVS_KEY = "codeia_convs_v1";
let convs = { active: null, list: [] };

function newConv(h) {
  const c = { id: Date.now().toString(36), title: "Nova conversa", history: h || [{ role: "system", content: SYSTEM_PROMPT }], updated: Date.now() };
  convs.list.unshift(c);
  convs.active = c.id;
  return c;
}
function persistConvs() {
  convs.list.sort((x, y) => y.updated - x.updated);
  convs.list = convs.list.slice(0, 30);
  try { localStorage.setItem(CONVS_KEY, JSON.stringify(convs)); } catch {} // sem espaço/modo privado: segue sem salvar
}
function loadConvs() {
  try { convs = JSON.parse(localStorage.getItem(CONVS_KEY)) || convs; } catch {}
  if (!convs.list.length) {
    let old = null;
    try { old = JSON.parse(localStorage.getItem(STORAGE_KEY)); } catch {}
    newConv(Array.isArray(old) && old.length && old[0].role === "system" ? old : null);
  }
  if (!convs.list.find((c) => c.id === convs.active)) convs.active = convs.list[0].id;
}
function saveHistory() {
  const c = convs.list.find((x) => x.id === convs.active);
  if (!c) return;
  c.history = history;
  const u = history.find((m) => m.role === "user");
  c.title = u ? u.content.split("\n")[0].slice(0, 40) || "Conversa" : "Nova conversa";
  c.updated = Date.now();
  persistConvs();
}
// carrega a conversa ativa na tela; retorna true se ela já tem mensagens
function loadHistory() {
  if (!convs.list.length) loadConvs();
  const c = convs.list.find((x) => x.id === convs.active);
  history = c.history;
  chatLog.innerHTML = "";
  const rest = history.slice(1);
  rest.forEach((m) => addMessage(m.role === "user" ? "user" : "assistant", m.content));
  return rest.length > 0;
}
function showWelcome() {
  addMessage("system", "**CodeIA** — assistente de programação. Escreva seu pedido abaixo; anexe arquivos ou um .zip do projeto como contexto.");
}

const convDlg = document.getElementById("convDlg");
const isGenerating = () => !stopBtn.classList.contains("hidden");
function openConv(id) {
  convs.active = id;
  attachedFiles = [];
  renderAttachments();
  if (!loadHistory()) showWelcome();
  persistConvs();
}
function renderConvList() {
  const box = document.getElementById("convList");
  box.innerHTML = "";
  convs.list.forEach((c) => {
    const row = document.createElement("div");
    row.className = "conv-row" + (c.id === convs.active ? " active" : "");
    const t = document.createElement("button");
    t.type = "button"; t.textContent = c.title;
    t.addEventListener("click", () => { openConv(c.id); convDlg.close(); });
    const d = document.createElement("button");
    d.type = "button"; d.textContent = "🗑";
    d.addEventListener("click", () => {
      if (!confirm("Apagar esta conversa?")) return;
      convs.list = convs.list.filter((x) => x.id !== c.id);
      if (!convs.list.length) newConv();
      if (c.id === convs.active) openConv(convs.list[0].id); else persistConvs();
      renderConvList();
    });
    row.append(t, d);
    box.appendChild(row);
  });
}
document.getElementById("convBtn").addEventListener("click", () => {
  if (isGenerating()) { alert("Pare a geração antes de trocar de conversa."); return; }
  renderConvList();
  convDlg.showModal();
});
document.getElementById("newConv").addEventListener("click", () => {
  newConv();
  openConv(convs.active);
  convDlg.close();
});
document.getElementById("closeConv").addEventListener("click", () => convDlg.close());

clearBtn.addEventListener("click", () => {
  history = [{ role: "system", content: SYSTEM_PROMPT }];
  attachedFiles = [];
  renderAttachments();
  chatLog.innerHTML = "";
  saveHistory();
  addMessage("system", "Conversa limpa. Pode começar de novo.");
});

// ---------- Anexos ----------
fileInput.addEventListener("change", async () => {
  const files = Array.from(fileInput.files || []);
  const rejected = [];
  for (const file of files) {
    const ext = file.name.split(".").pop().toLowerCase();

    if (ext === "zip") {
      await importZip(file, rejected);
      continue;
    }

    if (!TEXT_EXTENSIONS.includes(ext)) {
      rejected.push(`${file.name} (tipo não suportado)`);
      continue;
    }
    addFileEntry(file.name, await file.text().catch(() => null), rejected);
  }
  fileInput.value = "";
  renderAttachments();
  if (rejected.length) {
    addMessage("system", `⚠️ Não foi possível anexar: ${rejected.join(", ")}.`);
  }
});

function totalAttachedChars() {
  return attachedFiles.reduce((sum, f) => sum + f.content.length, 0);
}

function addFileEntry(name, text, rejected) {
  if (text == null) {
    rejected.push(`${name} (não foi possível ler)`);
    return;
  }
  if (totalAttachedChars() >= MAX_TOTAL_ATTACH_CHARS) {
    rejected.push(`${name} (limite de anexos por mensagem atingido)`);
    return;
  }
  const remaining = MAX_TOTAL_ATTACH_CHARS - totalAttachedChars();
  const cap = Math.min(MAX_FILE_CHARS, remaining);
  const truncated = text.length > cap;
  attachedFiles.push({
    name,
    content: truncated ? text.slice(0, cap) + "\n/* ...truncado... */" : text,
  });
}

async function addFromZip(file, rejected) {
  if (typeof JSZip === "undefined") {
    rejected.push(`${file.name} (suporte a zip não carregou, recarregue a página)`);
    return;
  }
  let zip;
  try {
    zip = await JSZip.loadAsync(file);
  } catch {
    rejected.push(`${file.name} (zip inválido ou corrompido)`);
    return;
  }

  const entries = Object.values(zip.files).filter((entry) => !entry.dir);
  let usedAny = false;
  let skippedCount = 0;

  for (const entry of entries) {
    if (totalAttachedChars() >= MAX_TOTAL_ATTACH_CHARS) {
      skippedCount += 1;
      continue;
    }
    const path = entry.name;
    const ext = path.split(".").pop().toLowerCase();
    const ignored = IGNORED_PATH_PARTS.some((part) => path.includes(part));
    if (ignored || !TEXT_EXTENSIONS.includes(ext)) {
      continue;
    }
    const text = await entry.async("string").catch(() => null);
    if (text == null) continue;
    addFileEntry(`${file.name}:${path}`, text, rejected);
    usedAny = true;
  }

  if (!usedAny) {
    rejected.push(`${file.name} (nenhum arquivo de texto reconhecido dentro do zip)`);
  } else if (skippedCount) {
    rejected.push(`${file.name} (alguns arquivos ficaram de fora, limite de tamanho atingido)`);
  }
}

function renderAttachments() {
  attachmentsEl.innerHTML = "";
  if (!attachedFiles.length) {
    attachmentsEl.classList.add("hidden");
    return;
  }
  attachmentsEl.classList.remove("hidden");
  attachedFiles.forEach((f, i) => {
    const chip = document.createElement("div");
    chip.className = "chip";
    chip.innerHTML = `<span>📄 ${escapeHtml(f.name)}</span>`;
    const rm = document.createElement("button");
    rm.textContent = "✕";
    rm.type = "button";
    rm.addEventListener("click", () => {
      attachedFiles.splice(i, 1);
      renderAttachments();
    });
    chip.appendChild(rm);
    attachmentsEl.appendChild(chip);
  });
}

// ---------- Envio de prompt ----------
composer.addEventListener("submit", async (e) => {
  e.preventDefault();
  const text = promptInput.value.trim();
  if (!text) return;

  let fullUserContent = text;
  if (attachedFiles.length) {
    const filesBlock = attachedFiles
      .map((f) => `Arquivo: ${f.name}\n\`\`\`\n${f.content}\n\`\`\``)
      .join("\n\n");
    fullUserContent = `${text}\n\nArquivos anexados:\n${filesBlock}`;
  }

  addMessage("user", text, attachedFiles.map((f) => f.name));
  history.push({ role: "user", content: fullUserContent });
  // o contexto do projeto vai só nesta chamada (não polui o histórico salvo)
  const sendMsgs = fitMessages(history.slice(0, -1).concat({ role: "user", content: fullUserContent + projectContext(text) }));
  saveHistory();
  promptInput.value = "";
  attachedFiles = [];
  renderAttachments();
  sendBtn.disabled = true;
  promptInput.disabled = true;

  const assistantEl = addMessage("assistant", "…");
  let acc = "";
  let clean = ""; // só o texto real da IA (sem avisos de erro)

  try {
    abortCtl = new AbortController();
    stopBtn.classList.remove("hidden");
    sendBtn.classList.add("hidden");
    const res = await chatRequest(sendMsgs, modelSelect.value, abortCtl.signal);

    if (!res.ok || !res.body) {
      const errData = await res.json().catch(() => ({}));
      const msg = (errData.error && errData.error.message) || errData.error || `Erro HTTP ${res.status}`;
      throw new Error(res.status === 413 || res.status === 429
        ? `${msg} (limite do plano grátis: reduza o contexto em ⚙️)` : msg);
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      const lines = buffer.split("\n");
      buffer = lines.pop(); // guarda linha incompleta para o próximo pedaço

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed.startsWith("data:")) continue;
        const payload = trimmed.slice(5).trim();
        if (payload === "[DONE]") continue;
        try {
          const json = JSON.parse(payload);
          const delta = json.choices?.[0]?.delta?.content || "";
          acc += delta;
          clean = acc;
          renderInto(assistantEl, acc);
        } catch {
          // ignora linhas parciais/erros de parse pontuais
        }
      }
    }
  } catch (err) {
    if (err && err.name === "AbortError") {
      acc += "\n\n⏹ _interrompido_";
    } else {
      console.error(err);
      acc += `\n\n⚠️ Ocorreu um erro ao gerar a resposta: ${(err && err.message) || "erro desconhecido"}`;
    }
    renderInto(assistantEl, acc);
  }
  stopBtn.classList.add("hidden");
  sendBtn.classList.remove("hidden");

  if (!clean.trim()) {
    // falhou/interrompeu sem resposta: tira a pergunta do histórico e devolve o texto pro campo
    history.pop();
    promptInput.value = text;
  } else {
    history.push({ role: "assistant", content: clean });
  }
  pruneHistory();
  saveHistory();
  sendBtn.disabled = false;
  promptInput.disabled = false;
  promptInput.focus();
});

// Mantém só o prompt de sistema + as últimas N trocas, pro contexto enviado
// à API não crescer sem limite (e não estourar o limite de tokens do plano).
function pruneHistory() {
  const system = history[0];
  const rest = history.slice(1);
  const maxMessages = MAX_HISTORY_TURNS * 2;
  if (rest.length > maxMessages) {
    history = [system, ...rest.slice(rest.length - maxMessages)];
  }
}

// ---------- Renderização ----------
function addMessage(role, text, fileNames) {
  const div = document.createElement("div");
  div.className = `msg ${role}`;
  chatLog.appendChild(div);
  let content = text;
  if (fileNames && fileNames.length) {
    content += `\n\n_(anexos: ${fileNames.join(", ")})_`;
  }
  renderInto(div, content);
  chatLog.scrollTop = chatLog.scrollHeight;
  return div;
}

function renderInto(el, raw) {
  el.innerHTML = markdownLite(raw);

  el.querySelectorAll("pre code").forEach((block) => {
    if (window.hljs) window.hljs.highlightElement(block);
    const pre = block.parentElement;
    if (pre.querySelector(".code-toolbar")) return;

    const toolbar = document.createElement("div");
    toolbar.className = "code-toolbar";

    const copyBtn = document.createElement("button");
    copyBtn.className = "copy-btn";
    copyBtn.type = "button";
    copyBtn.textContent = "Copiar";
    copyBtn.addEventListener("click", () => {
      navigator.clipboard.writeText(block.textContent);
      copyBtn.textContent = "Copiado!";
      setTimeout(() => (copyBtn.textContent = "Copiar"), 1200);
    });
    toolbar.appendChild(copyBtn);

    const lang = (block.className.match(/language-(\w+)/) || [])[1] || "txt";
    const dlBtn = document.createElement("button");
    dlBtn.className = "copy-btn";
    dlBtn.type = "button";
    dlBtn.textContent = "Baixar";
    dlBtn.addEventListener("click", () => {
      downloadText(`codigo.${extForLang(lang)}`, block.textContent);
    });
    toolbar.appendChild(dlBtn);

    if (/^(js|javascript)$/i.test(lang)) {
      const runBtn = document.createElement("button");
      runBtn.className = "copy-btn run-btn";
      runBtn.type = "button";
      runBtn.textContent = "▶ Executar";
      const output = document.createElement("pre");
      output.className = "run-output hidden";
      runBtn.addEventListener("click", () => {
        output.classList.remove("hidden");
        output.textContent = "Executando…";
        runJsCode(block.textContent, output);
      });
      toolbar.appendChild(runBtn);
      pre.after(output);
    }

    if (/^html$/i.test(lang)) {
      const frame = document.createElement("iframe");
      frame.className = "html-preview hidden";
      frame.sandbox = "allow-scripts";
      const prevBtn = document.createElement("button");
      prevBtn.className = "copy-btn run-btn";
      prevBtn.type = "button";
      prevBtn.textContent = "▶ Visualizar";
      prevBtn.addEventListener("click", () => {
        frame.srcdoc = block.textContent; // só carrega ao clicar
        frame.classList.toggle("hidden");
      });
      toolbar.appendChild(prevBtn);
      pre.after(frame);
    }

    const prev = pre.previousElementSibling;
    const pm = project && prev && prev.tagName === "P" && prev.textContent.match(/Arquivo:\s*([^\s`]+)\s*$/);
    if (pm) addApplyButtons(toolbar, pre, block, pm[1].replace(/^\.\//, ""));

    pre.prepend(toolbar);
  });

  // Detecta múltiplos arquivos gerados (convenção "Arquivo: caminho") e
  // oferece baixar tudo junto em .zip.
  if (!el.querySelector(".zip-btn")) {
    const fileEntries = extractFileEntries(raw);
    if (fileEntries.length > 1) {
      const zipBtn = document.createElement("button");
      zipBtn.className = "copy-btn zip-btn";
      zipBtn.type = "button";
      zipBtn.textContent = `📦 Baixar projeto (.zip · ${fileEntries.length} arquivos)`;
      zipBtn.addEventListener("click", () => downloadAsZip(fileEntries));
      el.appendChild(zipBtn);
    }
  }

  chatLog.scrollTop = chatLog.scrollHeight;
}

function extractFileEntries(raw) {
  const entries = [];
  const re = /Arquivo:\s*([^\n`]+)\n```(\w*)\n([\s\S]*?)```/g;
  let match;
  while ((match = re.exec(raw))) {
    entries.push({ path: match[1].trim(), content: match[3] });
  }
  return entries;
}

async function downloadAsZip(entries) {
  if (typeof JSZip === "undefined") {
    alert("Suporte a zip não carregou. Recarregue a página e tente de novo.");
    return;
  }
  const zip = new JSZip();
  entries.forEach((f) => zip.file(f.path, f.content));
  const blob = await zip.generateAsync({ type: "blob" });
  await saveFile("projeto-codeia.zip", blob);
}

function downloadText(filename, text) {
  saveFile(filename, new Blob([text], { type: "text/plain" }));
}

function extForLang(lang) {
  const map = {
    js: "js", javascript: "js", ts: "ts", typescript: "ts", py: "py",
    python: "py", java: "java", c: "c", cpp: "cpp", "c++": "cpp",
    cs: "cs", go: "go", rb: "rb", php: "php", html: "html", css: "css",
    json: "json", md: "md", sql: "sql", sh: "sh", bash: "sh",
    yml: "yml", yaml: "yml", xml: "xml", kt: "kt", swift: "swift", rs: "rs",
  };
  return map[lang.toLowerCase()] || "txt";
}

// Executa JS em uma iframe isolada (sandbox, sem acesso ao documento
// principal) e mostra o que foi impresso via console.log/error/warn.
function runJsCode(code, outputEl) {
  const iframe = document.createElement("iframe");
  iframe.sandbox = "allow-scripts";
  iframe.style.display = "none";
  const safeCode = code.replace(/<\/script>/gi, "<\\/script>");
  iframe.srcdoc = `<script>
    const logs = [];
    const fmt = (a) => a.map((v) => {
      try { return typeof v === "object" ? JSON.stringify(v) : String(v); }
      catch (e) { return String(v); }
    }).join(" ");
    console.log = (...a) => logs.push({ type: "log", text: fmt(a) });
    console.error = (...a) => logs.push({ type: "error", text: fmt(a) });
    console.warn = (...a) => logs.push({ type: "warn", text: fmt(a) });
    try {
      ${safeCode}
    } catch (e) {
      logs.push({ type: "error", text: e.message });
    }
    parent.postMessage({ __codeia_run: true, logs: logs }, "*");
  <\/script>`;
  document.body.appendChild(iframe);

  const timeout = setTimeout(() => {
    window.removeEventListener("message", handler);
    outputEl.textContent = "⏱️ Tempo esgotado (ou o código não terminou).";
    iframe.remove();
  }, 4000);

  function handler(e) {
    if (!e.data || !e.data.__codeia_run) return;
    clearTimeout(timeout);
    window.removeEventListener("message", handler);
    const logs = e.data.logs || [];
    outputEl.textContent = logs.length
      ? logs.map((l) => (l.type === "error" ? "⚠️ " : "") + l.text).join("\n")
      : "(sem saída de console.log)";
    iframe.remove();
  }
  window.addEventListener("message", handler);
}

// Conversor markdown: blocos de código, títulos, listas, `código`, negrito, itálico, links
function inlineMd(s) {
  return s
    .replace(/`([^`\n]+)`/g, "<code>$1</code>")
    .replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>")
    .replace(/\[([^\]]+)\]\((https?:[^)\s"]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
}

function mdBlock(plain) {
  let html = "", para = [], list = null;
  const flushP = () => { if (para.length) html += `<p>${inlineMd(para.join("<br>"))}</p>`; para = []; };
  const flushL = () => { if (list) html += `</${list}>`; list = null; };
  for (const line of plain.split("\n")) {
    const h = line.match(/^(#{1,4})\s+(.*)/);
    const li = line.match(/^\s*([-*]|\d+\.)\s+(.*)/);
    if (h) { flushP(); flushL(); const n = Math.min(h[1].length + 1, 4); html += `<h${n}>${inlineMd(h[2])}</h${n}>`; }
    else if (li) {
      flushP();
      const tag = /\d/.test(li[1]) ? "ol" : "ul";
      if (list !== tag) { flushL(); html += `<${tag}>`; list = tag; }
      html += `<li>${inlineMd(li[2])}</li>`;
    } else if (!line.trim()) { flushP(); flushL(); }
    else { flushL(); para.push(line); }
  }
  flushP(); flushL();
  return html;
}

function markdownLite(text) {
  const parts = escapeHtml(text).split(/```(\w*)\n([\s\S]*?)```/g);
  let html = "";
  for (let i = 0; i < parts.length; i += 3) {
    html += mdBlock(parts[i] || "");
    const lang = parts[i + 1], code = parts[i + 2];
    if (code !== undefined) html += `<pre><code class="${lang ? "language-" + lang : ""}">${code}</code></pre>`;
  }
  return html;
}

function escapeHtml(str) {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

// ---------- Inicialização ----------
loadProject();
loadConvs();
if (!loadHistory()) showWelcome();
