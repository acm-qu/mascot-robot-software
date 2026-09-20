# ACMO — the face

The mascot robot's face: an Android tablet app that shows exactly one thing, a
face, and talks. Say **"hey ACMO"**, it perks up and listens; say what you want;
it thinks with Gemini and answers out loud, acting out every sentence with one of
eight expressions.

```
 microphone ─▶ Vosk, on the tablet ──"hey ACMO"──▶ record until you stop talking
                                                              │ WAV
                                                              ▼
                              gemini-3.5-transcribe ──text──▶ gemini-3.5-flash-lite
                                                                        │ {segments:[{feeling,text},…]}
                                                                        ▼
                                      the face shows `feeling` while Android TTS says `text`
```

Nothing but the face is ever on screen. Five taps in the top-left corner open a
settings card (light/dark, conversation, dev mode, remote console, volume,
brightness, primary colour); a tap anywhere else previews the next expression.

## What's here

```
software/
├── remote/                      the operator's console: a Next.js page, not part of the Gradle build
├── app/src/main/java/com/acmqu/acmo/
│   ├── MainActivity.kt          the one screen: kiosk mode, mic permission, the tap gestures
│   ├── Brain.kt                 the state machine: IDLE → LISTENING → THINKING → SPEAKING → IDLE
│   ├── face/
│   │   ├── FaceView.kt          the face, drawn on a Canvas; blink, gaze, bob, glyph swaps, speaking mouth
│   │   ├── Expression.kt        the eight expressions: eye poses + glyphs, ported from the design
│   │   └── FaceTheme.kt         light/dark palettes; the accent is the "primary colour"
│   ├── voice/
│   │   ├── MicPipeline.kt       one mic thread: wake-word listening, then prompt capture with endpointing
│   │   ├── WakeWord.kt          the Vosk grammar of "ACMO" sound-alikes and the trigger rule
│   │   ├── ModelInstaller.kt    unpacks the Vosk model from assets on first run
│   │   ├── Speaker.kt           Android text-to-speech as one suspending call per sentence
│   │   ├── ElevenLabs.kt        the console's voice: ElevenLabs text-to-speech, streamed as 24 kHz PCM with timing
│   │   └── Wav.kt               the 44-byte header Gemini wants
│   ├── gemini/
│   │   ├── GeminiClient.kt      transcribe() and reply(), both over the Interactions API
│   │   ├── Personality.kt       who ACMO is (the system instruction) and the JSON schema of a reply
│   │   └── Reply.kt             Segment(feeling, text) and the parser
│   ├── settings/
│   │   ├── Settings.kt          SharedPreferences: theme, conversation, dev mode, remote, swatch, brightness
│   │   └── SettingsPanel.kt     the card's controls
│   └── remote/
│       ├── RemoteServer.kt      the console's way in: POST /say, /stop and GET /state on port 8765
│       ├── Line.kt              a line and its feeling; the wire types
│       ├── Tags.kt              which [words] name a face, and where they sit in a line
│       └── FaceCues.kt          from ElevenLabs' character timing to "this face at this byte"
├── app/src/main/res/
│   ├── layout/                  activity_main (the face + hidden card), view_settings (the card)
│   ├── font/                    JetBrains Mono Bold, the glyph face
│   └── values/, drawable/, mipmap-*/
├── app/src/main/assets/model-en-us/   the Vosk model — downloaded at build time, not in git
├── app/src/test/                unit tests for the reply parser, the wake trigger and the WAV header
├── app/build.gradle.kts         the API keys from local.properties into BuildConfig; the model download task
├── gradle/libs.versions.toml    every version pin
└── NOTICE.md                    third-party licenses
```

## Setup

1. **Open `software/` in Android Studio** (2025.2 or newer) and let it sync.
2. **Gemini key.** Add one line to `local.properties` (gitignored — it never
   reaches the repo):

   ```
   GEMINI_API_KEY=AIza...
   ```

   It is baked into the APK as `BuildConfig.GEMINI_API_KEY`, so **do not pass
   built APKs around.** Get a key from [Google AI Studio](https://aistudio.google.com/).

   For the remote console (below), two more lines — the voice id is optional:

   ```
   ELEVENLABS_API_KEY=sk_...
   ELEVENLABS_VOICE_ID=cgSgspJ2msm6clMCkdW9
   ```

   Create that key restricted to text-to-speech, with a credit cap: an ElevenLabs
   key is billable, and this one ends up inside the APK too. Without the key the
   app runs as before and the console's sends answer `503`.
3. **Build.** The first build downloads the 40 MB Vosk model into
   `app/src/main/assets/model-en-us/` (the `downloadVoskModel` task; delete the
   folder to re-fetch). From a terminal:

   ```sh
   export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
   ./gradlew :app:assembleDebug :app:testDebugUnitTest
   ./gradlew :app:installDebug        # with the tablet on adb
   ```

   Set up **wireless debugging** on the tablet (*Developer options → Wireless
   debugging*, then `adb pair` / `adb connect`) — it will be mounted in a robot.
4. **First run.** Allow the microphone when asked. The first launch unpacks the
   model (a few seconds, face idle). Make sure the tablet has a text-to-speech
   voice installed — *Settings → System → Languages → Text-to-speech*; Google's
   engine has English and Arabic.
5. **Kiosk.** The app keeps the screen on and hides the system bars; pin it with
   Android's screen pinning or a kiosk launcher for real deployments.

## Using it

- **"hey ACMO"** — pause — then talk. The face turns excited the moment it hears
  its name and stays excited while you speak and while it thinks. Stop talking
  for a second and it sends what it heard.
- It answers in one to four sentences, each with a feeling on its face, in the
  language you spoke (English or Arabic). It remembers the conversation for ten
  minutes, so follow-ups work ("what's my name?").
- Nothing heard, or nothing said within four seconds: a surprised look, then
  back to idle. An API or network failure: a sad face and a spoken apology — the
  face is the only screen, so the voice is the error channel.
- **Tap the face** (while idle) to preview the next expression; it reverts after
  four seconds.
- **Five taps in the top-left corner** within 2.5 s open settings. **Theme** is
  the face's own light/dark switch. **Conversation** is the wake word and Gemini:
  switch it *Off* for a scripted show, and ACMO ignores its name (the microphone
  stays paused, typed prompts are ignored, a conversation in progress is cut
  short) while the remote console keeps working. **Volume** is the tablet's media volume,
  which is what the voice uses. **Brightness** is this window's. **Primary
  colour** recolours the cheeks, badge and tear — the brand teal by default,
  then the department colours from the ACM QU design system.
- **Dev mode** (in settings) puts a text box and a mic button along the bottom
  of the face. Type a prompt and press *Send* on the keyboard: it goes straight
  to the model, no transcription. Tap the mic: ACMO listens right away, no wake
  word, and stops when you stop talking — or tap the mic again to send what it
  has so far. The button is filled while it listens and dimmed while ACMO is
  thinking or talking. Everything else — the face, the voice, the memory — is
  the same path the wake word takes.

## Remote console

`remote/` is a small Next.js page for a laptop on the same network: type a
line, pick one of the eight faces, and ACMO says it in an ElevenLabs voice.
The text is spoken word for word — Gemini is not involved — and streamed:
ElevenLabs returns raw 24 kHz PCM, with the moment each character is spoken,
and it goes straight into the same player as Gemini's voice. Measured on the
Redmi Pad 2: the first sound comes 0.4 s after Enter on a warm connection with
the fast model, and about 1.5 s for the first line after a few minutes' quiet.

```sh
adb forward tcp:8765 tcp:8765           # or type the address from the settings card into the page
cd remote && npm install && npm run dev # http://localhost:3000
```

The tablet listens on port 8765 — **Remote console** in the settings card, on
by default, with the address underneath:

| Route | Body | Reply |
| --- | --- | --- |
| `POST /say` | `{"text": "…", "feeling": "happy", "now": false}` | `{"id": 7, "queued": 0}` — `now` cuts off whatever is playing, otherwise the line waits its turn |
| `POST /stop` | | `{"ok": true}` — be quiet, forget the queue |
| `GET /state` | | `{"state", "face", "line", "queue", "error"}` — `face` is the expression on screen right now |

Lines play back to back with the chosen face; a line ElevenLabs cannot deliver
gets a sad face for a moment and the queue goes on. A line sent while ACMO is
in a wake-word conversation waits for it to end; *Say now* ends it.

**Tags.** A word in square brackets is an ElevenLabs v3 *audio tag*: direction
for the voice, not text to say — `[whispers] Come closer. [laughs] Got you!`
whispers, then laughs. A line with any tag goes to the expressive `eleven_v3`
model, which starts about a second later than `eleven_flash_v2_5`; a plain line
stays on Flash. A tag that names a face changes the face too, at the moment the
voice gets there, so the face pill is only the face until the first one. Each
face answers to its name and three more words (the row under the pills in the
console; click one to insert it):

| Face | Words |
| --- | --- |
| happy | happy, laughs, giggles, cheerful |
| sad | sad, crying, gloomy, disappointed |
| angry | angry, shouting, furious, growls |
| annoyed | annoyed, sarcastic, groans, frustrated |
| surprised | surprised, gasps, shocked, amazed |
| excited | excited, thrilled, enthusiastic, energetic |
| passionate | passionate, loving, romantic, dramatic |
| idle | idle |

Anything else in brackets — `[sighs]`, `[pause]`, `[excited whisper]` — changes
the tone only. Every tag is sent to ElevenLabs as typed.

**There is no authentication:** anyone on the Wi-Fi can make ACMO talk while
this is on. Switch it off in the settings card at a venue you do not trust.

## The reply format

The model does not answer in prose. It is asked for JSON, and the Interactions
API's `response_format` enforces a schema in which every segment's `feeling` is
an enum built from `Expression` — so the face can always show what the model
picked, and a typo or an invented feeling cannot reach the screen:

```json
{ "language": "en",
  "segments": [ { "feeling": "excited", "text": "Hi Hakim! Nice to meet you!" },
                { "feeling": "happy",   "text": "Why do robots never panic?" },
                { "feeling": "passionate", "text": "Nerves of steel!" } ] }
```

`Brain` walks the segments: set the face, speak the sentence, next. The speaking
mouth animation is driven by the speech engine's start/finish callbacks, so it
moves exactly while there is sound.

The personality lives in `Personality.SYSTEM` — edit it there. Two rules in it
matter more than the rest: no emoji or markdown (everything is read aloud), and
short segments (a segment is one face, and a face should change every sentence
or two).

## The face

A port of the *Interactive robot face demo* Claude Design project, drawn
natively in `FaceView` rather than in a WebView. The design's numbers are kept
as they are: a 16:10 stage measured in units of 1 % of its width, eyes at
(36, 30) and (64, 30) that are 14 × 20 rounded rectangles, and seven glyph
slots in JetBrains Mono Bold — brows, cheeks, mouth, badge, tear.

| Expression | Eyes | Glyphs |
| --- | --- | --- |
| `idle` | round, level | `_` |
| `surprised` | wide, lifted | `o` mouth, `!` badge |
| `sad` | squashed, tilted in, lowered | `/ \` brows, `(` mouth turned 90°, `;` tear |
| `happy` | thin arcs | `> <` cheeks, `)` mouth turned 90° |
| `angry` | squashed, tilted out | `\ /` brows, `#` mouth |
| `passionate` | soft, tilted | `<3` badge that pulses, `)` mouth |
| `annoyed` | one eye narrower, both shifted right | `~` mouth |
| `excited` | tall, whole face bobbing | `> <` cheeks, `D` mouth turned 90° |

Changing expression works as in the design: the eyes ease to the new pose over
600 ms; glyphs that differ shrink away, swap after 220 ms and pop in with an
overshoot (the sad and idle faces settle gently instead). The eyes blink every
2.4–5.6 s and glance around every 1.8–4.4 s. While speaking, the mouth cycles
`_ o O o - O o = O _ o -` at a jittered ~110 ms.

Everything is drawn from `Expression` — add a row there and it is a new face the
model can use (the enum in the schema is built from the same list).

## Tuning

| Where | What | Default |
| --- | --- | --- |
| `WakeWord.VARIANTS` | The sound-alikes the wake-word grammar accepts. Watch `adb logcat -s MicPipeline` for `wake heard:` lines and add what your voice produces. | `ack mo, ak mo, ac mo, hack mo, back mo, act mo, acme` |
| `WakeWord.acceptBareMo` | Also wake on a bare `mo`. More wakes, and the odd false one ("tell me more"). | `false` |
| `MicPipeline.NO_SPEECH_MS` | How long after the wake word speech must begin. | 4000 |
| `MicPipeline.END_SILENCE_MS` | Quiet that ends the prompt. | 900 |
| `MicPipeline.MAX_PROMPT_MS` | Longest prompt. | 12000 |
| `MicPipeline.MIN_THRESHOLD` / `THRESHOLD_GAIN` | Speech is `GAIN ×` the ambient RMS, never below `MIN`. Raise `MIN` in a loud room; lower it if the tablet's mic is quiet. | 400 / 3.5 |
| `GeminiClient.THINKING_LEVEL` | `minimal`, `low`, `medium`, `high`. Minimal answers in about a second. | `minimal` |
| `GeminiClient.MAX_OUTPUT_TOKENS` | Cap on a reply. | 600 |
| `Brain.MEMORY_MS` | How long a conversation is remembered. | 10 min |
| `FaceView.speakSpeedMs`, `eyeSize`, `blinkEnabled` | The design's props. | 110, 1.0, true |
| `Speaker` pitch / rate | `setPitch(1.1f)`, `setSpeechRate(1.0f)` in its init. | |
| `ElevenLabs.FAST_MODEL` / `EXPRESSIVE_MODEL` | The model for a plain line and for one with a `[tag]`. Flash starts in under half a second and reads a tag out loud; v3 takes it as direction and starts about a second later. | `eleven_flash_v2_5` / `eleven_v3` |
| `Tags.WORDS` | The bracketed words that change the face. Add a synonym here, and to `TAG_WORDS` in `remote/lib/acmo.ts` so the console shows it. | the table above |
| `ElevenLabs.DEFAULT_VOICE_ID` | The voice when `ELEVENLABS_VOICE_ID` is not set. Any id from ElevenLabs' `GET /v1/voices`. | Jessica |
| `RemoteServer.PORT` | Where the tablet listens for the console. | 8765 |

## Known limits

- **The wake word is approximate.** The on-device model cannot hear "ACMO" as a
  word — it is not in its vocabulary — so it listens for sound-alikes. Measured
  on synthesised speech from six voices: **"hey ACMO" woke 11 of 18 tries, and 24
  decoy phrases woke 0.** A bare "ACMO" with no "hey" is worse. Say the name,
  pause, then talk; do not run them together.
- **Internet is required** for every exchange (transcription and the reply both
  go to Gemini). The wake word is the only offline part.
- **The API key is inside the APK.** Fine on your own tablet; not for sharing.
- **Arabic** replies need an Arabic TTS voice on the tablet; without one the
  default voice reads them.
- **The wheels are not wired in yet.** The ESP32 firmware in the hardware repo
  already serves `GET /move?name=spin_right` on its own Wi-Fi network; calling
  it from `Brain` when a feeling warrants a move is the natural next step.
- The remote console and the Live conversation were exercised on the Redmi Pad 2
  on 2026-09-20; nothing has yet been left running long enough to see whether
  HyperOS kills the microphone thread.

## Toolchain

Pinned for Android Studio 2025.2 and the installed SDK — AGP 8.13.2, Gradle
8.14.5, Kotlin 2.2.21, `compileSdk` 36, `minSdk` 24. Newer AndroidX releases
require `compileSdk` 37 and AGP 9, which that Studio does not support. Plain
Views and a custom `View`; no Compose.

## License

MIT — see `LICENSE`. Third-party components are listed in `NOTICE.md`.
