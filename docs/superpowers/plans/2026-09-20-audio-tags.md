# Audio Tags Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A console line can carry `[tags]`: ElevenLabs v3 takes the tone from each one, and a tag that names a face changes the face at the moment the voice gets there.

**Architecture:** Every console line now goes to ElevenLabs' *stream with timestamps* endpoint — v3 for a tagged line, Flash for a plain one. The client parses its newline-delimited JSON, hands the audio to the player and the per-character timing to its sink; `Brain` maps each face tag's position to a byte offset and registers a cue on the existing `AudioOut.cue`, the mechanism that already changes the face between Gemini's sentences. Two pure classes carry the logic: `Tags` (which bracketed words name a face, and where they sit) and `FaceCues` (timing → cues). The console learns the words and shows the live face.

**Tech Stack:** Kotlin, OkHttp 4.12 with Okio 3.6 (base64), `org.json`, JUnit 4, MockWebServer; Next.js 16.3 / React 19 / TypeScript, plain CSS.

**Spec:** `docs/superpowers/specs/2026-09-20-audio-tags-design.md`. Two small deviations, decided while planning: `FaceCues.feed` takes only the byte offsets — `feed(atByte)` — because it counts alignment entries and never needs the characters; and a stream object without `audio_base64` (or with `null` there) is treated as timing only rather than as a bad chunk — the probes show every object carries the field, so this only matters if ElevenLabs ever adds a metadata-only object. Everything else is as specified.

---

## Conventions for every task

- All paths are relative to `software/` (the app's own git repo).
- Gradle needs Studio's JDK. Before any `./gradlew`:
  ```sh
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
- The unit tests: `./gradlew :app:testDebugUnitTest --console=plain -q`. Silence means green; a failure prints the failing test and `BUILD FAILED`. One class only: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.TagsTest' --console=plain -q`. There are 48 tests before this plan.
- The build: `./gradlew :app:assembleDebug --console=plain -q`.
- The console: `cd remote && npm run lint && npx tsc --noEmit && npm run build`.
- Commit messages: the repo's style is `type(scope): what it does`, lower case, a short body when it helps. End the body with `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.
- `local.properties` holds a real, paid ElevenLabs key. Never print it, never `cat` it, never commit it. The unit tests use MockWebServer and never reach ElevenLabs; do not write scripts that do.
- Never print `app/build/generated/**/BuildConfig.java` either: it contains the key.
- Do not touch `../hardware/` or anything outside `software/`.

## File map

| File | Responsibility |
| --- | --- |
| `app/src/main/java/com/acmqu/acmo/remote/Tags.kt` (new) | `FaceTag`; `Tags.WORDS`, `hasTags`, `faces` |
| `app/src/main/java/com/acmqu/acmo/remote/FaceCues.kt` (new) | `Cue`; `FaceCues`: timing → cues |
| `app/src/main/java/com/acmqu/acmo/voice/ElevenLabs.kt` | the timestamps stream: JSON lines → `Sink.timed` then `Sink.play`; the model per line |
| `app/src/main/java/com/acmqu/acmo/remote/Line.kt` | `Snapshot.face` |
| `app/src/main/java/com/acmqu/acmo/Brain.kt` | `playNext`: the opening face, the cues, the model; `snapshot()` |
| `app/src/test/java/com/acmqu/acmo/{TagsTest,FaceCuesTest}.kt` (new), `{ElevenLabsTest,LineTest,RemoteServerTest}.kt` | JVM tests |
| `remote/lib/acmo.ts`, `remote/app/page.tsx`, `remote/app/globals.css` | `State.face`, `TAG_WORDS`, the words row, the live face |
| `README.md`, `remote/README.md` | tags, the words, the two models |

---

### Task 1: `Tags` — which bracketed words name a face, and where they sit

**Files:**
- Create: `app/src/main/java/com/acmqu/acmo/remote/Tags.kt`
- Test: `app/src/test/java/com/acmqu/acmo/TagsTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.remote.FaceTag
import com.acmqu.acmo.remote.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audio tags in a line: which ones change the face, and where they are. */
class TagsTest {

    @Test
    fun `every label names its face, and so do the extra words`() {
        for (e in Expression.entries) assertEquals(e, Tags.WORDS[e.label])
        assertEquals(Expression.HAPPY, Tags.WORDS["laughs"])
        assertEquals(Expression.HAPPY, Tags.WORDS["giggles"])
        assertEquals(Expression.HAPPY, Tags.WORDS["cheerful"])
        assertEquals(Expression.SAD, Tags.WORDS["crying"])
        assertEquals(Expression.SAD, Tags.WORDS["gloomy"])
        assertEquals(Expression.SAD, Tags.WORDS["disappointed"])
        assertEquals(Expression.ANGRY, Tags.WORDS["shouting"])
        assertEquals(Expression.ANGRY, Tags.WORDS["furious"])
        assertEquals(Expression.ANGRY, Tags.WORDS["growls"])
        assertEquals(Expression.ANNOYED, Tags.WORDS["sarcastic"])
        assertEquals(Expression.ANNOYED, Tags.WORDS["groans"])
        assertEquals(Expression.ANNOYED, Tags.WORDS["frustrated"])
        assertEquals(Expression.SURPRISED, Tags.WORDS["gasps"])
        assertEquals(Expression.SURPRISED, Tags.WORDS["shocked"])
        assertEquals(Expression.SURPRISED, Tags.WORDS["amazed"])
        assertEquals(Expression.EXCITED, Tags.WORDS["thrilled"])
        assertEquals(Expression.EXCITED, Tags.WORDS["enthusiastic"])
        assertEquals(Expression.EXCITED, Tags.WORDS["energetic"])
        assertEquals(Expression.PASSIONATE, Tags.WORDS["loving"])
        assertEquals(Expression.PASSIONATE, Tags.WORDS["romantic"])
        assertEquals(Expression.PASSIONATE, Tags.WORDS["dramatic"])
        // Three extra words for every face but idle, which has only its label.
        for (e in Expression.entries) {
            val expected = if (e == Expression.IDLE) 1 else 4
            assertEquals(e.label, expected, Tags.WORDS.values.count { it == e })
        }
    }

    @Test
    fun `face tags come in order, with their exact text and where they start`() {
        val tags = Tags.faces("[excited] We won! [sad] But the pizza is gone. [laughs] Kidding.")
        assertEquals(
            listOf(
                FaceTag("[excited]", 0, Expression.EXCITED),
                FaceTag("[sad]", 18, Expression.SAD),
                FaceTag("[laughs]", 47, Expression.HAPPY),
            ),
            tags,
        )
    }

    @Test
    fun `the word is matched trimmed and case-insensitively, the text is kept as typed`() {
        val tags = Tags.faces("[ Sad ] oh. [LAUGHS] ha.")
        assertEquals(listOf(Expression.SAD, Expression.HAPPY), tags.map { it.feeling })
        assertEquals("[ Sad ]", tags[0].text)
        assertEquals("[LAUGHS]", tags[1].text)
    }

    @Test
    fun `a voice-only tag is not a face tag, but still makes the line expressive`() {
        val text = "[whispers] come closer. [sighs]"
        assertTrue(Tags.faces(text).isEmpty())
        assertTrue(Tags.hasTags(text))
        assertTrue(Tags.hasTags("[laughs harder] no way"))
        assertFalse(Tags.hasTags("No tags here, just [ a bracket that never closes"))
        assertFalse(Tags.hasTags("Plain text."))
    }

    @Test
    fun `broken brackets are text; nested ones leave the inner pair as the tag`() {
        assertTrue(Tags.faces("[sad").isEmpty())
        assertTrue(Tags.faces("sad]").isEmpty())
        assertTrue(Tags.faces("[]").isEmpty())
        assertFalse(Tags.hasTags("[]"))
        assertTrue(Tags.faces("[sad\nface]").isEmpty())
        assertFalse(Tags.hasTags("[sad\nface]"))
        assertTrue(Tags.faces("[" + "x".repeat(41) + "]").isEmpty())
        assertEquals(listOf(FaceTag("[sad]", 1, Expression.SAD)), Tags.faces("[[sad]]"))
    }

    @Test
    fun `indexes count code points, the way ElevenLabs counts characters`() {
        // An emoji is two UTF-16 units but one character to ElevenLabs.
        val text = "Hi 👋 there! [sad] Bye."
        val tag = Tags.faces(text).single()
        assertEquals(12, tag.index)
        assertEquals(13, text.indexOf("[sad]"))   // what a UTF-16 index would have said
    }

    @Test
    fun `a repeated tag is reported each time`() {
        val tags = Tags.faces("[sad] one. [sad] two.")
        assertEquals(listOf(0, 11), tags.map { it.index })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.TagsTest' --console=plain -q`
Expected: compilation fails with `Unresolved reference 'Tags'` (and `FaceTag`).

- [ ] **Step 3: Write `Tags.kt`**

```kotlin
package com.acmqu.acmo.remote

import com.acmqu.acmo.face.Expression

/** A face tag in a line: its exact text, where it starts (counted in code points), and the face it names. */
data class FaceTag(val text: String, val index: Int, val feeling: Expression)

/**
 * `[sad]`, `[laughs]`, `[whispers]`: a word in brackets is an ElevenLabs v3 audio tag -- direction
 * for the voice, not text to say. One that names a face (a label, or one of the extra words in
 * [WORDS]) changes the face too, when the voice gets there. Every tag stays in the text sent to
 * ElevenLabs; nothing here strips or rewrites them.
 */
object Tags {
    /** Three more ways of saying each face, chosen to be directions v3 understands as well. Idle has only its label. */
    private val EXTRAS: Map<Expression, List<String>> = mapOf(
        Expression.HAPPY to listOf("laughs", "giggles", "cheerful"),
        Expression.SAD to listOf("crying", "gloomy", "disappointed"),
        Expression.ANGRY to listOf("shouting", "furious", "growls"),
        Expression.ANNOYED to listOf("sarcastic", "groans", "frustrated"),
        Expression.SURPRISED to listOf("gasps", "shocked", "amazed"),
        Expression.EXCITED to listOf("thrilled", "enthusiastic", "energetic"),
        Expression.PASSIONATE to listOf("loving", "romantic", "dramatic"),
    )

    /** Every word that names a face, lowercase: the eight labels and the extras. The console mirrors this table. */
    val WORDS: Map<String, Expression> = buildMap {
        for (e in Expression.entries) {
            put(e.label, e)
            EXTRAS[e]?.forEach { put(it, e) }
        }
    }

    /** A bracketed word: one to forty characters, no brackets or line breaks inside. */
    private val TAG = Regex("""\[([^\[\]\n]{1,40})\]""")

    /** Whether [text] has any tag at all, face or voice only: such a line goes to the expressive model. */
    fun hasTags(text: String): Boolean = TAG.containsMatchIn(text)

    /** The face tags of [text], in order. [FaceTag.index] is counted in code points, the way ElevenLabs counts characters. */
    fun faces(text: String): List<FaceTag> = TAG.findAll(text).mapNotNull { m ->
        WORDS[m.groupValues[1].trim().lowercase()]?.let { FaceTag(m.value, text.codePointCount(0, m.range.first), it) }
    }.toList()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.TagsTest' --console=plain -q`
Expected: silence (green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acmqu/acmo/remote/Tags.kt app/src/test/java/com/acmqu/acmo/TagsTest.kt
git commit -m "feat(remote): the words that name a face in brackets, and where they sit in a line

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `FaceCues` — from character timing to "this face at this byte"

**Files:**
- Create: `app/src/main/java/com/acmqu/acmo/remote/FaceCues.kt`
- Test: `app/src/test/java/com/acmqu/acmo/FaceCuesTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.remote.Cue
import com.acmqu.acmo.remote.FaceCues
import com.acmqu.acmo.remote.FaceTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** From "this character starts at this byte" to "show this face at this byte". */
class FaceCuesTest {

    // "[excited] We won! [sad] But the pizza is gone." -- tags at 0 and 18.
    private val tags = listOf(
        FaceTag("[excited]", 0, Expression.EXCITED),
        FaceTag("[sad]", 18, Expression.SAD),
    )

    /** The byte offsets of [n] characters starting at character [from], 1 000 bytes apart. */
    private fun bytes(from: Int, n: Int) = LongArray(n) { (from + it) * 1000L }

    @Test
    fun `the tag at index 0 is never a cue; the next one is, once its character is timed`() {
        val cues = FaceCues(tags)
        assertEquals(1, cues.pending)
        assertEquals(emptyList<Cue>(), cues.feed(bytes(0, 18)))   // characters 0..17: the tag at 18 is not timed yet
        assertEquals(1, cues.pending)
        assertEquals(listOf(Cue(18_000, Expression.SAD)), cues.feed(bytes(18, 5)))
        assertEquals(0, cues.pending)
        assertEquals(emptyList<Cue>(), cues.feed(bytes(23, 20)))
    }

    @Test
    fun `a feed may cover several tags, or split a tag anywhere`() {
        val three = tags + FaceTag("[laughs]", 40, Expression.HAPPY)
        // One feed with both tags in it.
        assertEquals(listOf(Cue(18_000, Expression.SAD), Cue(40_000, Expression.HAPPY)), FaceCues(three).feed(bytes(0, 50)))
        // Split: "[sa" in one feed, "d]" in the next. Only the '[' matters, so the cue comes with the first.
        val split = FaceCues(three)
        assertEquals(emptyList<Cue>(), split.feed(bytes(0, 17)))
        assertEquals(listOf(Cue(18_000, Expression.SAD)), split.feed(bytes(17, 4)))   // characters 17..20
        assertEquals(listOf(Cue(40_000, Expression.HAPPY)), split.feed(bytes(21, 30)))
    }

    @Test
    fun `a cue is a whole frame`() {
        val cues = FaceCues(listOf(FaceTag("[sad]", 1, Expression.SAD)))
        assertEquals(listOf(Cue(4_242, Expression.SAD)), cues.feed(longArrayOf(0, 4_243)))
    }

    @Test
    fun `no tags, no cues`() {
        val cues = FaceCues(emptyList())
        assertEquals(0, cues.pending)
        assertTrue(cues.feed(bytes(0, 10)).isEmpty())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.FaceCuesTest' --console=plain -q`
Expected: compilation fails with `Unresolved reference 'FaceCues'` (and `Cue`).

- [ ] **Step 3: Write `FaceCues.kt`**

```kotlin
package com.acmqu.acmo.remote

import com.acmqu.acmo.face.Expression

/** A face to show once playback reaches [atByte] of the line's audio. */
data class Cue(val atByte: Long, val feeling: Expression)

/**
 * Turns the timing ElevenLabs streams into face cues. Fed the byte offset at which each character
 * of the text begins, in order, it answers with the face tags reached so far and where in the
 * audio each begins -- the start of the tag's `[`, which is the pause before the new tone.
 * One thread at a time.
 *
 * A tag at index 0 is never a cue: the Brain shows that face from the start.
 */
class FaceCues(tags: List<FaceTag>) {
    private val tags = tags.filter { it.index > 0 }
    private var timed = 0   // characters timed so far
    private var next = 0

    /** Face tags the timing has not reached yet. */
    val pending: Int get() = tags.size - next

    /**
     * [atByte] is where each of the next characters of the text begins in the audio, one entry
     * per character. Returns the cues now known, oldest first, each rounded down to a whole frame.
     */
    fun feed(atByte: LongArray): List<Cue> {
        val start = timed
        timed += atByte.size
        if (next >= tags.size || tags[next].index >= timed) return emptyList()
        val found = ArrayList<Cue>(1)
        while (next < tags.size && tags[next].index < timed) {
            val tag = tags[next++]
            found += Cue(atByte[tag.index - start] and 1L.inv(), tag.feeling)
        }
        return found
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.FaceCuesTest' --console=plain -q`
Expected: silence (green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acmqu/acmo/remote/FaceCues.kt app/src/test/java/com/acmqu/acmo/FaceCuesTest.kt
git commit -m "feat(remote): from ElevenLabs' character timing to face cues

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `ElevenLabs` — the stream with timing, and the model per line

**Files:**
- Modify: `app/src/main/java/com/acmqu/acmo/voice/ElevenLabs.kt` (the whole file is replaced below)
- Test: `app/src/test/java/com/acmqu/acmo/ElevenLabsTest.kt` (the whole file is replaced below)

Background for whoever does this: ElevenLabs' `POST /v1/text-to-speech/{voice}/stream/with-timestamps?output_format=pcm_24000` answers `application/json`, chunked, as a stream of JSON objects, each followed by a blank line. An object looks like `{"audio_base64": "…", "alignment": {"characters": ["[", "h", …], "character_start_times_seconds": [0.0, 0.013, …], "character_end_times_seconds": […]}, "normalized_alignment": {…}, "quality_check": null}`; `alignment` is `null` on audio-only objects; the `characters` of every object concatenated are the request text exactly, one entry per code point. Probed live on 2026-09-20 with both models. `Brain` keeps compiling through this task because `Sink.timed` gets a default body and `stream`'s new parameter has a default.

- [ ] **Step 1: Replace the tests**

```kotlin
package com.acmqu.acmo

import com.acmqu.acmo.voice.ElevenLabs
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The streaming client against a local server that plays ElevenLabs' stream-with-timestamps endpoint. */
class ElevenLabsTest {

    private class RecordingSink : ElevenLabs.Sink {
        /** Every call, in order: `play:<bytes>` or `timed:<chars>`. */
        val events = mutableListOf<String>()
        val timings = mutableListOf<Pair<String, LongArray>>()
        private val chunks = mutableListOf<ByteArray>()
        @Volatile var finished = 0
        @Volatile var failure: String? = null
        val done = CountDownLatch(1)

        override fun play(pcm: ByteArray) {
            synchronized(this) {
                chunks += pcm
                events += "play:${pcm.size}"
            }
        }

        override fun timed(chars: String, atByte: LongArray) {
            synchronized(this) {
                timings += chars to atByte
                events += "timed:$chars"
            }
        }

        override fun finish() {
            finished++
            done.countDown()
        }

        override fun fail(message: String) {
            failure = message
            done.countDown()
        }

        fun chunkCount(): Int = synchronized(this) { chunks.size }
        fun audio(): ByteArray = synchronized(this) { chunks.fold(ByteArray(0)) { acc, c -> acc + c } }
    }

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun client() = ElevenLabs("key-123", "voice-abc", baseUrl = server.url("/").toString().trimEnd('/'))

    /**
     * One object of the stream as ElevenLabs sends it: the audio as base64 and the timing of
     * [chars] -- each starting at [from] seconds, [step] seconds apart -- or `"alignment": null`
     * when [chars] is null. Followed by the blank line the real stream puts between objects.
     */
    private fun obj(pcm: ByteArray, chars: String? = null, from: Double = 0.0, step: Double = 0.5): String {
        val o = JSONObject().put("audio_base64", pcm.toByteString().base64())
        if (chars == null) {
            o.put("alignment", JSONObject.NULL)
        } else {
            val cs = JSONArray()
            val starts = JSONArray()
            val ends = JSONArray()
            chars.forEachIndexed { i, c ->
                cs.put(c.toString())
                starts.put(from + i * step)
                ends.put(from + (i + 1) * step)
            }
            o.put(
                "alignment",
                JSONObject().put("characters", cs).put("character_start_times_seconds", starts).put("character_end_times_seconds", ends),
            )
        }
        o.put("normalized_alignment", JSONObject.NULL).put("quality_check", JSONObject.NULL)
        return o.toString() + "\n\n"
    }

    private fun pcm(size: Int, seed: Int = 0) = ByteArray(size) { ((it + seed) % 251).toByte() }

    @Test
    fun `asks for the line on the timestamps endpoint, from the fast model unless expressive`() {
        server.enqueue(MockResponse().setBody(""))
        server.enqueue(MockResponse().setBody(""))
        val plain = RecordingSink()
        client().stream("Hello there", plain)
        assertTrue(plain.done.await(5, TimeUnit.SECONDS))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/v1/text-to-speech/voice-abc/stream/with-timestamps?output_format=pcm_24000", r.path)
        assertEquals("key-123", r.getHeader("xi-api-key"))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(r.body.readUtf8())
        assertEquals("Hello there", body.getString("text"))
        assertEquals("eleven_flash_v2_5", body.getString("model_id"))

        val tagged = RecordingSink()
        client().stream("[sad] Hello there", tagged, expressive = true)
        assertTrue(tagged.done.await(5, TimeUnit.SECONDS))
        val body2 = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("[sad] Hello there", body2.getString("text"))
        assertEquals("eleven_v3", body2.getString("model_id"))
    }

    @Test
    fun `the audio arrives decoded, in order, then finish`() {
        val a = pcm(3000)
        val b = pcm(5000, 7)
        val c = pcm(1200, 11)
        val body = obj(a, "Hello") + obj(b) + obj(c, " there!", from = 1.0)
        server.enqueue(MockResponse().setChunkedBody(Buffer().writeUtf8(body), 1000))
        val sink = RecordingSink()
        client().stream("Hello there!", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertNull(sink.failure)
        assertEquals(1, sink.finished)
        assertEquals(3, sink.chunkCount())
        assertArrayEquals(a + b + c, sink.audio())
    }

    @Test
    fun `the timing comes before the audio it describes, as bytes of the stream`() {
        // Characters half a second apart from t = 1 s; the stream is 48 000 bytes a second.
        server.enqueue(MockResponse().setBody(obj(pcm(100), "[sad] Hi", from = 1.0)))
        val sink = RecordingSink()
        client().stream("[sad] Hi", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertNull(sink.failure)
        assertEquals(listOf("timed:[sad] Hi", "play:100"), sink.events)
        val (chars, atByte) = sink.timings.single()
        assertEquals("[sad] Hi", chars)
        assertArrayEquals(longArrayOf(48_000, 72_000, 96_000, 120_000, 144_000, 168_000, 192_000, 216_000), atByte)
    }

    @Test
    fun `a byte offset is a whole frame`() {
        // 0.012525 s is 601.2 bytes; a frame is two bytes, so the cue lands on 600.
        server.enqueue(MockResponse().setBody(obj(pcm(2), "a", from = 0.012525)))
        val sink = RecordingSink()
        client().stream("a", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertArrayEquals(longArrayOf(600), sink.timings.single().second)
    }

    @Test
    fun `an alignment that is null or empty sends no timing, and empty audio is not played`() {
        val empty = JSONObject()
            .put("audio_base64", "")
            .put(
                "alignment",
                JSONObject().put("characters", JSONArray()).put("character_start_times_seconds", JSONArray()).put("character_end_times_seconds", JSONArray()),
            )
            .toString() + "\n\n"
        server.enqueue(MockResponse().setBody(obj(pcm(10)) + empty))
        val sink = RecordingSink()
        client().stream("Hi", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertNull(sink.failure)
        assertEquals(listOf("play:10"), sink.events)   // the null alignment and the empty object sent nothing
        assertEquals(1, sink.finished)
    }

    @Test
    fun `an object without audio is timing only`() {
        server.enqueue(MockResponse().setBody("""{"alignment": null}""" + "\n" + obj(pcm(4), "ab")))
        val sink = RecordingSink()
        client().stream("ab", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertNull(sink.failure)
        assertEquals(listOf("timed:ab", "play:4"), sink.events)
    }

    @Test
    fun `a line that is not what ElevenLabs sends fails the line`() {
        val bad = listOf(
            "not json at all\n",
            obj(pcm(10)) + """{"audio_base64": "@@@ not base64 @@@", "alignment": null}""" + "\n",
            """{"audio_base64": "", "alignment": {"characters": ["a", "b"], "character_start_times_seconds": [0.0]}}""" + "\n",
            """{"audio_base64": 42, "alignment": null}""" + "\n",
        )
        for (body in bad) {
            server.enqueue(MockResponse().setBody(body))
            val sink = RecordingSink()
            client().stream("Hi", sink)
            assertTrue(body, sink.done.await(5, TimeUnit.SECONDS))
            assertEquals(body, "ElevenLabs: bad chunk", sink.failure)
            assertEquals(body, 0, sink.finished)
        }
    }

    @Test
    fun `an HTTP error is reported with ElevenLabs' message, and no audio`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}"""),
        )
        val sink = RecordingSink()
        client().stream("Hello", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertEquals("ElevenLabs 401: Invalid API key", sink.failure)
        assertEquals(0, sink.finished)
        assertEquals(0, sink.chunkCount())
    }

    @Test
    fun `a cancelled call reports nothing`() {
        // 200 KB at 40 KB/s: five seconds of body, cancelled after a fraction of it.
        server.enqueue(
            MockResponse().setBody(Buffer().write(ByteArray(200_000))).throttleBody(2000, 50, TimeUnit.MILLISECONDS),
        )
        val sink = RecordingSink()
        val call = client().stream("Hello", sink)
        Thread.sleep(300)
        call.cancel()
        assertFalse(sink.done.await(1, TimeUnit.SECONDS))
        assertNull(sink.failure)
        assertEquals(0, sink.finished)
    }

    @Test
    fun `a stream that dies mid-body is a failure, not a finish`() {
        val body = buildString { repeat(60) { append(obj(pcm(3000, it))) } }   // ~240 KB; the server sends half
        server.enqueue(
            MockResponse().setChunkedBody(Buffer().writeUtf8(body), 3000)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val sink = RecordingSink()
        client().stream("Hello", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertNotNull(sink.failure)
        assertEquals(0, sink.finished)
        assertTrue("some audio should have arrived first", sink.chunkCount() > 0)
    }

    @Test
    fun `a 2xx with no body at all just finishes`() {
        server.enqueue(MockResponse())
        val sink = RecordingSink()
        client().stream("Hello", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertEquals(1, sink.finished)
        assertNull(sink.failure)
        assertEquals(0, sink.chunkCount())
    }

    @Test
    fun `an error whose body is torn still fails instead of going silent`() {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("""{"detail":{"message":"boom"}}""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val sink = RecordingSink()
        client().stream("Hello", sink)
        assertTrue("the sink heard nothing", sink.done.await(5, TimeUnit.SECONDS))
        assertTrue("got: ${sink.failure}", sink.failure?.startsWith("ElevenLabs 500: ") == true)
        assertEquals(0, sink.finished)
    }

    @Test
    fun `error bodies in the shapes ElevenLabs uses`() {
        assertEquals("Invalid API key", ElevenLabs.errorMessage("""{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}"""))
        assertEquals("quota_exceeded", ElevenLabs.errorMessage("""{"detail":{"status":"quota_exceeded"}}"""))
        // A null message must fall through to the status: Android's org.json would stringify it as "null".
        assertEquals("quota_exceeded", ElevenLabs.errorMessage("""{"detail":{"status":"quota_exceeded","message":null}}"""))
        assertEquals("Not Found", ElevenLabs.errorMessage("""{"detail":"Not Found"}"""))
        assertEquals("<html>oops</html>", ElevenLabs.errorMessage("<html>oops</html>"))
        assertEquals("no details", ElevenLabs.errorMessage(""))
        assertEquals("no details", ElevenLabs.errorMessage(null))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.ElevenLabsTest' --console=plain -q`
Expected: compilation fails — `timed` overrides nothing, and `expressive` is not a parameter of `stream`.

- [ ] **Step 3: Replace `ElevenLabs.kt`**

```kotlin
package com.acmqu.acmo.voice

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs text-to-speech, streamed with timing. [stream] asks for a line as raw 24 kHz mono
 * 16-bit PCM -- exactly what [AudioOut] plays, so nothing is decoded but base64 -- and hands the
 * bytes to a [Sink] chunk by chunk as they arrive, on OkHttp's thread, each chunk preceded by
 * where in the audio the characters it speaks begin. That timing is how a `[tag]` in the text
 * changes the face at the right moment. The first chunk is usually heard within half a second
 * from the fast model, within two from the expressive one.
 *
 * The client keeps its TLS connection to ElevenLabs alive between lines, so
 * only the first line after a few minutes' quiet pays for the handshake.
 */
class ElevenLabs(
    private val apiKey: String,
    voiceId: String,
    private val http: OkHttpClient = defaultClient,
    baseUrl: String = BASE_URL,
) {
    /** Where the audio goes. Implementations must not throw: an exception here escapes on OkHttp's thread. */
    interface Sink {
        /** Some of the audio, in order. OkHttp's thread. */
        fun play(pcm: ByteArray)

        /**
         * The next characters of the text, in order, and the byte of the audio at which each begins,
         * delivered before the audio they describe. OkHttp's thread. A sink that does not care
         * about timing need not override it.
         */
        fun timed(chars: String, atByte: LongArray) {}

        /** The whole line has been delivered. */
        fun finish()

        /**
         * No more audio is coming: an HTTP error, a timeout, a dropped connection, a chunk that made
         * no sense. Not after a cancel, apart from one that lands mid-read.
         */
        fun fail(message: String)
    }

    private val url = "$baseUrl/v1/text-to-speech/$voiceId/stream/with-timestamps?output_format=$OUTPUT_FORMAT"

    /**
     * Starts streaming [text] into [sink]. An [expressive] line goes to the v3 model, which reads a
     * `[tag]` as direction; a plain one to the fast model, which would read it out loud. Cancel the
     * returned call to stop; the sink then hears nothing more.
     */
    fun stream(text: String, sink: Sink, expressive: Boolean = false): Call {
        val model = if (expressive) EXPRESSIVE_MODEL else FAST_MODEL
        val body = JSONObject().put("text", text).put("model_id", model).toString()
        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", apiKey)
            .post(body.toRequestBody(JSON))
            .build()
        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                Log.w(TAG, "request failed", e)
                sink.fail("ElevenLabs: ${e.message ?: e.javaClass.simpleName}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    if (!r.isSuccessful) {
                        // Bounded and exception-safe: a torn or slow error body must still end in fail(),
                        // because an exception escaping this callback is swallowed by OkHttp, not reported.
                        val why = errorMessage(runCatching { r.peekBody(ERROR_BODY_BYTES).string() }.getOrNull())
                        Log.w(TAG, "HTTP ${r.code}: $why")
                        sink.fail("ElevenLabs ${r.code}: $why")
                        return
                    }
                    Log.d(TAG, "${r.protocol} ${r.code} $model")
                    val reader = r.body?.charStream()?.buffered() ?: run {
                        if (!call.isCanceled()) sink.finish()
                        return
                    }
                    // One JSON object per line, a blank line between them; each carries some audio and,
                    // usually, the timing of the characters it speaks.
                    var total = 0L
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            total += deliver(line, sink)
                        }
                        Log.d(TAG, "body ended: $total bytes, ${total / (AudioOut.BYTES_PER_MS * 1000)} s of audio")
                    } catch (e: IOException) {
                        if (call.isCanceled()) return
                        Log.w(TAG, "stream ended early", e)
                        sink.fail("ElevenLabs: ${e.message ?: e.javaClass.simpleName}")
                        return
                    } catch (e: BadChunk) {
                        if (call.isCanceled()) return
                        Log.w(TAG, "bad chunk: ${e.message}")
                        sink.fail("ElevenLabs: bad chunk")
                        return
                    }
                    if (!call.isCanceled()) sink.finish()
                }
            }
        })
        return call
    }

    /** A line of the stream that is not what ElevenLabs documents. */
    private class BadChunk(message: String) : Exception(message)

    /** One object of the stream: its timing to the sink first, then its audio. Returns the audio's size in bytes. */
    private fun deliver(line: String, sink: Sink): Int {
        val o = try {
            JSONObject(line)
        } catch (_: JSONException) {
            throw BadChunk("not JSON: ${line.take(80)}")
        }
        o.optJSONObject("alignment")?.let { alignment ->
            val chars = alignment.optJSONArray("characters") ?: throw BadChunk("an alignment without characters")
            val starts = alignment.optJSONArray("character_start_times_seconds")
                ?: throw BadChunk("an alignment without start times")
            if (chars.length() != starts.length()) throw BadChunk("${chars.length()} characters, ${starts.length()} times")
            if (chars.length() > 0) {
                val text = StringBuilder(chars.length())
                val atByte = LongArray(chars.length())
                for (i in 0 until chars.length()) {
                    text.append(chars.optString(i))
                    atByte[i] = (starts.optDouble(i, 0.0) * BYTES_PER_SECOND).toLong() and 1L.inv()
                }
                sink.timed(text.toString(), atByte)
            }
        }
        // Timing without audio is not something ElevenLabs sends today, and not worth failing a line over.
        val audio = when (val a = o.opt("audio_base64")) {
            null, JSONObject.NULL -> return 0
            is String -> a
            else -> throw BadChunk("audio_base64 is not a string")
        }
        if (audio.isEmpty()) return 0
        val pcm = audio.decodeBase64()?.toByteArray() ?: throw BadChunk("audio that is not base64")
        if (pcm.isNotEmpty()) sink.play(pcm)
        return pcm.size
    }

    companion object {
        private const val TAG = "ElevenLabs"
        const val BASE_URL = "https://api.elevenlabs.io"

        /** The lowest-latency model (about 75 ms), 32 languages including Arabic. Reads a `[tag]` out loud. */
        const val FAST_MODEL = "eleven_flash_v2_5"

        /** Eleven v3: takes a `[tag]` as direction for the voice. Starts about a second later than [FAST_MODEL]. */
        const val EXPRESSIVE_MODEL = "eleven_v3"

        /** Raw 24 kHz mono 16-bit PCM: what AudioOut plays, available on every tier. */
        const val OUTPUT_FORMAT = "pcm_24000"

        /** Jessica -- "playful, bright, warm" -- one of ElevenLabs' stock voices. */
        const val DEFAULT_VOICE_ID = "cgSgspJ2msm6clMCkdW9"

        /** Seconds in the timing to bytes of the audio: 24 000 frames of two bytes a second. */
        const val BYTES_PER_SECOND = AudioOut.BYTES_PER_MS * 1000.0

        /** As much of an error body as is worth reading; the message is truncated to 200 characters anyway. */
        const val ERROR_BODY_BYTES = 4096L

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * ElevenLabs' error body: `{"detail": {"status": ..., "message": ...}}`, or `{"detail": "..."}`,
         * or not JSON at all. Strings only: Android's org.json turns a JSON null into the word "null".
         */
        fun errorMessage(body: String?): String {
            if (body.isNullOrBlank()) return "no details"
            return try {
                when (val detail = JSONObject(body).opt("detail")) {
                    is JSONObject -> (detail.opt("message") as? String)?.ifBlank { null }
                        ?: (detail.opt("status") as? String)?.ifBlank { null }
                        ?: detail.toString().take(200)
                    is String -> detail
                    else -> body.take(200)
                }
            } catch (_: JSONException) {
                body.take(200)
            }
        }

        /** Short timeouts: a line that stalls for 15 s is dead, and the queue should move on. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
```

`CHUNK_BYTES` and `MODEL` are gone; nothing else referenced them (check with `grep -rn "CHUNK_BYTES\|ElevenLabs.MODEL" app/src`). If the compiler refuses `const` for `BYTES_PER_SECOND` (a `Long` times a `Double` literal), make it a plain `val`.

- [ ] **Step 4: Run all the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q`
Expected: silence (green): 48 − 9 + 13 + 7 + 4 = 63 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acmqu/acmo/voice/ElevenLabs.kt app/src/test/java/com/acmqu/acmo/ElevenLabsTest.kt
git commit -m "feat(voice): ElevenLabs streamed with timing -- v3 for a tagged line, Flash for a plain one

The stream-with-timestamps endpoint says where in the audio each character
begins, tags included; the sink hears that before the audio it describes.
Base64 through Okio: java.util.Base64 needs API 26 and android.util.Base64
is a stub in unit tests.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `/state` says which face is on screen

**Files:**
- Modify: `app/src/main/java/com/acmqu/acmo/remote/Line.kt` (the `Snapshot` class at the end)
- Modify: `app/src/main/java/com/acmqu/acmo/Brain.kt` (`snapshot()`, one line)
- Test: `app/src/test/java/com/acmqu/acmo/LineTest.kt`, `app/src/test/java/com/acmqu/acmo/RemoteServerTest.kt`

- [ ] **Step 1: Update the tests**

In `LineTest.kt`, the two snapshot tests become:

```kotlin
    @Test
    fun `a quiet snapshot has nulls, not missing keys`() {
        val json = Snapshot(Brain.State.IDLE, Expression.IDLE, null, emptyList(), null).toJson()
        assertEquals("idle", json.getString("state"))
        assertEquals("idle", json.getString("face"))
        assertTrue(json.has("line"))
        assertTrue(json.isNull("line"))
        assertEquals(0, json.getJSONArray("queue").length())
        assertTrue(json.has("error"))
        assertTrue(json.isNull("error"))
    }

    @Test
    fun `a busy snapshot lists the face on screen, the line, the queue and the error`() {
        val json = Snapshot(
            Brain.State.SPEAKING,
            Expression.SAD,   // a tag moved the face; the line started happy
            Entry(3, Line("Hi", Expression.HAPPY)),
            listOf(Entry(4, Line("Bye", Expression.SAD)), Entry(5, Line("Wait", Expression.ANGRY))),
            Failure(2, "ElevenLabs 401: Invalid API key"),
        ).toJson()
        assertEquals("speaking", json.getString("state"))
        assertEquals("sad", json.getString("face"))
        val line = json.getJSONObject("line")
        assertEquals(3, line.getInt("id"))
        assertEquals("Hi", line.getString("text"))
        assertEquals("happy", line.getString("feeling"))
        val queue = json.getJSONArray("queue")
        assertEquals(2, queue.length())
        assertEquals(4, queue.getJSONObject(0).getInt("id"))
        assertEquals("sad", queue.getJSONObject(0).getString("feeling"))
        assertEquals("Wait", queue.getJSONObject(1).getString("text"))
        val error = json.getJSONObject("error")
        assertEquals(2, error.getInt("id"))
        assertEquals("ElevenLabs 401: Invalid API key", error.getString("message"))
    }
```

In `RemoteServerTest.kt`, `FakeHost`'s default becomes `var snapshot = Snapshot(Brain.State.IDLE, Expression.IDLE, null, emptyList(), null)`, and the `state is the snapshot as JSON` test becomes:

```kotlin
    @Test
    fun `state is the snapshot as JSON`() {
        host.snapshot = Snapshot(
            Brain.State.SPEAKING,
            Expression.SAD,
            Entry(3, Line("Hi", Expression.HAPPY)),
            listOf(Entry(4, Line("Bye", Expression.SAD))),
            Failure(2, "ElevenLabs 401: Invalid API key"),
        )
        val r = request("GET", "/state")
        assertEquals(200, r.code)
        val json = JSONObject(r.body)
        assertEquals("speaking", json.getString("state"))
        assertEquals("sad", json.getString("face"))
        assertEquals(3, json.getJSONObject("line").getInt("id"))
        assertEquals("sad", json.getJSONArray("queue").getJSONObject(0).getString("feeling"))
        assertEquals("ElevenLabs 401: Invalid API key", json.getJSONObject("error").getString("message"))
    }
```

Search both test files for any other `Snapshot(` and give each the new second argument (`Expression.IDLE`).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.LineTest' --console=plain -q`
Expected: compilation fails — `Snapshot` has no such constructor.

- [ ] **Step 3: Change `Snapshot` and `Brain.snapshot()`**

In `Line.kt`, replace the `Snapshot` class:

```kotlin
/**
 * What `GET /state` reports: [face] is the expression on screen right now (it moves during a
 * tagged line); [line] is the remote line playing, null during a Gemini reply or when quiet.
 */
data class Snapshot(val state: Brain.State, val face: Expression, val line: Entry?, val queue: List<Entry>, val error: Failure?) {
    fun toJson(): JSONObject = JSONObject()
        .put("state", state.name.lowercase())
        .put("face", face.label)
        .put("line", line?.toJson() ?: JSONObject.NULL)
        .put("queue", JSONArray().apply { queue.forEach { put(it.toJson()) } })
        .put("error", error?.toJson() ?: JSONObject.NULL)
}
```

In `Brain.kt`, `snapshot()` becomes:

```kotlin
    override fun snapshot(): Snapshot = Snapshot(state, face.expression, playing, queue.toList(), lastFailure)
```

- [ ] **Step 4: Run all the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q`
Expected: silence (green), still 63 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/acmqu/acmo/remote/Line.kt app/src/main/java/com/acmqu/acmo/Brain.kt app/src/test/java/com/acmqu/acmo/LineTest.kt app/src/test/java/com/acmqu/acmo/RemoteServerTest.kt
git commit -m "feat(remote): /state says which face is on screen

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: `Brain` — the opening face, the cues, the model

**Files:**
- Modify: `app/src/main/java/com/acmqu/acmo/Brain.kt` (`playNext()`, around lines 505–541, and the imports)

There is no JVM test for `Brain` (it needs Android); the build is the check here, and Task 8 exercises it on the tablet.

- [ ] **Step 1: Add the imports**

Next to `import com.acmqu.acmo.remote.Failure` and the other `remote` imports:

```kotlin
import com.acmqu.acmo.remote.FaceCues
import com.acmqu.acmo.remote.Tags
```

- [ ] **Step 2: Replace the end of `playNext()`**

From `playing = entry` to the end of the function, the body becomes:

```kotlin
        playing = entry
        faces = emptyList()
        faceIndex = 0
        lastFailure = null
        state = State.SPEAKING
        // The operator's face until the first face tag -- or that tag's face, if the line opens with one:
        // the v3 model takes a second or two to start, and the face should not flip when it does.
        val text = entry.line.text
        val tags = Tags.faces(text)
        val opening = tags.firstOrNull()?.takeIf { it.index == 0 }?.feeling ?: entry.line.feeling
        val expressive = Tags.hasTags(text)
        face.setExpression(opening)
        Log.i(TAG, "line #${entry.id} (${opening.label}${if (expressive) ", expressive" else ""}): \"$text\"")
        // The player is made here, on the main thread, so the socket thread only ever feeds this one.
        val o = player()
        val cues = FaceCues(tags)
        call = voice.stream(text, expressive = expressive, sink = object : ElevenLabs.Sink {
            override fun timed(chars: String, atByte: LongArray) {
                // OkHttp's thread. A cue is registered before the audio it points into reaches the player,
                // and guarded by identity: a stopped line's cue must never touch the next line's face.
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

            override fun fail(message: String) {
                main.post { lineFailed(entry, o, message) }
            }
        })
    }
```

Everything above `playing = entry` in the function (the dequeue, the `eleven ?: run { … }` guard, cancelling the jobs, pausing the mic, cancelling a late `out`) stays exactly as it is. `AudioOut` is already imported in `Brain.kt`.

- [ ] **Step 3: Build and run the tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain -q`
Expected: silence (green). If the compiler complains about `o.cue`, the signature in `AudioOut.kt` is `fun cue(atByte: Long, action: () -> Unit)` — the trailing lambda is right.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/acmqu/acmo/Brain.kt
git commit -m "feat: a [tag] that names a face changes the face when the voice gets there

A tagged line goes to the expressive model; the face the line opens with is
the first tag's when the text starts with one, else the operator's.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: The console — the words, and the live face

**Files:**
- Modify: `remote/lib/acmo.ts`
- Modify: `remote/app/page.tsx`
- Modify: `remote/app/globals.css`

- [ ] **Step 1: `lib/acmo.ts` — `State.face` and `TAG_WORDS`**

Change the `State` type and add the table after `FEELINGS`/`Feeling`:

```ts
export type State = {
  state: "booting" | "idle" | "listening" | "thinking" | "speaking";
  /** The face on screen right now; during a tagged line it moves. Older tablets do not send it. */
  face?: Feeling;
  /** The remote line being spoken; null during a wake-word reply, or when quiet. */
  line: Entry | null;
  queue: Entry[];
  error: { id: number; message: string } | null;
};

/**
 * The words that change the face when written in brackets -- the label first, then three more
 * ways of saying it. Mirrors Tags.WORDS in the app (remote/Tags.kt), which is the source of truth.
 * Any other [word] is a voice-only tag: ElevenLabs takes the tone, the face stays.
 */
export const TAG_WORDS: Record<Feeling, readonly string[]> = {
  idle: ["idle"],
  surprised: ["surprised", "gasps", "shocked", "amazed"],
  sad: ["sad", "crying", "gloomy", "disappointed"],
  happy: ["happy", "laughs", "giggles", "cheerful"],
  angry: ["angry", "shouting", "furious", "growls"],
  passionate: ["passionate", "loving", "romantic", "dramatic"],
  annoyed: ["annoyed", "sarcastic", "groans", "frustrated"],
  excited: ["excited", "thrilled", "enthusiastic", "energetic"],
};
```

- [ ] **Step 2: `app/page.tsx` — the live face, the words row, the placeholder**

Import `TAG_WORDS` with the others from `@/lib/acmo`. `describe` shows the live face:

```ts
    case "speaking":
      return s.line ? `speaking · ${s.face ?? s.line.feeling}` : "speaking (not a remote line)";
```

After the `hush` callback, add the insertion (it needs `text` and `setText`, which exist):

```ts
  // A word from the row under the faces, dropped into the line as a tag, at the cursor.
  const insert = useCallback(
    (word: string) => {
      const el = box.current;
      const tag = `[${word}] `;
      const start = el?.selectionStart ?? text.length;
      const end = el?.selectionEnd ?? start;
      setText(text.slice(0, start) + tag + text.slice(end));
      // The caret goes after the tag once React has rendered the new value.
      requestAnimationFrame(() => {
        if (!el) return;
        el.focus();
        el.setSelectionRange(start + tag.length, start + tag.length);
        el.style.height = "auto";
        el.style.height = `${el.scrollHeight}px`;
      });
    },
    [text],
  );
```

Right after the `<div className="faces" …>…</div>` block, add the row:

```tsx
        <div className="words">
          {TAG_WORDS[feeling].map((w) => (
            <button key={w} type="button" className="word" onClick={() => insert(w)} title="insert at the cursor">
              [{w}]
            </button>
          ))}
          <span className="hint">in the line changes the voice and the face; any other [tag] only the voice</span>
        </div>
```

And the textarea's placeholder becomes:

```tsx
          placeholder="What should ACMO say? A [tag] changes the tone; a face word changes the face too."
```

- [ ] **Step 3: `app/globals.css` — the row**

After the `.faces` rule:

```css
/* The selected face's tag words: small, in the eyebrow's voice, so the faces above stay the loud row. */
.words {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 10px;
}

.word {
  font: inherit;
  font-size: 12px;
  color: var(--ink);
  background: transparent;
  border: 2px solid var(--gray);
  border-radius: 9999px;
  padding: 2px 10px;
  cursor: pointer;
}

.word:hover,
.word:focus-visible {
  border-color: var(--teal);
  outline: none;
}

.words .hint {
  font-size: 12px;
}
```

- [ ] **Step 4: Lint, type-check, build**

Run: `cd remote && npm run lint && npx tsc --noEmit && npm run build`
Expected: no lint errors, no type errors, `✓ Compiled successfully`. If `react-hooks/refs` or `react-hooks/set-state-in-effect` complains, the fix is to keep the ref reads inside the click handler (they are) and never in an effect.

- [ ] **Step 5: Look at it**

Run: `cd remote && npm run dev` and open http://localhost:3000. Click `happy` then `[laughs]`: the textarea gains `[laughs] ` at the cursor and keeps the focus. Switch faces: the row changes. Stop the dev server afterwards (`Ctrl-C`, or `pkill -f "next dev"`). No `AGENTS.md`/`CLAUDE.md` should appear in `remote/` (`next.config.ts` has `agentRules: false`); if one does, delete it.

- [ ] **Step 6: Commit**

```bash
git add remote/lib/acmo.ts remote/app/page.tsx remote/app/globals.css
git commit -m "feat(remote): the console knows the tag words and shows the live face

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Docs

**Files:**
- Modify: `README.md` (the *What's here* tree, the *Remote console* section, the *Tuning* table)
- Modify: `remote/README.md`

- [ ] **Step 1: The tree**

Under `│   │   ├── ElevenLabs.kt` change the description to `the console's voice: ElevenLabs text-to-speech, streamed as 24 kHz PCM with timing`. The `remote/` package becomes:

```
│   └── remote/
│       ├── RemoteServer.kt      the console's way in: POST /say, /stop and GET /state on port 8765
│       ├── Line.kt              a line and its feeling; the wire types
│       ├── Tags.kt              which [words] name a face, and where they sit in a line
│       └── FaceCues.kt          from ElevenLabs' character timing to "this face at this byte"
```

- [ ] **Step 2: The *Remote console* section**

Replace its first paragraph with:

```markdown
`remote/` is a small Next.js page for a laptop on the same network: type a
line, pick one of the eight faces, and ACMO says it in an ElevenLabs voice.
The text is spoken word for word — Gemini is not involved — and streamed:
ElevenLabs returns raw 24 kHz PCM, with the moment each character is spoken,
and it goes straight into the same player as Gemini's voice. Measured on the
Redmi Pad 2: the first sound comes 0.4 s after Enter on a warm connection with
the fast model, and about 1.5 s for the first line after a few minutes' quiet.
```

The `GET /state` row of the routes table becomes:

```markdown
| `GET /state` | | `{"state", "face", "line", "queue", "error"}` — `face` is the expression on screen right now |
```

After the paragraph that begins `Lines play back to back with the chosen face`, add:

```markdown
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
```

- [ ] **Step 3: The *Tuning* table**

Replace the `ElevenLabs.MODEL` row with these two:

```markdown
| `ElevenLabs.FAST_MODEL` / `EXPRESSIVE_MODEL` | The model for a plain line and for one with a `[tag]`. Flash starts in under half a second and reads a tag out loud; v3 takes it as direction and starts about a second later. | `eleven_flash_v2_5` / `eleven_v3` |
| `Tags.WORDS` | The bracketed words that change the face. Add a synonym here, and to `TAG_WORDS` in `remote/lib/acmo.ts` so the console shows it. | the table above |
```

- [ ] **Step 4: `remote/README.md`**

Add a bullet before the *There is no authentication* one:

```markdown
- A word in brackets is a tag for the voice — `[whispers]`, `[laughs]`, `[sad]`.
  The eight face names and their synonyms (the row under the face pills; click
  one to insert it) change the face too, right when the voice gets there. A
  tagged line uses ElevenLabs' v3 model and starts about a second later.
```

- [ ] **Step 5: Commit**

```bash
git add README.md remote/README.md
git commit -m "docs: audio tags -- the words, the two models, the live face in /state

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: On the tablet (the main session, with the user)

Not for a subagent: it needs the Redmi (`adb -s b15f152c`), the console, and an ear.

- [ ] Build and install: `./gradlew :app:installDebug --console=plain -q`; `adb -s b15f152c shell am start -n com.acmqu.acmo/.MainActivity`; `adb -s b15f152c forward tcp:8765 tcp:8765`; watch `adb -s b15f152c logcat -s Brain RemoteServer ElevenLabs AudioOut`.
- [ ] A three-tag line, e.g. `[excited] We won the hackathon! [sad] But the pizza is gone. [laughs] Kidding, there is more.`: the log shows `expressive`, then `sad at N ms` and `happy at M ms`; the face changes at each tag; the tags are not spoken; no gap in the audio.
- [ ] A line starting with a face tag shows that face from the start, never the pill's.
- [ ] A voice-only line, `[whispers] can you hear me?`: the tone changes, the face is the pill's throughout.
- [ ] `[idle] Nothing much today.`: is "idle" spoken?
- [ ] Latency to the first sound, warm, from the `say #` log line to `line #N playing`: a plain line (Flash) and a tagged line (v3). Record both in the README.
- [ ] Stop (Esc) in the middle of a tagged line: the face goes idle and no cue fires afterwards.
- [ ] `GET /state` during a tagged line shows the moving `face`; the console's status line follows it; clicking a word inserts it at the cursor.
- [ ] Write the results into the spec as `## 9. Implementation notes`, correct the README's numbers, commit.
