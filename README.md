# PgManager

Gerenciador PostgreSQL para Android (Kotlin + Jetpack Compose + JDBC).

## Como compilar no GitHub Actions
1. Crie um repositório no GitHub e envie todo o conteúdo desta pasta (push na branch `main`).
2. Abra a aba **Actions** → workflow **Build APK** (roda sozinho a cada push; também dá para rodar manualmente em *Run workflow*).
3. Ao terminar, baixe o APK em **Artifacts → PgManager-apk**.
4. Para publicar em Releases: `git tag v1.0 && git push origin v1.0`.

## Recursos
- Várias conexões salvas (senhas criptografadas no aparelho), teste de conexão, SSL opcional
- Listar / criar / excluir bancos
- Listar tabelas, views e matviews (com filtro), criar tabela via editor SQL
- Ver dados paginados (100 por página), inserir / editar / excluir linhas
- Ver estrutura (colunas, tipos, defaults) e índices
- Esvaziar (TRUNCATE) e excluir tabelas
- Editor SQL com várias instruções, tempo de execução e exportação CSV (compartilhar)
