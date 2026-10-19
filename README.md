# Dev Toolbox

App Android (Kotlin, sem bibliotecas externas) com ferramentas para desenvolvedores:

- **GitHub**: repositórios, Actions (status dos builds, jobs/etapas com falha, re-executar, cancelar),
  issues, pull requests, navegador de código, releases.
  **Edição:** editar/criar/mover/excluir arquivos, enviar arquivos do celular, enviar projeto .zip (um único commit),
  criar/trocar branch e criar repositório.
- **Rede**: scanner da rede local (+ portas abertas por dispositivo), ping, DNS, teste de portas, IP.
- **Cliente de API** com histórico, **JSON/XML**, **conversores** (Base64, URL, hex, hashes, timestamp, JWT, UUID),
  **regex**, **cores**, **monitor de sites** e **cheat sheets**.

## Compilar pelo GitHub Actions
1. Envie estes arquivos para um repositório (branch `main`).
2. Aba **Actions** → **Build APK (Dev Toolbox)** → baixe o artefato **devtools-apk**.

## Token do GitHub
Opcional para repositórios públicos. Para repos privados ou re-executar builds, crie um token fine-grained em
GitHub → Settings → Developer settings. Para editar e enviar código: **Contents: Read and write**; para builds: **Actions: Read and write**;
para alterar `.github/workflows`: **Workflows: Read and write**. Criar repositório pode exigir token clássico com escopo `repo`.
O token fica salvo apenas no armazenamento privado do app.
