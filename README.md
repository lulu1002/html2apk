# Meu Launcher

Launcher de apps para Android, personalizável e **não permanente**: não se registra como tela
inicial (sem categoria `HOME`), então aparece na gaveta de apps como qualquer app e nunca
substitui o seu launcher atual. Voltar sai dele.

## Recursos
- Grade de apps com busca (Enter abre o primeiro resultado)
- Dock com até 5 apps fixados
- Personalização: tema (sistema/claro/escuro/AMOLED), cor de destaque, colunas (3–6),
  tamanho do ícone, nomes dos apps, ordenação, fechar ao abrir app
- Segure um app: fixar/desafixar, ocultar, informações, desinstalar
- Atualiza sozinho ao instalar/remover apps

## Compilar no GitHub Actions
1. Crie um repositório e envie esta pasta (o arquivo `.github/workflows/build.yml` já está incluso).
2. Aba **Actions** → workflow **Build APK** (roda a cada push; ou clique em *Run workflow*).
3. Ao terminar, baixe o artefato **MeuLauncher-debug** (contém o `.apk` assinado com chave de debug).

Stack: Kotlin, AGP 8.5.2, Gradle 8.7, JDK 17, minSdk 26, targetSdk 34.
