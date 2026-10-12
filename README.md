# Monitor de Rede (Android)

App que mostra, em tempo real, quais apps do celular estao se conectando a quais
servidores (IP, dominio, porta) e quantos bytes enviam/recebem.

Funciona criando uma VPN local no proprio aparelho (VpnService). Nada sai do celular:
o trafego e apenas encaminhado e contado. Nao precisa de root.

## Compilar no GitHub Actions
1. Crie um repositorio no GitHub e envie todos estes arquivos (incluindo a pasta `.github`).
2. Abra a aba **Actions**; o workflow "Build APK" roda sozinho a cada push
   (ou clique em **Run workflow**).
3. Quando terminar, abra a execucao e baixe o artefato **NetMonitor-debug-apk**
   (um .zip com o `app-debug.apk` dentro).
4. Copie o APK para o celular e instale (permita "instalar apps desconhecidos").

## Usando
Abra o app > **Iniciar monitor** > aceite o pedido de VPN. A lista atualiza a cada segundo.

## Limitacoes
- So IPv4 (trafego IPv6 nao passa pela VPN e nao aparece).
- HTTPS: voce ve destino, porta e volume, nao o conteudo.
- Nome do app dono da conexao exige Android 10 ou mais novo.
- "DNS privado" do Android pode esconder os nomes de dominio (veja so o IP).
- Ping (ICMP) nao e encaminhado.
