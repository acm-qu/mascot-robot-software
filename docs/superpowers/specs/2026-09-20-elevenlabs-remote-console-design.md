# ElevenLabs remote console — design

*2026-09-20. Approved in conversation; this is the written form.*

A browser page on the operator's laptop where you type a line and pick a face;
the tablet says the line in an ElevenLabs voice, streamed so the first sound
comes as early as possible, with the face acting it out. ElevenLabs is only the
voice: the text is spoken word for word, Gemini is not in the loop. The wake-word
conversation (Gemini Live) stays exactly as it is.

```
 browser (software/remote, Next)          tablet (ACMO app)                      ElevenLabs
 ┌───────────────────────────┐  LAN /    ┌──────────────────────────────┐  TLS  ┌──────────────┐
 │ face picker · text box    │  adb fwd  │ RemoteServer :8765 (NanoHTTPD)│ ────▶ │ /stream       │
 │ [Queue] [Say now] [Stop]  │ ────────▶ │   └▶ Brain.say(line, now)     │ ◀──── │ pcm_24000     │
 │ status: what's playing,   │ ◀──────── │        queue → ElevenLabs →   │ chunks│ flash_v2_5    │
 │ the queue, last error     │  /state   │        AudioOut → face+mouth  │       └──────────────┘
 └───────────────────────────┘  500 ms   └──────────────────────────────┘
```

Decisions taken during the design, in one place:

| Question | Decision |
| --- | --- |
| What ElevenLabs does | Text-to-speech of the typed text, verbatim. |
| A line arrives while ACMO is busy | Two buttons: **Queue** (plays after what is playing) and **Say now** (cuts it off). Plus **Stop**. |
| Which face | The operator picks one of the eight expressions per line; default `happy`. |
| Transport | The tablet hosts a small HTTP server; the browser calls it directly. |
| ElevenLabs API | HTTP streaming endpoint, raw 24 kHz PCM into the existing `AudioOut`. |
| Security | None for now ("make it work first"). An Off/On toggle exists, default **on**. |
| Voice | `eleven_flash_v2_5`; default voice *Jessica* (`cgSgspJ2msm6clMCkdW9`), overridable. |
| Commits | The uncommitted Gemini Live migration is committed first, on its own. |

## 1. The wire protocol

The tablet listens on **`0.0.0.0:8765`** (`RemoteServer.PORT`). JSON in, JSON
out, `Content-Type: application/json`. Every response carries
`Access-Control-Allow-Origin: *`, `Access-Control-Allow-Methods: GET, POST, OPTIONS`
and `Access-Control-Allow-Headers: Content-Type`; an `OPTIONS` request to any
route gets `204` with those headers, which is what lets a page on
`localhost:3000` call it.

| Route | Request | Response |
| --- | --- | --- |
| `POST /say` | `{"text": "Hi!", "feeling": "happy", "now": false}` | `200 {"id": 7, "queued": 2}` |
| `POST /stop` | *(no body)* | `200 {"ok": true}` |
| `GET /state` | | `200 {"state": …, "line": …, "queue": […], "error": …}` |
| `GET /` | | `200` `text/plain`: one paragraph saying what this is and listing the routes. |
| anything else | | `404 {"error": "no such route"}` |

**`/say`.** `text` is trimmed; empty or longer than 2 000 characters
(`Line.MAX_CHARS`) is `400 {"error": "…"}`. `feeling` is one of the eight
`Expression` labels — `idle surprised sad happy angry passionate annoyed excited`
— matched case-insensitively; missing means `happy`, unknown is `400`. `now`
defaults to `false`. With `now:false` the line goes to the back of the queue and
starts at once if ACMO is idle; with `now:true` it cuts off whatever is playing
(a remote line or a wake-word conversation) and starts immediately, and the rest
of the queue follows it. `id` is a counter per app run. `queued` is how many
remote lines are ahead of it: the one playing plus the ones waiting, so `0`
means it plays as soon as ACMO is free (immediately, unless a conversation is in
flight). A body that is not JSON is `400`. When no ElevenLabs key is built in,
`503 {"error": "no ElevenLabs key: add ELEVENLABS_API_KEY to local.properties and rebuild"}`.
If the main thread does not answer within 2 s, `503 {"error": "the app is not responding"}`.

**`/stop`.** Be quiet: cancels whatever is playing — a remote line *or* a Gemini
reply — drops the queue, and goes idle. Also cuts off a conversation that is
still listening or thinking.

**`/state`.**

```json
{ "state": "speaking",
  "line":  { "id": 7, "text": "Welcome to the ACM booth!", "feeling": "happy" },
  "queue": [ { "id": 8, "text": "But I'm out of batteries.", "feeling": "sad" } ],
  "error": { "id": 6, "message": "ElevenLabs 401: Invalid API key" } }
```

`state` is `Brain.State` in lower case: `booting`, `idle`, `listening`,
`thinking`, `speaking`. `line` is the remote line being spoken, or `null` — so
`speaking` with a `null` line is a Gemini reply (or the sad pause after a failed
line, §2.4). `queue` is what is waiting, in order. `error` is the most recent
failed line and why, or `null`; it is cleared when the next line *starts*.

## 2. The tablet

### 2.1 `remote/RemoteServer.kt`

A `NanoHTTPD` subclass (`org.nanohttpd:nanohttpd:2.3.1`, no transitive
dependencies at runtime). NanoHTTPD serves each request on its own thread; the
server parses and validates there, then does the one call into the app on the
main thread and waits for its answer:

```kotlin
class RemoteServer(
    private val port: Int,
    private val host: Host,
    /** Runs a block on the main thread. Tests pass `{ it() }`. */
    private val onMain: (() -> Unit) -> Unit,
    private val hasKey: Boolean,
) : NanoHTTPD(port) {
    interface Host {
        fun say(line: Line, now: Boolean): Said          // Said(id, queued)
        fun hush()
        fun snapshot(): Snapshot
    }
}
```

The wait is a `CountDownLatch` with a 2 s timeout (→ `503`). `Brain` implements
`Host` and stays main-thread-only, as it is today. `start()` / `stop()` are
idempotent; a port already in use is logged and reported as a failure to start
(the settings card shows "could not listen on :8765").

### 2.2 `remote/Line.kt`

```kotlin
data class Line(val text: String, val feeling: Expression)
data class Entry(val id: Int, val line: Line)
data class Said(val id: Int, val queued: Int)
data class Failure(val id: Int, val message: String)
data class Snapshot(val state: Brain.State, val line: Entry?, val queue: List<Entry>, val error: Failure?)
```

`Line.parse(json: String): Line` implements the `/say` rules above and throws
`IllegalArgumentException` with the message that becomes the `400` body.
`Snapshot.toJson()` / `Said.toJson()` produce the wire form. All pure; all
tested on the JVM (the real `org.json` is already a test dependency).

### 2.3 `voice/ElevenLabs.kt`

```kotlin
class ElevenLabs(private val apiKey: String, private val voiceId: String, private val http: OkHttpClient = defaultClient) {
    interface Sink {
        fun play(pcm: ByteArray)     // OkHttp thread, as chunks arrive
        fun finish()                 // the body ended cleanly
        fun fail(message: String)    // HTTP error, timeout, network -- never after cancel()
    }
    /** Starts streaming [text]; returns the call so it can be cancelled. */
    fun stream(text: String, sink: Sink): Call
}
```

- `POST https://api.elevenlabs.io/v1/text-to-speech/{voiceId}/stream?output_format=pcm_24000`,
  header `xi-api-key`, body `{"text": …, "model_id": "eleven_flash_v2_5"}`. No
  `language_code` (the model detects it; Arabic lines work). No
  `optimize_streaming_latency` (deprecated). Voice settings are left at
  ElevenLabs' defaults; `MODEL`, `OUTPUT_FORMAT`, `DEFAULT_VOICE_ID` and the
  chunk size are constants at the top of the file.
- `pcm_24000` is 24 kHz mono 16-bit little-endian, exactly what `AudioOut`
  plays — no decoding. It is available on every tier (only `pcm_44100` is gated).
- The response body is read in 4 KB chunks (~85 ms of audio each) straight into
  `sink.play`. End of body → `sink.finish()`. Any non-2xx → the body is parsed as
  ElevenLabs' error JSON (`detail.message`, or `detail` as a string) and
  reported as `sink.fail("ElevenLabs 401: Invalid API key")`; an `IOException`
  → `sink.fail("ElevenLabs: <message>")`, unless the call was cancelled, in
  which case nothing is reported.
- Its own `OkHttpClient` with 15 s connect, read and write timeouts, so a
  stalled stream ends the line instead of hanging it. OkHttp keeps the TLS
  connection alive between lines (5 min idle), which removes the ~0.35 s
  handshake from every line but the first.

Measured today from the same network with `curl` (which cannot reuse the
connection): first byte in 0.65 s of which 0.33 s is TLS, for a 5 s line;
1.7 s on the very first request while ElevenLabs warmed up. Expect about
0.3–0.5 s from Enter to first sound on the tablet once warm.

Keys: `app/build.gradle.kts` reads `ELEVENLABS_API_KEY` and
`ELEVENLABS_VOICE_ID` from `local.properties` (or the environment) into
`BuildConfig`, exactly like `GEMINI_API_KEY`. The voice falls back to
`DEFAULT_VOICE_ID` when blank. A blank key is logged at start (not spoken — the
feature is optional) and makes `/say` answer `503`.

### 2.4 `Brain`

New state, all main-thread unless marked:

```kotlin
private val queue = ArrayDeque<Entry>()
@Volatile private var current: Entry? = null      // the remote line playing
private var call: Call? = null                    // its ElevenLabs stream
private var nextId = 1
private var lastFailure: Failure? = null
private val eleven: ElevenLabs?                   // null when there is no key
```

**`say(line, now): Said`** — allowed in every state, including `BOOTING` (a
line can be spoken before the microphone model has loaded, like a typed prompt).

- `now = false`: append. If `state` is `IDLE` or `BOOTING`, `playNext()`.
  Otherwise the line waits for `goIdle()`.
- `now = true`: `interrupt()`, then add to the front and `playNext()`.
- Returns `Said(id, queued)` where `queued` counts the entries ahead of it plus
  one if a remote line is playing.

**`interrupt()`** — cancel `listenJob`, `replyJob`, `previewJob`; mic → `PAUSED`,
`mic.sink = null`; `out = null` then cancel the old player (the existing
pattern, so its late `onFinish` is ignored); cancel `call`; `faces = emptyList()`;
if a Gemini exchange was in flight (`state` is `LISTENING`, `THINKING` or
`SPEAKING` with `current == null`) then `closeSession()` — the resumption handle
is kept, so the next "hey ACMO" still remembers the conversation; this is also
what stops the model's late audio from mixing into the remote line.
`current = null`.

**`playNext()`** — take the head of the queue (none → `idle()`); `current = it`;
`lastFailure = null`; cancel `previewJob`/`listenJob`/`replyJob`; mic → `PAUSED`;
`state = SPEAKING`; `face.setExpression(entry.line.feeling)`; create the player
eagerly (`val o = player()`) and start the stream with a sink that only ever
touches that player:

```kotlin
call = eleven.stream(text, object : ElevenLabs.Sink {
    override fun play(pcm: ByteArray) = o.play(pcm)
    override fun finish() = o.finish()
    override fun fail(message: String) { main.post { lineFailed(entry, o, message) } }
})
```

The existing `onSpeechStart` animates the mouth and arms the 90 s watchdog; the
existing `onSpeechEnd` fires when the last byte has been *heard* and, when
`current != null`, calls `lineEnded()` instead of touching `lastExchangeAt`
(remote lines are not part of the conversation's memory clock).

**`lineEnded()`** — `current = null; call = null; playNext()` — so consecutive
queued lines run back to back, and the last one goes to `idle()`.

**`lineFailed(entry, o, message)`** — ignored unless `entry === current`.
`lastFailure = Failure(id, message)`; `out = null`; `o.cancel()`; `current = null`;
`face.setExpression(SAD)`; state stays `SPEAKING`; after 1.5 s (`replyJob`),
`playNext()`. No spoken apology: an operator is watching a screen, and the
message is in `/state`. The queue continues, so one network blip does not
drop every line typed ahead.

**`hush()`** — `interrupt()`, `queue.clear()`, `idle()`.

**`goIdle()`** becomes: if the queue is not empty, `playNext()`; else `idle()`
(today's `goIdle` body, renamed). Every path that returns to idle — a finished
Gemini reply, `shrug()`, the failure apology — therefore drains the queue.

**Guards.** `SessionListener.onAudio` (socket thread) returns early while
`current != null`, belt and braces against a session's audio reaching the
player during a remote line. `setForeground(false)` and `stop()` also cancel
`call` and clear the queue. `start(model)` (the microphone coming up) leaves a
playing line alone: `state != BOOTING` → mic stays `PAUSED`, and `idle()` puts
it in `WAKE` when the line ends.

**`snapshot()`** — `Snapshot(state, current, queue.toList(), lastFailure)`.

### 2.5 Settings and the card

`Settings.remote: Boolean`, key `"remote"`, default **true**. The card gets, after
the Dev mode row: an eyebrow "Remote", Off/On pills, and one muted mono line
underneath — `http://10.20.55.42:8765` (the first site-local IPv4 among
`NetworkInterface.getNetworkInterfaces()`), or
`no Wi-Fi address — adb forward tcp:8765 tcp:8765` when there is none, or
`could not listen on :8765` when the server failed to start. Off → the line
reads `off`. The strings live in `strings.xml` like the others.

`MainActivity` owns the server: created in `onCreate`, started or stopped from
`applySettings()` according to `settings.remote`, stopped in `onDestroy`.
`Brain` is the `Host`; `onMain` is `runOnUiThread`. No manifest changes: a
listening socket needs no permission, and `INTERNET` is already declared.

## 3. The console — `software/remote/`

Next 16.3 (`create-next-app`), TypeScript, App Router, `--empty`, ESLint, no
Tailwind, no `src/` dir, npm. Scaffolded with `--disable-git` (it lives inside
the app's repo) and without the generated `AGENTS.md`. **Not part of the Gradle
build**: `settings.gradle.kts` includes only `:app`, so nothing is needed on
that side; the scaffold's own `.gitignore` covers `node_modules/` and `.next/`.
Android Studio will index the folder unless it is marked *Excluded* — a
per-machine `.idea` choice, mentioned in the README.

Three files matter:

- **`lib/acmo.ts`** — the client: `say(base, text, feeling, now)`, `stop(base)`,
  `state(base)`; thin `fetch` wrappers. A non-2xx with `{error}` becomes
  `Error(error)`; other failures become `Error("can't reach <base>")`. Exports
  the `Feeling` union and `FEELINGS` list (the eight labels, in the face's
  order) and the `State` type mirroring `/state`.
- **`app/page.tsx`** — a client component, the whole page.
- **`app/globals.css`** — the face's look: JetBrains Mono (`next/font/google`,
  system monospace fallback), paper `#FBFAFB` / ink `#010000` / greys `#373637`,
  `#706D70`, teal `#2FBBAB` on light and `#3AE4D1` on dark, following
  `prefers-color-scheme`. Pills like the settings card: filled teal with dark
  text when selected, a teal ring otherwise.

```
┌──────────────────────────────────────────────────────────────┐
│ ACMO remote           ● http://localhost:8765     [address ▾] │
├──────────────────────────────────────────────────────────────┤
│ face   idle  surprised  sad  (happy)  angry  passionate  …    │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ What should ACMO say?                                    │ │
│ └──────────────────────────────────────────────────────────┘ │
│ [ Queue  ⏎ ]   [ Say now  ⌘⏎ ]   [ Stop  esc ]               │
├──────────────────────────────────────────────────────────────┤
│ speaking · happy  "Welcome to the ACM booth!"                │
│ queued  1 sad   "But I'm out of batteries."                  │
│         2 excited "Just kidding!"                            │
│ error   "ElevenLabs 401: Invalid API key"  (line 4)          │
└──────────────────────────────────────────────────────────────┘
```

Behaviour:

- **Address**: default `http://localhost:8765`, editable, kept in
  `localStorage` under `acmo.address`. The dot is green while `/state` answers;
  red otherwise, with the hint *run `adb forward tcp:8765 tcp:8765`, or enter
  the address from ACMO's settings card*.
- **Face**: eight pills, `happy` selected at load, the selection persists
  between lines (component state).
- **Text box**: a textarea that grows with its content. **Enter** queues,
  **Shift+Enter** inserts a newline, **⌘/Ctrl+Enter** says it now, **Esc**
  stops. The three buttons do the same with the mouse and show the shortcut.
  Whitespace-only text is not sent. On a `200` the box clears and keeps focus;
  on an error the text stays and the message shows under the box until the
  next successful send.
- **Status** from `GET /state` every 500 ms (a poll is skipped while the
  previous one is still in flight): the first line reads `idle`, `booting`,
  `talking to someone` (listening/thinking), `speaking · <feeling> "<text>"`
  for a remote line, or `speaking (not a remote line)`. Then the queue, one row
  per entry with its feeling, and the last error in red when there is one.
  The Stop button is enabled only while something is playing or queued.
- Nothing else: no history, no auth, no API routes.

`remote/README.md`: three commands — `adb forward tcp:8765 tcp:8765`,
`npm install`, `npm run dev` → `http://localhost:3000` — plus how to find the
tablet's address when not on adb. Node ≥ 20.9 (this Mac has 25).

## 4. Files, docs, tests, commits

```
software/
├── remote/                                   the console (new)
├── docs/superpowers/specs/…-design.md        this file
└── app/
    ├── build.gradle.kts                      ELEVENLABS_API_KEY / ELEVENLABS_VOICE_ID -> BuildConfig
    └── src/main/java/com/acmqu/acmo/
        ├── Brain.kt                          queue, say/interrupt/playNext/lineEnded/lineFailed/hush/snapshot
        ├── MainActivity.kt                   owns the RemoteServer
        ├── remote/RemoteServer.kt            new
        ├── remote/Line.kt                    new: Line, Entry, Said, Failure, Snapshot, JSON
        ├── voice/ElevenLabs.kt               new
        └── settings/Settings.kt, SettingsPanel.kt, res/layout/view_settings.xml, res/values/strings.xml
gradle/libs.versions.toml                     nanohttpd 2.3.1; mockwebserver 4.12.0 (test)
```

**Docs.** `software/README.md`: the two keys under *Setup*, a *Remote console*
section (what it is, the three commands, the four routes, the security note),
and tuning rows for `ElevenLabs.MODEL`, `DEFAULT_VOICE_ID`, `RemoteServer.PORT`.
`CONNECT.md`: the `adb forward` line in §4 and §7. The workspace README's
diagram: one line for the console.

**Tests** (JVM, `app/src/test`, JUnit 4 as today):

- `LineTest` — defaults, trimming, case-insensitive feeling, unknown feeling,
  empty text, 2 001 characters, non-JSON body; `Snapshot`/`Said` JSON.
- `RemoteServerTest` — the real server on port 0 (whatever the OS gives) with a
  fake `Host` and `onMain = { it() }`: `/say` reaches the host with the parsed
  line and `now`, and answers `{id, queued}`; `400` bodies; `503` without a key;
  `/stop` calls `hush()`; `/state` returns the snapshot's JSON; `OPTIONS` is
  `204` with the CORS headers; every response has `Access-Control-Allow-Origin`.
- `ElevenLabsTest` — with OkHttp's `MockWebServer`: the request's path, query,
  `xi-api-key` and body; a chunked `200` reaches the sink chunk by chunk and
  then `finish()`; a `401` with ElevenLabs' error JSON becomes
  `fail("ElevenLabs 401: …")`; a cancelled call reports nothing.

The `Brain` queue logic and the console are verified by hand on the Redmi
(`b15f152c`): `curl` against the tablet for each route, then the console end to
end — queue three lines, say one now, stop — and the wake word still working
afterwards. The report says exactly what was observed.

**Commits**, in `software/` (the app's own repo):

1. The Gemini Live migration that is already in the working tree, as its own
   commit (approved; it builds and its 16 tests pass).
2. This spec.
3. The tablet side: server, ElevenLabs, Brain, settings, tests, README.
4. The console.

Nothing is committed in `hardware/`.

## 5. Later, not now

Auth (a shared token), a history of sent lines, inline face tags timed with
ElevenLabs' alignment data (the WebSocket endpoint), Gemini-written lines,
mDNS discovery, driving the wheels from a line. The README's *What's here* tree
still describes the pre-Live files (`GeminiClient`, `Reply`, `Wav`); that
belongs to the Live migration's own clean-up, not to this change.

## 6. Implementation notes (2026-09-20)

What the code does differently from the sections above, found in review while
building it. The sections are left as designed; this is the record.

- **§2.2** The `/say` body parser is `Say.parse(json): Say(line, now)`, not
  `Line.parse`, because `now` lives in the same body. It reads `text` and
  `feeling` as strings only: Android's `org.json` turns a JSON `null` into the
  word `"null"` where the JVM's returns the fallback, so `{"text": null}` would
  have been spoken aloud on the tablet while passing the tests.
- **§2.3** The error body of a non-2xx reply is read bounded (4 KB) and
  exception-safe: an `IOException` there escaped OkHttp's callback and the sink
  heard neither `fail` nor `finish`. `Sink.fail` may follow a cancel that lands
  mid-read; it is otherwise never called after `cancel()`.
- **§2.1** The server never gzips (NanoHTTPD would, for browsers, and a gzipped
  chunked body on the `204` preflight poisons the keep-alive connection); a
  `Content-Length` over 1 MB is refused with `413` before a byte is read; a
  request the main thread did not answer within 2 s is abandoned rather than
  acted on late, and so is one that arrives while the server is stopping. The
  preflight also carries `Access-Control-Max-Age: 86400`.
- **§2.4** A console line's watchdog is `10 s + 100 ms × characters` rather than
  the reply's fixed 90 s, so a 2 000-character line is not cut off. The mic
  joins whatever state a line left behind if it finished while the wake-word
  model was still loading.
- **§2.5** The row is labelled *Remote console*; the address line is in the ink
  colour, not muted; the card's rows scroll when taller than the screen; the
  hints read `no Wi-Fi address — on the Mac: adb forward tcp:8765 tcp:8765` and
  `could not listen on :8765 — is another ACMO running?`.
- **§4** Commits were finer-grained than the four listed: one per task, plus
  one follow-up per review that asked for a change.
