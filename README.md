# P2P Call — Android

App Android nativo (Kotlin + Jetpack Compose) para chamadas de voz P2P com compartilhamento de tela.
Usa o mesmo protocolo de sinalização Socket.IO do cliente web e do [cliente desktop](https://github.com/ericoamico/p2p-call)
(`join`, `user-joined`, `offer`, `answer`, `ice-candidate`, `user-disconnected`), então participantes
web, desktop e Android se misturam na mesma sala.

## Recursos

- Áudio P2P via WebRTC nativo (libwebrtc), Opus estéreo com FEC, sem cancelamento de eco, supressão
  de ruído ou AGC (áudio cru, como no web e no desktop)
- Escolha do microfone (aparelho, fone com fio, USB; troca durante a chamada) e qualidade de envio (32–510 kbps)
- Silenciar microfone (também **Ctrl+M** com teclado físico)
- **Nome de exibição** opcional (até 40 caracteres) e lista **Na sala**; entradas e saídas são anunciadas pelo nome
- **Link da sala** no formato da versão web (`https://servidor/?sala=nome`): "Copiar link" copia;
  colar um link no campo Sala deixa só o nome e ajusta o servidor; links `https://voz.tempsharing.com.br/?sala=…`
  abrem direto no app
- **Gravação** da chamada (você + áudio do aparelho + participantes) em WAV, salva em `Música/P2PCall`
- **Compartilhar tela** em VP8 (até 1600 px, 12 fps) para participantes desktop/Android. As telas recebidas
  aparecem em lista (toque para ampliar) e somem 3 s depois que o participante para
- **Áudio do aparelho** junto com a tela: captura o áudio dos outros apps *excluindo* este, então as vozes
  da chamada não voltam para ninguém. É somado à trilha do microfone (chega a todos, inclusive web,
  sem renegociação) e continua indo com o microfone silenciado. Apps que bloqueiam captura não são incluídos
- Chamada continua em segundo plano e com a tela apagada (serviço em primeiro plano com botão "Sair" na notificação)
- **Atualização automática**: ao abrir, compara a versão com a última release deste repositório; "Atualizar"
  baixa o APK (conferindo tamanho e SHA-256) e abre o instalador do Android

Requer Android 10 ou mais recente.

## Acessibilidade

Pensado para TalkBack e teclado, com textos curtos:

- Todos os campos têm rótulo; erros aparecem no próprio campo, que recebe o foco
- Silenciar, Compartilhar tela e Gravar são chaves: o TalkBack anuncia "ativado"/"desativado"
- O status é uma região ao vivo: entradas, saídas, telas compartilhadas e gravação salva são anunciadas sem mover o foco
- O foco vai para "Silenciar microfone" ao entrar e para "Entrar" ao sair
- Seções com títulos ("Na sala", "Telas") para navegar por cabeçalhos
- Alvos de toque de 56 dp, anel de foco visível e contraste acima de 4,5:1

## Download

Cada push gera o APK pelo GitHub Actions (`.github/workflows/build.yml`), disponível como artefato da
execução. Na `main`, também é publicada uma release `v1.0.N` com o `P2PCall.apk` — baixe em
[Releases](../../releases/latest). Depois disso o próprio app avisa das novas versões.

Todas as builds são assinadas com a mesma chave (`app/release.keystore`), para que a atualização instale
por cima. Para usar uma chave própria, crie os secrets `P2PCALL_KEYSTORE_BASE64`, `P2PCALL_KEYSTORE_PASSWORD`,
`P2PCALL_KEY_ALIAS` e `P2PCALL_KEY_PASSWORD` (trocar a chave exige reinstalar o app uma vez).

## Compilar

```bash
./gradlew assembleRelease
```

O APK fica em `app/build/outputs/apk/release/app-release.apk`. Testes: `./gradlew testReleaseUnitTest`.

## Estrutura

| Pasta | Conteúdo |
| --- | --- |
| `call/` | `CallManager` (sinalização + uma `PeerConnection` por participante), `PeerSession`, `Sdp`, `RoomLink` |
| `audio/` | `AudioPipeline` (mute, soma do áudio do aparelho), `SystemAudioCapture`, `CallRecorder` |
| `ui/` | Tela em Compose e componentes acessíveis |
| `update/` | `Updater` (releases do GitHub + PackageInstaller) |
| `CallService.kt` | Serviço em primeiro plano (microfone / captura de tela) e wake lock |
