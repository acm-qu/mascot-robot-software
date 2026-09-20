# Audio tags: the voice and the face follow `[tags]` in a line — design

*2026-09-20. Approved in conversation; this is the written form. Builds on
`2026-09-20-elevenlabs-remote-console-design.md`.*

A line typed into the console can carry ElevenLabs v3 *audio tags* — a word in
square brackets, `[sad]`, `[laughs]`, `[whispers]` — and the voice takes that
tone from there on. The tag is direction, not text: v3 does not say it. A tag
that names one of ACMO's faces also changes the face, exactly when the voice
gets there, so one line can walk through several feelings:

```
[excited] We won the hackathon!  [sad] But the pizza is gone.  [whispers] All of it.
   face: excited ─────────────────▶ sad ────────────────────────▶ sad (voice only)
```

Today the tag would be read out loud ("feeling text") because the fast model
(`eleven_flash_v2_5`) does not know tags, and the face stays on the one the
operator picked. After this, both follow the text.

Decisions taken during the design, in one place:

| Question | Decision |
| --- | --- |
| Which model | `eleven_v3` when the line contains any tag; `eleven_flash_v2_5` otherwise. Both through the same endpoint and code path. |
| Why not v3 always | v3 starts about a second later (Mac, cold TLS: 1.8–2.0 s to the first byte, Flash 1.05–1.1 s). A plain line keeps today's 0.4 s. |
| How the face knows when | ElevenLabs' *stream with timestamps* endpoint: every character's start time, tags included. One request, continuous audio, exact cues. |
| Is the tag sent | Yes, verbatim, all of them. Nothing is stripped or rewritten. |
| Which words change the face | The eight labels, plus three more words for each face except `idle` (§1). |
| The console's face pill | The face until the first face tag. A line that starts with a face tag starts on that face. |
| Wire protocol | Unchanged, except `GET /state` gains `face`: the face on screen now. |

## 1. Tags

**Grammar.** A tag is `[` + one to forty characters that are not `[`, `]` or a
line break + `]`. Anything else in brackets is text. Tags may repeat and there
may be any number of them; they take effect in order.

**Face tags.** A tag whose word — trimmed, case-insensitive — is in this table
changes the face when the voice reaches it. Every other tag (`[whispers]`,
`[sighs]`, `[laughs harder]`, `[pause]`) is *voice only*: the tone changes, the
face does not. The words were chosen to be directions v3 understands as well as
names for the faces.

| Face | Words |
| --- | --- |
| `idle` | idle |
| `happy` | happy, laughs, giggles, cheerful |
| `sad` | sad, crying, gloomy, disappointed |
| `angry` | angry, shouting, furious, growls |
| `annoyed` | annoyed, sarcastic, groans, frustrated |
| `surprised` | surprised, gasps, shocked, amazed |
| `excited` | excited, thrilled, enthusiastic, energetic |
| `passionate` | passionate, loving, romantic, dramatic |

The table lives in one place on the tablet (`Tags.WORDS`) and is mirrored in
the console for its hints (§4), like the eight labels already are.

**Which face, when.**

- The operator's pill (`feeling` in `/say`, default `happy`) is the face from
  the first sound until the first face tag.
- If the line *starts* with a face tag — other tags and spaces may come before
  it, text may not — that face shows as soon as the line starts, not the pill's:
  otherwise the pill's face would show for the whole 1.5–2 s the model takes to
  start and then flip.
- Every later face tag switches the face at the **start time of its `[`**. The
  alignment attributes the pause before a new segment (0.3–0.5 s in the probes)
  to the opening bracket, so the face turns during that breath and leads the
  voice by a fraction of a second, the way a face does.
- A face tag makes the line *expressive* (v3) like any tag; a plain line goes
  to Flash and keeps its 0.4 s start.

`[idle]` is sent like the rest. "Idle" is an odd direction for a voice; whether
v3 says it or ignores it is a by-ear check on the tablet (§6), and if it says
it, dropping just that word from the voice text is a follow-up.

## 2. The wire

`POST /say`, `POST /stop` and the CORS rules are unchanged; tags travel inside
`text`, and `Say.parse` neither validates nor strips them. `GET /state` gains
one field:

```json
{"state": "speaking", "face": "sad", "line": {"id": 7, "text": "…", "feeling": "excited"}, "queue": [], "error": null}
```

`face` is the label of the expression on screen at that moment — during a
tagged line it moves; `line.feeling` stays the face the line started with.
It is always present: whatever the screen shows, `idle` when quiet.

## 3. The tablet

### 3.1 `remote/Tags.kt` (new)

```kotlin
/** A face tag in a line: its exact text, where it starts (in code points), and the face it names. */
data class FaceTag(val text: String, val index: Int, val feeling: Expression)

object Tags {
    /** Every word that names a face, lowercase: the eight labels and §1's extras. */
    val WORDS: Map<String, Expression>
    /** Any tag at all -- face or voice only: the line should go to the expressive model. */
    fun hasTags(text: String): Boolean
    /** The face tags of [text], in order. */
    fun faces(text: String): List<FaceTag>
}
```

Pure Kotlin, no Android. The regex is `\[([^\[\]\r\n]{1,40})\]`. `index` is
counted in **code points**, not UTF-16 units, because that is how ElevenLabs
counts characters (probed: an emoji is one alignment entry).

### 3.2 `remote/FaceCues.kt` (new)

```kotlin
/**
 * Turns the timing ElevenLabs streams into face cues. Fed the characters as they are
 * timed, in order, it answers with the face tags reached so far and where in the
 * audio each begins. One thread at a time.
 */
class FaceCues(tags: List<FaceTag>) {
    /** [atByte] is where each of the next characters begins in the audio, one entry per code point. Returns the cues now known. */
    fun feed(atByte: LongArray): List<Cue>
    /** Face tags the timing has not reached yet. */
    val pending: Int
}
data class Cue(val atByte: Long, val feeling: Expression)
```

It counts the characters fed so far; when the count passes a tag's `index`,
the tag's byte is `atByte[index - start of this feed]`, rounded down to a whole
frame (`and 1.inv()`). `chars` may split a tag anywhere: only the `[` matters.
A tag whose `index` is `0` is not emitted (the Brain shows that face from the
start, §3.4).

### 3.3 `voice/ElevenLabs.kt`

**Endpoint.** Every line — tagged or not, Flash or v3 — goes to
`POST /v1/text-to-speech/{voice}/stream/with-timestamps?output_format=pcm_24000`
with `{"text": …, "model_id": …}`. The reply is `application/json`, chunked:
a stream of JSON objects, each followed by a blank line, one per audio chunk:

```json
{"audio_base64": "…", "alignment": {"characters": ["[", "h", …], "character_start_times_seconds": [0.0, 0.013, …], "character_end_times_seconds": […]}, "normalized_alignment": {…}, "quality_check": null}
```

Probed with both models and confirmed: `alignment` is `null` on audio-only
chunks; the last object has empty arrays; the `characters` of all chunks
concatenated are the request text, character for character (code points);
tags are present with their times. `normalized_alignment` and `quality_check`
are ignored.

**Reading it.** `BufferedReader.readLine()` over the body, blank lines skipped,
`JSONObject(line)` — the JSON library already in use, so no new dependency and
the JVM `org.json` on the test classpath still exercises it. `audio_base64` is
decoded with Okio's `decodeBase64()`, which is on the classpath through OkHttp
and works in unit tests (`java.util.Base64` needs API 26, `minSdk` is 24, and
`android.util.Base64` is a stub in unit tests). Per object, **in this order**:
the alignment goes to the sink first, then the audio — so a cue is always
registered before the bytes it refers to reach the player. An alignment that is
`null` or has no characters sends nothing to `timed`; an `audio_base64` that is
empty sends nothing to `play`.

**The sink** gains one method:

```kotlin
interface Sink {
    fun play(pcm: ByteArray)
    /** Where in the audio each of the next characters begins, one entry per code point, before the audio they describe. OkHttp's thread. */
    fun timed(atByte: LongArray)
    fun finish()
    fun fail(message: String)
}
```

`atByte = (seconds × 48 000)` rounded down to a frame (`AudioOut.BYTES_PER_MS × 1000`
bytes per second). Not `CUE_LEAD_BYTES`: that constant compensates for Gemini's
transcript running ahead of its audio; these times are exact.

**The model.** `stream(text, sink, expressive = false)`: `eleven_v3` when
`expressive`, `eleven_flash_v2_5` otherwise. The Brain decides
(`Tags.hasTags`), so the voice package knows nothing about tags.

**Failures.** A line that is not JSON, an `audio_base64` that is not a string or
does not decode, an `alignment` that is not an object, one without `characters`
or start times, arrays of different lengths, or a start time that is not a number
is `fail("ElevenLabs: bad chunk")` and the read stops. An object with no
`audio_base64` (or `null` there) is timing only. A sink that throws from `play`
or `timed` also ends in `fail`; `finish` and `fail` must not throw. The rest
(non-2xx, timeouts, a body that dies, cancel) is exactly as today. The old read loop and its odd-byte
carry are gone: base64 decodes to whole arrays. `AudioOut.play` keeps its own carry,
which still guards a decoded chunk of odd length.

### 3.4 `Brain`

`playNext()` changes in four lines' worth:

```kotlin
val tags = Tags.faces(entry.line.text)
val opening = Tags.opening(text) ?: entry.line.feeling   // the first face tag, if only tags come before it
face.setExpression(opening)
val cues = FaceCues(tags)
val o = player()
call = voice.stream(entry.line.text, expressive = Tags.hasTags(entry.line.text), sink = object : ElevenLabs.Sink {
    override fun timed(atByte: LongArray) {
        for (c in cues.feed(atByte)) {
            Log.i(TAG, "line #${entry.id}: ${c.feeling.label} at ${c.atByte / AudioOut.BYTES_PER_MS} ms")
            o.cue(c.atByte) { if (playing === entry) face.setExpression(c.feeling) }
        }
    }
    override fun play(pcm: ByteArray) = o.play(pcm)
    override fun finish() {
        if (cues.pending > 0) Log.w(TAG, "line #${entry.id}: ${cues.pending} face tag(s) never reached")
        o.finish()
    }
    override fun fail(message: String) { main.post { lineFailed(entry, o, message) } }
})
```

The cue's guard is by identity — the same reason the Gemini path guards its
cues with `state == SPEAKING` — so a cue from a line that was stopped or cut
in on can never touch the next one; `AudioOut.cancel()` clears pending cues
anyway. The log line for a line now also says whether it is expressive.
`snapshot()` passes `face.expression`. Queue, `now`, `hush`, the watchdog
(`LINE_MS_PER_CHAR` per character, tags included) and the Gemini path are
untouched.

### 3.5 `remote/Line.kt`

`Snapshot` gains `face: Expression`, serialised as its label. `Line`, `Say`,
`Entry`, `Said`, `Failure` are unchanged.

## 4. The console — `software/remote/`

- `lib/acmo.ts`: `State.face: Feeling`; `TAG_WORDS: Record<Feeling, readonly string[]>`
  mirroring §1 (the label first, `idle` alone), with a comment naming
  `Tags.kt` as the source of truth.
- Under the face pills, one row of small buttons: the selected face's words,
  each rendered as its tag — `[happy] [laughs] [giggles] [cheerful]`. Clicking
  one inserts that tag and a space at the cursor in the textarea and puts the
  focus back. This is how the operator finds the words; nothing else changes
  in what the pills mean.
- The textarea's placeholder mentions tags: *What should ACMO say? A [tag]
  changes the tone; a face word changes the face too.*
- The status line shows the live face during a line: `speaking · sad` from
  `state.face` (falling back to `line.feeling` if a tablet does not send it).
  The queue list still shows each line's starting `feeling`.
- No other UI. No tag validation in the browser.

## 5. Errors and edge cases

| Case | What happens |
| --- | --- |
| Voice-only tag (`[whispers]`) | Sent, changes the tone, face unchanged; the line is expressive. |
| A word in brackets v3 does not know | v3's business: it may ignore it or say it. Not a face tag unless in §1. |
| `[` without `]`, `[]`, nested brackets, a line break inside | Plain text. |
| Tag repeated, or three tags in one chunk | Cues in order; each fires. |
| The alignment never reaches a tag (should not happen) | The face stays; a warning is logged when the line ends with cues pending. |
| A bad chunk (§3.3) | The line fails like an HTTP error: sad face for `FAIL_PAUSE_MS`, then the queue goes on. |
| Stop / Say now mid-line | The identity guard covers a cue already posted; `cancel()` drops the rest once the audio thread unwinds, and refuses new ones. |
| A 2 000-character tagged line | v3 streams as it generates (3–5× real time in the probes), so the start is still ~2 s; watchdog budget unchanged. |
| Emoji before a tag | Indexes are code points on both sides (§3.1), so the cue lands on the tag. |

## 6. Tests and checks

**Unit tests (JVM, no Android):**

- `TagsTest` (new): the eight labels and every §1 word map to their face,
  case- and space-insensitively; voice-only tags are not face tags but make
  `hasTags` true; malformed brackets are text; indexes are counted in code
  points (an emoji before the tag); tags in order, repeats included.
- `FaceCuesTest` (new): a tag split across two feeds; three tags in one feed;
  the index-0 tag is not emitted; bytes are whole frames; `pending` counts down.
- `ElevenLabsTest`: rewritten for the timestamps stream — the path and
  `model_id` for a plain and an expressive line; audio decoded in order then
  `finish`; `timed` before `play` within an object and converted to bytes;
  `null` and empty alignments skipped; blank lines skipped; a bad line fails;
  the existing error, cancel and torn-body tests kept.
- `LineTest` / `RemoteServerTest`: `Snapshot` and `/state` carry `face`.

**Console:** `npm run lint`, `npx tsc --noEmit`, `npm run build`; `TAG_WORDS`
has every feeling.

**On the Redmi, by eye and ear:**

- A three-tag line: the face changes at each tag, the tags are not spoken, the
  audio has no gaps.
- A line starting with a face tag shows that face from the start.
- A voice-only tag changes the tone and not the face.
- `[idle]`: spoken or not.
- Latency to the first sound, warm: a plain line (Flash) and a tagged line (v3).
- Stop in the middle of a tagged line: no face change afterwards.
- The console shows the live face and the tag buttons insert at the cursor.

Not checked here: v3's credit cost per character against Flash (the key lacks
`user_read`, so read it off the ElevenLabs dashboard).

## 7. Files, docs, commits

New: `remote/Tags.kt`, `remote/FaceCues.kt`, `TagsTest.kt`, `FaceCuesTest.kt`.
Changed: `voice/ElevenLabs.kt`, `Brain.kt`, `remote/Line.kt`, `ElevenLabsTest.kt`,
`LineTest.kt`, `RemoteServerTest.kt`, `remote/lib/acmo.ts`, `remote/app/page.tsx`
(+ a few lines of CSS), `remote/README.md`, `README.md` (the *Remote console*
section: tags, the words table, the two models and their latency; the tuning
table: `Tags.WORDS`). Small commits, one per step, each with green tests. No
new dependency.

## 8. Later, not now

- Dropping `[idle]` from the voice text if v3 says it.
- A `GET /tags` route so the console reads the words from the tablet instead of
  mirroring them.
- Letting the operator force v3 for a plain line, or Flash for a tagged one.

## 9. Implementation notes (2026-09-20)

What the reviews and the tablet changed, so this document matches the code:

- `FaceCues.feed(atByte)` and `Sink.timed(atByte)` take byte offsets only, one
  entry per code point; nothing read the characters, and a `String` is the
  wrong shape for them (an emoji is one entry, two UTF-16 units). Probed: the
  alignment counts code points even for a skin-tone or a family emoji.
- `Sink.timed` is abstract once `Brain` implements it, so a sink cannot silently
  drop every cue. An exception from `play` or `timed` ends the line in `fail`.
- A stream object with no `audio_base64` (or `null`) is timing only; an
  `alignment` that is not an object, or a start time that is not a number, is a
  bad chunk. Every object ElevenLabs sent carried `audio_base64` as a string.
- The line opens on its first face tag when only tags and spaces precede it
  (`Tags.opening`), so `[whispers] [sad] …` does not flip at the first sound.
- A bare CR inside brackets is not a tag either. `AudioOut.cue` refuses a cue
  after a cancel; the identity guard covers one already posted.
- The player logs when a cue fires (head position, time since start) and, at
  the end, how much played and the AudioTrack's underrun count.

Verified on the Redmi Pad 2 (`b15f152c`), the same day, over `adb forward`:

| Check | Result |
| --- | --- |
| Three-tag line | Opens on `excited`; cues `sad` at 2.08 s and `happy` at 4.28 s of audio fire with the head exactly there; 7.1 s of audio, 0 underruns; `/state` follows excited → sad → happy. |
| Leading face tag | That face from the start, never the pill's. |
| Voice-only tag (`[whispers]`) | v3, tone only; the face stays on the pill's. |
| `[whispers] [sad] …` | Opens on `sad`; the re-cue at 90 ms is a no-op. |
| Stop at 1.5 s | Idle within 0.2 s; no cue fires afterwards. |
| Trailing tag (`Bye now [sad]`) | Fires: v3 leaves ~0.9 s of pause after the last word, and the cue sits at its start. |
| `[idle] …` | Opens on `idle`; 1.3 s of audio for four words, so the word is not spoken (by-ear check with the operator). |
| Latency to the player, warm | Flash 0.49 s, v3 1.29 s from Enter; the tablet's audio output then starts ~0.47 s later for both (head position lags `play()` by that much; no underruns). |
| v3 delivery | Bursts of ~0.4 s of audio, 7 s of audio within 2.3 s of the first byte -- well ahead of playback. |

Not measured: v3's credit cost per character (the key lacks `user_read`).
Worth trying later: `AudioTrack.PERFORMANCE_MODE_LOW_LATENCY` (API 26+) to cut
the half-second output start; a `remote/scripts/check-words` script so the two
word tables cannot drift; `GET /tags`.
