package com.exemplo.devtools

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.LinearLayout

private val CHEATS: LinkedHashMap<String, List<Pair<String, String>>> = linkedMapOf(
    "Git" to listOf(
        "git init" to "Cria um repositório",
        "git clone <url>" to "Clona um repositório",
        "git status" to "Estado dos arquivos",
        "git add ." to "Adiciona tudo ao stage",
        "git commit -m \"msg\"" to "Cria um commit",
        "git commit --amend" to "Altera o último commit",
        "git push -u origin main" to "Envia e vincula a branch",
        "git pull --rebase" to "Atualiza sem commit de merge",
        "git fetch --all --prune" to "Busca remotos e limpa branches apagadas",
        "git branch -a" to "Lista todas as branches",
        "git switch -c nova" to "Cria e troca de branch",
        "git restore arquivo" to "Descarta alterações do arquivo",
        "git restore --staged arquivo" to "Tira do stage",
        "git stash / git stash pop" to "Guarda e recupera alterações",
        "git log --oneline --graph --all" to "Histórico em árvore",
        "git diff --staged" to "Diferenças que vão no commit",
        "git reset --soft HEAD~1" to "Desfaz commit mantendo alterações",
        "git reset --hard origin/main" to "Descarta tudo e iguala ao remoto",
        "git revert <hash>" to "Cria commit que desfaz outro",
        "git rebase -i HEAD~3" to "Edita os últimos 3 commits",
        "git cherry-pick <hash>" to "Aplica um commit de outra branch",
        "git tag v1.0 && git push --tags" to "Cria e envia tag",
        "git remote -v" to "Mostra remotos",
        "git clean -fd" to "Apaga arquivos não rastreados"
    ),
    "Linux" to listOf(
        "ls -lah" to "Lista detalhada com tamanhos legíveis",
        "find . -name \"*.log\"" to "Procura arquivos por nome",
        "grep -rn \"texto\" ." to "Busca texto recursivamente",
        "tail -f arquivo" to "Acompanha um log ao vivo",
        "chmod +x script.sh" to "Torna executável",
        "chown user:grupo arq" to "Muda o dono",
        "ps aux | grep nome" to "Procura processo",
        "kill -9 PID" to "Mata processo à força",
        "df -h" to "Espaço em disco",
        "du -sh *" to "Tamanho de cada item da pasta",
        "free -h" to "Uso de memória",
        "ss -tulpn" to "Portas em escuta e processos",
        "curl -I https://site.com" to "Só os headers da resposta",
        "tar -czf a.tar.gz pasta" to "Compacta",
        "tar -xzf a.tar.gz" to "Extrai",
        "scp arq user@host:/caminho" to "Copia via SSH",
        "ssh -i chave.pem user@host" to "Conecta com chave",
        "systemctl status serviço" to "Estado de um serviço",
        "journalctl -u serviço -f" to "Logs do serviço ao vivo",
        "ln -s alvo link" to "Link simbólico",
        "sed -i 's/velho/novo/g' arq" to "Substitui texto no arquivo",
        "awk '{print \$1}' arq" to "Imprime a primeira coluna",
        "history | grep termo" to "Procura no histórico"
    ),
    "Docker" to listOf(
        "docker ps -a" to "Lista containers",
        "docker images" to "Lista imagens",
        "docker run -d -p 8080:80 nginx" to "Sobe um container em segundo plano",
        "docker exec -it nome sh" to "Entra no container",
        "docker logs -f nome" to "Logs ao vivo",
        "docker stop nome && docker rm nome" to "Para e remove",
        "docker build -t app:1.0 ." to "Constrói a imagem",
        "docker compose up -d" to "Sobe os serviços",
        "docker compose down -v" to "Derruba serviços e volumes",
        "docker system prune -a" to "Limpa tudo que não está em uso",
        "docker volume ls" to "Lista volumes",
        "docker inspect nome" to "Detalhes em JSON"
    ),
    "SQL" to listOf(
        "SELECT col FROM tabela WHERE x = 1;" to "Consulta básica",
        "SELECT * FROM a JOIN b ON a.id = b.a_id;" to "Junção interna",
        "SELECT col, COUNT(*) FROM t GROUP BY col;" to "Agrupar e contar",
        "... GROUP BY col HAVING COUNT(*) > 1;" to "Filtrar grupos (duplicados)",
        "SELECT * FROM t ORDER BY col DESC LIMIT 10;" to "Ordenar e limitar",
        "INSERT INTO t (a, b) VALUES (1, 'x');" to "Inserir",
        "UPDATE t SET a = 2 WHERE id = 1;" to "Atualizar",
        "DELETE FROM t WHERE id = 1;" to "Apagar",
        "CREATE TABLE t (id INT PRIMARY KEY, nome TEXT);" to "Criar tabela",
        "ALTER TABLE t ADD COLUMN c INT;" to "Adicionar coluna",
        "CREATE INDEX idx ON t (col);" to "Criar índice",
        "EXPLAIN SELECT ...;" to "Plano de execução"
    ),
    "ADB" to listOf(
        "adb devices" to "Lista aparelhos conectados",
        "adb install -r app.apk" to "Instala/atualiza APK",
        "adb uninstall com.pacote" to "Desinstala app",
        "adb logcat" to "Logs do sistema",
        "adb logcat *:E" to "Só erros",
        "adb shell" to "Terminal no aparelho",
        "adb pull /sdcard/arq ." to "Copia do aparelho",
        "adb push arq /sdcard/" to "Copia para o aparelho",
        "adb shell pm list packages" to "Lista pacotes instalados",
        "adb reverse tcp:8081 tcp:8081" to "Redireciona porta do aparelho ao PC",
        "adb tcpip 5555" to "Ativa ADB por Wi-Fi",
        "adb shell screencap -p /sdcard/s.png" to "Captura de tela"
    ),
    "GitHub Actions" to listOf(
        "on: [push, pull_request, workflow_dispatch]" to "Gatilhos do workflow",
        "runs-on: ubuntu-latest" to "Tipo de máquina",
        "- uses: actions/checkout@v4" to "Baixa o código",
        "- uses: actions/setup-java@v4" to "Instala o JDK (with: java-version)",
        "- uses: actions/upload-artifact@v4" to "Publica arquivos (name + path)",
        "- uses: actions/cache@v4" to "Cache de dependências",
        "run: |" to "Bloco de comandos de shell",
        "env:\n  TOKEN: \${{ secrets.MEU_TOKEN }}" to "Usa um segredo",
        "if: failure()" to "Roda só se algo falhou",
        "strategy:\n  matrix:\n    os: [ubuntu-latest, macos-latest]" to "Matriz de execução",
        "needs: build" to "Depende de outro job",
        "timeout-minutes: 15" to "Limite de tempo do job"
    )
)

fun MainActivity.cheatsView(): View {
    val box = vbox()
    val cat = box.add(spinner(CHEATS.keys.toList()), 0)
    val search = box.add(ed("Filtrar (ex.: branch, log, volume)"))
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    fun render() {
        val q = search.text.toString().trim()
        val items = CHEATS[cat.selectedItem.toString()].orEmpty()
            .filter { q.isEmpty() || it.first.contains(q, true) || it.second.contains(q, true) }
        list.removeAllViews()
        if (items.isEmpty()) list.add(tv("Nada encontrado", 13f, GRAY), 8)
        for ((cmd, desc) in items) {
            val row = vbox(10).apply {
                setBackgroundColor(CARD)
                setOnClickListener { copyText(cmd) }
            }
            row.add(tv(cmd, 13f, GREEN, true), 0)
            row.add(tv(desc, 12f, GRAY), 2)
            list.add(row, 4)
        }
    }

    cat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = render()
        override fun onNothingSelected(p: AdapterView<*>?) {}
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) = render()
    })
    box.add(tv("Toque em um comando para copiar", 11f, GRAY), 4)
    box.add(list, 6)
    render()
    return scroll(box)
}
