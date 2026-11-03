# DNS Switch VPN

Troca o DNS por IP (8.8.8.8, 1.1.1.1...) sem root e sem PC, usando uma VPN local que só captura consultas DNS.
4 atalhos editáveis na barra de puxar do topo.

## Compilar
1. Crie um repositório no GitHub e envie todos estes arquivos (incluindo a pasta .github).
2. Aba **Actions** > **Build APK** > *Run workflow* (também roda a cada push).
3. Baixe o APK em **Artifacts** > `DnsSwitchVpn-apk`.

## Uso
Abra o app, edite os 4 atalhos, toque em "Ativar este DNS" (autorize a VPN na 1a vez).
Depois adicione "DNS 1".."DNS 4" no painel rápido.
Observação: se o "DNS privado" do Android estiver em Automático/Desligado, este app funciona normalmente.
Se estiver com um hostname fixo, o sistema usa esse e ignora a VPN.
