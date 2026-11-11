# CLAUDE.md

Native Android client (Kotlin, Jetpack Compose, minSdk 29) for the P2P voice call app. It is a port of the
WPF client in `ericoamico/p2p-call` and must stay wire-compatible with it and with the web client
(`client.js`) through the same Socket.IO signaling server. Code comments, UI strings and commit messages
are in Brazilian Portuguese; keep that convention.

## Commands

```bash
./gradlew assembleRelease          # APK em app/build/outputs/apk/release/
./gradlew testReleaseUnitTest      # testes JVM (Sdp, Participant)
```

CI (`.github/workflows/build.yml`) builds on every push and, on `main`, creates release `v1.0.<run>` with
`P2PCall.apk`; `versionCode` = run number (`-PbuildNumber`). The updater reads
`BuildConfig.RELEASES_API` (from `GITHUB_REPOSITORY` in CI).

## Architecture

- **Signaling** (`call/CallManager.kt`): events `join`, `user-joined`, `offer`, `answer`, `ice-candidate`
  (sends both `target` and `to`), `user-disconnected`. All handlers run serialized on a single-thread
  coroutine scope under a `Mutex`. Early ICE candidates are buffered (`earlyCandidates`, `PeerSession.pending`).
  The existing participant offers to each newcomer.
- **Display names**: extra `name` field inside the offer/answer objects (`{type, sdp, name}`), same as desktop.
- **Negotiation**: offers have one audio (mic) + one video transceiver (VP8 preferred, SIPSorcery only does VP8);
  the screen track is set with `sender.setTrack` so sharing needs no renegotiation. On an incoming offer,
  `configureTransceivers` gives the first audio m-line the mic and leaves extra ones (web PC audio) receive-only.
  Renegotiations reuse the existing `PeerConnection`. `Sdp.tuneOpus` adds `stereo=1` and, on remote
  descriptions, `maxaveragebitrate` (= chosen quality); sender `maxBitrateBps` is also set.
- **Audio**: `JavaAudioDeviceModule` with HW AEC/NS off, raw constraints, output as media usage.
  `AudioPipeline` (an `AudioRecordDataCallback`) edits each 10 ms mic buffer: mute zeroes it (the mic only),
  then `SystemAudioCapture` (AudioPlaybackCapture excluding our own UID) is summed in, then the buffer feeds
  `CallRecorder`, which also mixes remote tracks via `AudioTrackSink`.
- **Screen**: `ScreenCapturerAndroid`; Android 14 requires `CallService` to be foreground with type
  `mediaProjection` *before* the projection is used, so the consent result goes through `CallService.shareScreen`.
- **UI** (`ui/CallScreen.kt`): state comes from `CallManager` StateFlows; form/update state in `MainViewModel`.

## Accessibility (must be preserved)

Short labels (no long help texts). Mute/screen/record are `Role.Switch` toggles with fixed labels. Status is a
polite live region. Focus goes to the offending field on error, to "Silenciar microfone" on join and to
"Entrar" on leave. Section titles use `heading()`. Keep touch targets ≥ 56 dp and the visible focus border.
