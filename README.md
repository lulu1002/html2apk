# Dev Toolbox

App Android (Kotlin, sem bibliotecas externas) com ferramentas para desenvolvedores:

- **GitHub**: repositórios, Actions (status dos builds, jobs/etapas com falha, re-executar, cancelar),
  issues, pull requests, navegador de código, releases.
- **Rede**: scanner da rede local (+ portas abertas por dispositivo), ping, DNS, teste de portas, IP.
- **Cliente de API** com histórico, **JSON/XML**, **conversores** (Base64, URL, hex, hashes, timestamp, JWT, UUID),
  **regex**, **cores**, **monitor de sites** e **cheat sheets**.

## Compilar pelo GitHub Actions
1. Envie estes arquivos para um repositório (branch `main`).
2. Aba **Actions** → **Build APK (Dev Toolbox)** → baixe o artefato **devtools-apk**.

## Token do GitHub
Opcional para repositórios públicos. Para repos privados ou re-executar builds, crie um token fine-grained em
GitHub → Settings → Developer settings, com permissões mínimas (Contents/Metadata: leitura; Actions: leitura e escrita).
O token fica salvo apenas no armazenamento privado do app.
