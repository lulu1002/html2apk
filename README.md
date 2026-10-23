# Bloco de Notas Android

Aplicativo Android offline para criar, editar e apagar notas organizadas em categorias.

## Recursos
- Criar, editar e apagar notas.
- Criar, renomear e apagar categorias.
- Filtrar notas por categoria.
- Salvamento local no próprio aplicativo usando armazenamento privado (`SharedPreferences`).
- Interface em português.
- Workflow do GitHub Actions para compilar e publicar o APK como artefato.

## Compilar localmente
Requisitos: JDK 17, Android SDK e conexão com a internet na primeira compilação.

```bash
./gradlew assembleDebug
```

APK gerado em:
`app/build/outputs/apk/debug/app-debug.apk`

## Compilar no GitHub Actions
1. Crie um repositório no GitHub e envie todos os arquivos deste projeto.
2. Garanta que a branch principal se chame `main` ou `master`.
3. Acesse a aba **Actions** e execute **Build APK** (ou faça push).
4. Ao concluir, abra a execução do workflow e baixe o artefato **bloco-de-notas-apk**.

## Observações
- As notas ficam no armazenamento privado do app e não são enviadas para a internet.
- Desinstalar o app ou limpar os dados do aplicativo apaga as notas locais.
- Apagar uma categoria também apaga as notas associadas a ela.
