# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

ACMO is the ACM QU mascot robot's face. It has three parts:
- **`app/`**: an Android tablet app (Kotlin, plain Views plus one custom `View`, no Compose). It shows only a face, listens for "hey ACMO", talks through Gemini Live, and drives the wheel-control board over USB.
- **`remote/`**: a Next.js operator console that POSTs lines for the tablet to speak in an ElevenLabs voice. It is not part of the Gradle build; `settings.gradle.kts` includes only `:app`.
- **`kneedle/`**: an old experiment (natural language → `needle` model → serial letter → Arduino). It is outside the build and refers to `GeminiClient`, which no longer exists. Treat it as dead code unless asked.

`README.md` is detailed and mostly accurate. Two parts are stale:
- Its top diagram and its `gemini/` file list (`GeminiClient.kt`, `Reply.kt`, `Wav.kt`) describe the old transcribe-then-reply pipeline.
- The tuning rows that name `GeminiClient.*` describe the same old pipeline.

The current code uses a Gemini Live WebSocket session instead (see the next section).

## Commands

The Android build needs `JAVA_HOME` pointed at Android Studio's bundled JBR. On Windows, use `gradlew.bat` or `./gradlew` from Git Bash.

```sh
./gradlew :app:assembleDebug                 # first build downloads the 40 MB Vosk model (downloadVoskModel, a preBuild dependency)
./gradlew :app:testDebugUnitTest             # JVM unit tests (app/src/test)
./gradlew :app:testDebugUnitTest --tests com.acmqu.acmo.TagsTest   # a single test class
./gradlew :app:installDebug                  # tablet on adb (usually wireless debugging)
adb logcat -s Brain MicPipeline RobotLink    # the useful log tags

cd remote && npm install && npm run dev      # console at http://localhost:3000
cd remote && npm run lint                    # eslint + scripts/check-words.mjs
adb forward tcp:8765 tcp:8765                # lets the console reach the tablet's server at localhost:8765
```

Secrets are read from `local.properties` (gitignored) or the environment, then baked into `BuildConfig`: `GEMINI_API_KEY`, `ELEVENLABS_API_KEY`, and optionally `ELEVENLABS_VOICE_ID`. Without the ElevenLabs key, `eleven` is null: Gemini's own voice is used and the console's `/say` returns 503. Built APKs contain the keys.

The Vosk model lives in `app/src/main/assets/model-en-us/` (gitignored). Delete that folder to re-download it. The `uuid` file in it is how the app decides whether to unpack the model again on the device.

## Architecture

**`Brain.kt` is the core.** It is one state machine on the main thread: `BOOTING → IDLE → LISTENING → THINKING → SPEAKING → IDLE`. `MainActivity` builds and wires everything: `FaceView`, `Speaker`, `ElevenLabs`, `RobotLink`, `Brain`, `RemoteServer`, and the settings card (opened with five taps in the top-left corner). `Brain` implements both `MicPipeline.Listener` and `RemoteServer.Host`.

**Conversation path (Gemini Live):**
1. `MicPipeline` runs one mic thread with three modes: `WAKE` (Vosk grammar from `WakeWord`), `STREAM`, and `PAUSED`. Hearing the wake word flips it to `STREAM` without dropping a chunk. In `STREAM`, 16 kHz PCM goes to `mic.sink`, which is `LiveSession.sendAudio`.
2. `LiveSession` is the WebSocket to Gemini Live. Its setup message is built by `Personality.setup()`:
   - It holds the model id, the voice, and the system prompt `Personality.SYSTEM`.
   - It also sets server-side end-of-speech VAD.
   - The model calls the `set_faces` tool once per answer, with one `Expression` per sentence; the tool's enum is built from `Expression.entries`.
   - The session's resumption handle keeps memory across exchanges for `Brain.MEMORY_MS`.
   - Listener callbacks arrive on the main thread, except `onAudio`, which runs on the socket thread.
3. `Brain.Reply` (an inner class) holds Gemini's own audio back:
   - As the output transcript completes each sentence (`Sentence.whole`), that sentence goes to ElevenLabs, and the pieces play back to back through one `AudioOut`.
   - If ElevenLabs fails or there is no transcript, `fallBack` plays Gemini's held audio instead.
   - Faces change at sentence boundaries.
4. `Speaker` (Android TTS) is used only for apologies when the session itself cannot talk. Errors show as a sad face plus spoken audio, because the screen shows only the face.

**Console path:** `RemoteServer` is a NanoHTTPD server on port 8765 with three routes:
- `POST /say {text, feeling, now}` queues a line; `now` cuts off whatever is playing.
- `POST /stop` clears the queue.
- `GET /state` reports what ACMO is doing.

Each request thread hops to the main thread through `onMain` and waits for the result, so `Brain` stays main-thread-only. Lines are spoken word for word by `ElevenLabs`, with no Gemini involved. A line sent during a wake-word conversation waits for it to end, unless `now` is set. There is no auth.

**Audio and timing.** Everything is keyed to byte offsets in a 24 kHz mono 16-bit PCM stream:
- `AudioOut` plays the chunks and fires *cues* on the main thread once the playback head passes a byte offset. Face changes and mouth movement both run on these cues, so they land when the sound is heard, not when it arrives.
- `MouthGate` turns RMS per 20 ms window into mouth open/close/size.
- `VoiceEffect` is a ring modulator for the robotic timbre. It must never add or drop samples, or the cues drift out of sync.
- `FaceCues` maps ElevenLabs' character timing to "this face at this byte".

**Faces:** `face/Expression.kt` is the single source of truth. A new enum row automatically becomes a face that `FaceView` draws and that the model's schema and the console's tags can use. `FaceView` draws on a Canvas using the design's units: a 16:10 stage measured in 1 % of its width, with glyphs in JetBrains Mono Bold.

**Robot link:** `RobotLink` is a USB host that writes to the board's CDC-ACM bulk endpoint at 115200 8N1:
- On each face change it writes the expression label plus `\n`. The same face twice in a row is not sent again.
- It is write-only and best-effort; a background reader drains the board's output.
- The manifest's `device_filter.xml` matches the Arduino vendor id `0x2341`.
- The board firmware lives in a separate hardware repo (`hardware/wheel-control`).

## Gotchas

- **Tag words exist in two places.** `app/.../remote/Tags.kt` (`EXTRAS`) is the source of truth; `remote/lib/acmo.ts` (`FEELINGS`, `TAG_WORDS`) mirrors it. `npm run lint` fails when they disagree, so change both. A line containing any `[tag]` goes to `eleven_v3`; a plain line goes to `eleven_flash_v2_5`.
- **Unit tests run on the JVM.** `unitTests.isReturnDefaultValues = true` makes `android.util.Log` a no-op, and a real `org.json` replaces Android's stub. `RemoteServer` and `ElevenLabs` are tested against `MockWebServer`. Keep new logic testable this way by pulling pure functions out of Android classes, as `Tags`, `Line`, `FaceCues`, `MouthGate`, `LiveMessage`, and `WakeWord` already do.
- **Toolchain is pinned** for Android Studio 2025.2: AGP 8.13.2, Kotlin 2.2.21, compile/target SDK 36, min SDK 24, Java 17. Do not bump AndroidX to versions that need SDK 37 or AGP 9. All versions are in `gradle/libs.versions.toml`.
- **`jserialcomm` is unused.** It is a declared dependency, but nothing in `app/` uses it; `RobotLink` uses Android's `UsbManager` directly.
- **Comment style is prose.** Code comments are written as sentences that explain *why*, often at length. Match that style when editing.
- **Design history** lives in `docs/superpowers/specs/` and `docs/superpowers/plans/`, covering the ElevenLabs console and audio tags.
