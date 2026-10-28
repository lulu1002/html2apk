# CodeIA — Assistente de programação (via API da Groq)

Site com um chat de IA voltado para programação: você digita o pedido (e pode
anexar arquivos de código), e o modelo responde com código. A geração roda na
nuvem através da **API da Groq** — rápida e com plano gratuito generoso — em
vez de rodar no seu dispositivo (a versão 100% no navegador foi trocada por
essa por causa da lentidão em celulares).

## Como funciona

- O front-end (`public/`) manda o prompt para `/api/chat` no seu próprio
  servidor Express.
- O `server.js` repassa a conversa para a Groq usando sua chave de API, que
  fica **só no servidor** (variável de ambiente `GROQ_API_KEY`) — nunca é
  exposta no navegador do usuário.
- A resposta é transmitida em streaming (token a token) de volta pro chat.

## 1. Criar sua chave da Groq (grátis)

1. Crie uma conta em https://console.groq.com
2. Vá em **API Keys** e gere uma chave.
3. Guarde essa chave — você vai usar como `GROQ_API_KEY`.

O plano gratuito da Groq tem um limite diário de requisições, suficiente
para uso pessoal/testes.

## 2. Rodando localmente

```bash
npm install
cp .env.example .env
# edite o .env e cole sua chave em GROQ_API_KEY
npm start
```

Acesse `http://localhost:3000`.

## 3. Deploy no Render

1. Suba esta pasta para um repositório no GitHub.
2. No Render, crie um **Web Service** apontando para o repositório.
3. Configurações:
   - **Build Command:** `npm install`
   - **Start Command:** `npm start`
   - **Environment:** Node
4. Em **Environment → Environment Variables**, adicione:
   - `GROQ_API_KEY` = sua chave da Groq
5. O Render já injeta `PORT` automaticamente — o `server.js` lê
   `process.env.PORT`, não precisa mexer em nada.

## Novidades desta versão

- **Conversa persistente:** o histórico fica salvo no `localStorage` do
  navegador — recarregar a página não perde o chat. O botão 🗑️ limpa tudo.
- **Baixar código:** cada bloco de código tem botões **Copiar** e **Baixar**
  (baixa só aquele arquivo).
- **Executar JavaScript:** blocos `js`/`javascript` ganham um botão
  **▶ Executar**, que roda o código numa `iframe` isolada (sandbox, sem
  acesso à página) e mostra a saída de `console.log`/`console.error`. Não
  interrompe loops infinitos — evite pedir/rodar código que trave de
  propósito.
- **Projeto completo em .zip:** quando você pede pra gerar vários arquivos,
  o prompt de sistema instrui o modelo a marcar cada um com uma linha
  `Arquivo: caminho/nome.ext` antes do bloco de código. Se a resposta tiver
  mais de um arquivo nesse formato, aparece um botão **📦 Baixar projeto
  (.zip)** que empacota tudo.

## Anexando um projeto inteiro (.zip)

Dá para anexar um `.zip` do projeto: o navegador abre o zip localmente (com
JSZip, via CDN) e extrai só os arquivos de texto/código reconhecidos,
ignorando `node_modules/`, `.git/`, `dist/`, `build/`, `vendor/` e arquivos
binários (imagens, executáveis etc.). Isso evita mandar lixo binário pra API
e estourar o limite de tokens do plano gratuito da Groq.

Se o projeto for grande, nem tudo cabe em uma mensagem — o app corta pelo
limite (`MAX_TOTAL_ATTACH_CHARS` em `public/app.js`) e avisa quando algum
arquivo ficou de fora. Nesses casos, é melhor anexar só as pastas/arquivos
relevantes pra pergunta em vez do projeto inteiro.

## Modelos disponíveis no seletor
- `openai/gpt-oss-20b` — padrão, rápido e bom custo-benefício para código.
- `openai/gpt-oss-120b` — mais forte, um pouco mais lento.
- `qwen/qwen3.6-27b` — ótimo desempenho específico em código.
- `moonshotai/kimi-k2-instruct` — alternativa.

A lista de modelos disponíveis na Groq muda com o tempo; confira a lista
atualizada em https://console.groq.com/docs/models e ajuste as `<option>`
em `public/index.html` se algum desses for descontinuado.

## Personalização rápida

- **Prompt de sistema:** constante `SYSTEM_PROMPT` no topo de `public/app.js`.
- **Modelo padrão:** primeira `<option>` em `public/index.html`.
- **Tema visual:** cores e layout em `public/style.css` (tema escuro).
- **Tipos de arquivo aceitos no anexo:** atributo `accept` do `<input
  id="fileInput">` em `public/index.html`.

## Segurança

- Nunca comite o `.env` com a chave real (já está no `.gitignore`).
- Toda a chamada à Groq passa pelo seu backend — o navegador do usuário
  jamais vê a chave de API.

## Novidades da v3

- **⚙️ Configurações:** tamanho do contexto (Econômico/Normal/Grande), URL do servidor, chave da Groq própria e código de acesso.
- **Modos rápidos:** Explicar, Revisar, Depurar, Testes e Refatorar.
- **⏹ Parar** a geração; **▶ Visualizar** blocos HTML; markdown melhor (títulos, listas, `código`, links).
- **Servidor:** cancela a chamada à Groq ao parar, CORS liberado (APK) e `APP_ACCESS_CODE` opcional para ninguém gastar sua chave.

## Gerar o APK (GitHub Actions)

1. Suba esta pasta para um repositório no GitHub (o `.env` já fica de fora).
2. Aba **Actions → Build APK → Run workflow** (também roda a cada push).
3. Ao terminar, baixe **CodeIA-apk** em *Artifacts* (zip com `app-debug.apk`) e instale no celular.
4. No app, abra ⚙️ e informe **a URL do seu servidor no Render** (+ código de acesso, se usar) **ou** sua chave da Groq.

Local: `npm install && npm run www && npx cap add android && npx cap sync android`.

## Projeto, Diff e Aplicar (v4)

Anexe um `.zip` e ele vira o **projeto**: fica salvo no aparelho (IndexedDB), e a cada pergunta vão só a árvore de arquivos e os mais relevantes (cabendo no contexto de ⚙️). Quando a IA devolver `Arquivo: caminho` + código, o bloco ganha **Diff** (linhas removidas/adicionadas) e **✔ Aplicar** (atualiza o projeto salvo). **📦 Baixar** exporta o projeto atualizado em .zip. Arquivos maiores que o limite de contexto não são enviados, para a IA nunca devolver um arquivo incompleto.

## Várias conversas (v5)

O botão **☰** abre a lista de conversas: crie uma nova, volte para uma antiga ou apague. Ficam salvas no aparelho (até 30; as mais antigas saem primeiro) e o título vem da primeira mensagem. O histórico da versão anterior é migrado sozinho. Para trocar de conversa, pare a geração antes.

## Limite de tokens (v6)

Cada requisição respeita um teto por preset de ⚙️ (Econômico ≈ 12 mil caracteres, Normal ≈ 18 mil, Grande ≈ 40 mil — o Grande só serve no plano pago da Groq). Anexos de mensagens antigas não são reenviados e as trocas mais velhas caem primeiro. Se a chamada falhar, a pergunta volta para o campo de texto e o erro não entra no histórico.
