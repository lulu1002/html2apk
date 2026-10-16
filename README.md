# WiFi Scout

App Android (Kotlin, sem dependências externas) para achar o melhor ponto de Wi-Fi do cômodo.

- **Sinal**: RSSI em dBm em tempo real (atualiza a cada 0,3 s), barra de qualidade, gráfico e dados da rede (banda, velocidade do link).
- **Melhor local**: mede 5 s parado em cada ponto, tira a média, salva com um nome e monta o ranking.
- **Speedtest**: latência, download e upload usando os servidores da Cloudflare.

## Gerar o APK no GitHub Actions

1. Crie um repositório no GitHub e suba todo o conteúdo desta pasta (inclusive `.github`).
2. Vá em **Actions → Build APK** (roda sozinho a cada push, ou clique em *Run workflow*).
3. Ao terminar, baixe o artefato **WifiScout-debug-apk** no fim da página da execução e descompacte o `app-debug.apk`.
4. No celular, permita instalar apps de fontes desconhecidas e instale o APK.

O APK é de debug (assinado automaticamente com a chave de debug), suficiente para uso pessoal.

## Observações

- A permissão de localização serve só para o Android liberar o nome da rede (SSID); o RSSI funciona sem ela.
- Ligue o GPS do celular se o nome da rede aparecer como "Rede conectada".
