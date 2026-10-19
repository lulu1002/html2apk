# WG Tunnel

Cliente VPN WireGuard para Android, simples e inspirado no app oficial (`com.wireguard.android`).
Usa a biblioteca oficial `com.wireguard.android:tunnel` (wireguard-go embutido).

## Recursos
- Lista de túneis com chave liga/desliga e contadores de tráfego (↓/↑) ao vivo
- Importar arquivo `.conf` ou criar túnel do zero (gera a chave privada e mostra a pública)
- Editar, exportar e excluir (toque longo no túnel abre o menu)
- Atalho nas configurações rápidas (tile) e widget na tela inicial para ligar/desligar o último túnel usado
- Tema claro/escuro automático

## Como compilar no GitHub Actions
1. Crie um repositório no GitHub e envie esta pasta (`git init && git add . && git commit -m init && git push`).
2. Abra a aba **Actions** → workflow **Build APK** (roda sozinho a cada push, ou use *Run workflow*).
3. Ao terminar, baixe os artefatos `wg-tunnel-debug` ou `wg-tunnel-release` (o `.apk` está dentro do zip).

O APK de release é assinado com a chave de debug, então instala direto. Para publicar de verdade, use uma keystore sua.

## Compilar localmente
Precisa de JDK 17, Android SDK e Gradle 8.9+: `gradle assembleDebug`.

## Não incluído (comparado ao app oficial)
Importar por QR code, iniciar no boot, split tunneling por app, backend de kernel/root.
