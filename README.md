# Rádio (streaming)

App Android (Kotlin + Media3/ExoPlayer) com lista de estações, favoritas, anterior/próxima,
timer para desligar, **busca de rádios do Brasil** (Radio Browser), notificação com controles e reprodução em segundo plano.

## Compilar pelo GitHub Actions
1. Crie um repositório e envie estes arquivos (push na branch `main`).
2. Aba **Actions** → workflow **Build APK** (roda sozinho a cada push, ou clique em *Run workflow*).
3. Ao terminar, baixe o artefato **radio-apk** (contém `app-debug.apk` e `app-release.apk`).

## Compilar localmente
Com Gradle 8.7+ e JDK 17: `gradle assembleDebug`
