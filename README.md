# VidGrab

App Android (Kotlin + Jetpack Compose) que baixa vídeo/áudio de links usando yt-dlp + ffmpeg embutidos
([youtubedl-android](https://github.com/JunkFood02/youtubedl-android)).

## Recursos
- Cola/compartilha um link → mostra capa, título e duração
- Vídeo (melhor, 1080p, 720p, 480p, 360p) ou áudio (MP3, M4A)
- Fila (2 simultâneos), progresso, cancelar, histórico, abrir arquivo
- Serviço em primeiro plano com notificação
- Pasta privada (padrão): os arquivos ficam dentro do app, com `.nomedia`, e não aparecem na galeria. Dá para desligar nos Ajustes e salvar em Downloads/VidGrab
- Atualiza o yt-dlp sozinho (1x/dia) e manualmente nos Ajustes

## Compilar no GitHub Actions
1. Crie um repositório e suba esta pasta (`git init && git add . && git commit -m init && git push`).
2. Aba **Actions** → o workflow *Build APK* roda no push. Baixe o APK em **Artifacts**.
   (Use `app-arm64-v8a-debug.apk` na maioria dos celulares, ou o `universal`.)

### APK release assinado (opcional)
Gere um keystore e cadastre em Settings → Secrets → Actions:
- `KEYSTORE_BASE64` → `base64 -w0 release.jks`
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

## Compilar localmente
Android Studio (JDK 17) → Open → Run. Ou `gradle assembleDebug` (Gradle 8.9).

## Trocar nome/pacote
Altere `applicationId` e `namespace` em `app/build.gradle.kts`, `app_name` em `strings.xml`
e renomeie o pacote `com.example.vidgrab`.
