# Construtor de HTML para APK

App Android que gera APKs a partir de HTML **dentro do próprio celular**, sem servidor.

## Como funciona
1. O módulo `template` é um app WebView sem dependências (só `android.*`). Ele carrega `assets/www/index.html`.
2. O módulo `app` (o construtor) embute esse molde compilado como `assets/template.apk`.
3. Ao tocar em **Gerar APK**, o construtor:
   - copia o molde e troca nome/pacote/versão no `AndroidManifest.xml` binário (`AxmlPatcher`);
   - coloca seu HTML (ou ZIP do site) em `assets/www/`, grava `config.json` (tela cheia/orientação);
   - troca o ícone (se você escolheu um);
   - reescreve o ZIP com `resources.arsc` alinhado (`ZipWriter`) e assina com `apksig` (v1+v2).

## Compilar (GitHub Actions, sem PC)
1. Crie um repositório e suba esta pasta.
2. Aba **Actions** → *Build APK* → **Run workflow**.
3. Baixe o artefato `ConstrutorHTML-apk` (zip com `app-debug.apk`) e instale.

Local, com Android SDK + JDK 17: `gradle :app:assembleDebug`.

## Observações
- Todos os APKs gerados usam a mesma chave (`app/src/main/assets/signing`). Serve para uso pessoal;
  para publicar na Play Store, reassine com a sua própria chave.
- O molde precisa continuar **sem dependências androidx**: elas criam permissões com o nome do pacote
  e dois apps gerados não instalariam juntos.
- `versionCode` fica sempre 1. Para atualizar um app já instalado, desinstale antes se o Android reclamar.
- ZIP: precisa ter `index.html` (pode estar dentro de uma pasta).
