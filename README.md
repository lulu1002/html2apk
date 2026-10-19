# Meu Launcher

Launcher de apps para Android, personalizável e **não permanente**: não se registra como tela
inicial (sem categoria `HOME`), então aparece na gaveta de apps como qualquer app e nunca
substitui o seu launcher atual. Voltar sai dele.

## Recursos
- **Tela inicial** com relógio, data, apps recentes/mais usados, dock e um botão **Apps** (ou deslize para cima) que abre a lista; Voltar na lista volta para a tela inicial. Dá para ligar "Abrir direto na lista de apps" em Personalizar
- Lista de apps com busca (Enter abre o primeiro resultado)
- Dock com até 5 apps fixados
- Personalização: tema (sistema/claro/escuro/AMOLED), cor de destaque, colunas (3–6),
  tamanho do ícone, nomes dos apps, ordenação, fechar ao abrir app
- **Imagem de fundo** da galeria, com controle de escurecimento (e botão para remover)
- **Pastas**: segure um app > "Mover para pasta"; toque na pasta para abrir; segure a pasta para renomear/excluir
- **Gestos** configuráveis (deslizar para baixo/cima, toque duplo): buscar, bloquear a tela, abrir Personalizar, abrir notificações
- **Linha rápida** no topo com os apps recentes ou mais usados (e ordenação por mais usados)
- **Bloqueio por app**: segure um app > "Bloquear com senha/digital" (usa a senha, PIN, padrão ou digital do próprio celular, ou uma **senha própria do launcher**, separada da do celular — escolha em Personalizar > Bloqueio de apps; a senha própria não entra no backup). Vale para abrir pelo launcher; Personalizar também pede a senha quando há apps bloqueados
- **Backup**: exporta/importa todas as configurações (e a imagem de fundo) em um arquivo JSON
- Segure um app: fixar/desafixar, pasta, bloqueio, ocultar, informações, desinstalar
- Atualiza sozinho ao instalar/remover apps
- **Launcher padrão opcional**: em Personalizar, ligue "Permitir usar como tela inicial" e use o botão para escolher no sistema. Desligado por padrão; desligar volta ao modo de app comum

## Compilar no GitHub Actions
1. Crie um repositório e envie esta pasta (o arquivo `.github/workflows/build.yml` já está incluso).
2. Aba **Actions** → workflow **Build APK** (roda a cada push; ou clique em *Run workflow*).
3. Ao terminar, baixe o artefato **MeuLauncher-debug** (contém o `.apk` assinado com chave de debug).

Stack: Kotlin, AGP 8.5.2, Gradle 8.7, JDK 17, minSdk 26, targetSdk 34.
