# ElevenLabs Remote Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A browser page where an operator types a line and picks a face, and the tablet says it in an ElevenLabs voice, streamed, with the face acting it out.

**Architecture:** The tablet app gains a small NanoHTTPD server (`POST /say`, `POST /stop`, `GET /state`) that hands lines to `Brain`, which queues them and streams each one from ElevenLabs' HTTP streaming endpoint as raw 24 kHz PCM straight into the existing `AudioOut`. The console is a client-only Next.js page in `software/remote/` that calls the tablet directly (`http://localhost:8765` via `adb forward`, or the tablet's LAN address) and polls `/state` twice a second.

**Tech Stack:** Kotlin (Android, Views), NanoHTTPD 2.3.1, OkHttp 4.12 (+ MockWebServer for tests), JUnit 4, `org.json`; Next.js 16.3 / React 19 / TypeScript, plain CSS, npm.

**Spec:** `docs/superpowers/specs/2026-09-20-elevenlabs-remote-console-design.md`. One naming deviation from it: the `/say` body parser is `Say.parse(json)` returning `Say(line, now)`, because `now` lives in the same body as the line.

---

## Conventions for every task

- All paths are relative to `software/` (the app's own git repo) unless they start with `../`.
- Gradle needs Studio's JDK. Before any `./gradlew`:
  ```sh
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
- The unit tests: `./gradlew :app:testDebugUnitTest --console=plain -q`. Silence means green; a failure prints the failing test and `BUILD FAILED`. One class only: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.LineTest' --console=plain -q`.
- The build: `./gradlew :app:assembleDebug --console=plain -q`.
- The tablet is the Redmi Pad 2, adb serial `b15f152c`. An emulator (`emulator-5554`) may also be attached: always set `ANDROID_SERIAL=b15f152c` or pass `-s b15f152c`. `adb` lives at `~/Library/Android/sdk/platform-tools/adb` (not on PATH).
- Commit messages: the repo's style is `type: what it does`, lower case, a short body when it helps. End the body with `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`.
- `local.properties` holds real keys. Never print it, never `cat` it, never commit it (it is gitignored).
- Do not touch `../hardware/`.

## File map

| File | Responsibility |
| --- | --- |
| `gradle/libs.versions.toml`, `app/build.gradle.kts`, `NOTICE.md` | NanoHTTPD + MockWebServer, the two keys into `BuildConfig`, JVM tests may call `Log` |
| `app/src/main/java/com/acmqu/acmo/remote/Line.kt` (new) | `Line`, `Say` (the `/say` body parser), `Entry`, `Said`, `Failure`, `Snapshot` and their JSON |
| `app/src/main/java/com/acmqu/acmo/voice/ElevenLabs.kt` (new) | one call: stream a line's audio into a `Sink` |
| `app/src/main/java/com/acmqu/acmo/remote/RemoteServer.kt` (new) | the HTTP routes, CORS, main-thread hop into a `Host` |
| `app/src/main/java/com/acmqu/acmo/settings/{Settings,SettingsPanel}.kt`, `res/layout/view_settings.xml`, `res/values/strings.xml` | the Remote Off/On row and address line |
| `app/src/main/java/com/acmqu/acmo/Brain.kt` | the queue: `say`, `hush`, `snapshot`, `interrupt`, `playNext`, `lineEnded`, `lineFailed`; `goIdle` drains the queue |
| `app/src/main/java/com/acmqu/acmo/MainActivity.kt` | owns the server; builds `ElevenLabs` from `BuildConfig` |
| `app/src/test/java/com/acmqu/acmo/{LineTest,ElevenLabsTest,RemoteServerTest}.kt` (new) | JVM tests |
| `remote/` (new) | the Next.js console: `lib/acmo.ts`, `app/layout.tsx`, `app/page.tsx`, `app/globals.css`, `README.md` |
| `README.md`, `../CONNECT.md`, `../README.md` | docs |

---

### Task 1: Build configuration — keys, NanoHTTPD, MockWebServer

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `NOTICE.md`

- [ ] **Step 1: Add the two libraries to the version catalog**

In `gradle/libs.versions.toml`, under `[versions]`, after the `okhttp = "4.12.0"` line add:

```toml
nanohttpd = "2.3.1"
```

Under `[libraries]`, after the `okhttp = { ... }` line add:

```toml
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
nanohttpd = { group = "org.nanohttpd", name = "nanohttpd", version.ref = "nanohttpd" }
```

- [ ] **Step 2: Read the ElevenLabs keys into BuildConfig**

In `app/build.gradle.kts`, replace this block:

```kotlin
// The Gemini key never enters the repo: it is read from local.properties (gitignored)
// and baked into BuildConfig. That also means a built APK contains it -- do not hand
// APKs around.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val geminiApiKey: String = localProps.getProperty("GEMINI_API_KEY")
    ?: System.getenv("GEMINI_API_KEY")
    ?: ""
```

with:

```kotlin
// The API keys never enter the repo: they are read from local.properties (gitignored)
// and baked into BuildConfig. That also means a built APK contains them -- do not hand
// APKs around.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String = localProps.getProperty(name) ?: System.getenv(name) ?: ""
val geminiApiKey = secret("GEMINI_API_KEY")
val elevenLabsApiKey = secret("ELEVENLABS_API_KEY")
val elevenLabsVoiceId = secret("ELEVENLABS_VOICE_ID")
```

Replace the single `buildConfigField` line inside `defaultConfig`:

```kotlin
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
```

with:

```kotlin
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        // The remote console's voice. A blank voice means ElevenLabs.DEFAULT_VOICE_ID.
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"$elevenLabsApiKey\"")
        buildConfigField("String", "ELEVENLABS_VOICE_ID", "\"$elevenLabsVoiceId\"")
```

After the `compileOptions { ... }` block (still inside `android { ... }`), add:

```kotlin
    testOptions {
        // android.util.Log returns 0 instead of throwing "not mocked", so the server and
        // the ElevenLabs client, which log, can be unit tested on the JVM.
        unitTests.isReturnDefaultValues = true
    }
```

In `dependencies { ... }`, after `implementation(libs.okhttp)` add:

```kotlin
    // The remote console's server on the tablet.
    implementation(libs.nanohttpd)
```

and after `testImplementation(libs.json)` add:

```kotlin
    // A local HTTP server that plays ElevenLabs in the client's tests.
    testImplementation(libs.okhttp.mockwebserver)
```

- [ ] **Step 3: Record NanoHTTPD in the third-party notices**

In `NOTICE.md`, change the OkHttp row's "Where" cell from `HTTP to Gemini` to `HTTP to Gemini and ElevenLabs`, and add a row after it:

```markdown
| [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) 2.3.1 | the remote console's server on the tablet | BSD-3-Clause |
```

- [ ] **Step 4: Build, and check the fields exist**

Run:

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug --console=plain -q && grep -c ELEVENLABS app/build/generated/source/buildConfig/debug/com/acmqu/acmo/BuildConfig.java
```

Expected: no Gradle output, then `2` (both fields generated). Do not print the file itself: it contains the key.

- [ ] **Step 5: Commit**

```sh
git add gradle/libs.versions.toml app/build.gradle.kts NOTICE.md
git commit -m "build: ElevenLabs keys in BuildConfig; NanoHTTPD, MockWebServer

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `Line.kt` — the wire types and the `/say` body parser

**Files:**
- Create: `app/src/main/java/com/acmqu/acmo/remote/Line.kt`
- Test: `app/src/test/java/com/acmqu/acmo/LineTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/acmqu/acmo/LineTest.kt`:

```kotlin
package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.remote.Entry
import com.acmqu.acmo.remote.Failure
import com.acmqu.acmo.remote.Line
import com.acmqu.acmo.remote.Said
import com.acmqu.acmo.remote.Say
import com.acmqu.acmo.remote.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The body of POST /say, and the JSON the other routes answer with. */
class LineTest {

    @Test
    fun `reads the text, the feeling and now`() {
        val s = Say.parse("""{"text": "Hello there!", "feeling": "excited", "now": true}""")
        assertEquals(Line("Hello there!", Expression.EXCITED), s.line)
        assertTrue(s.now)
    }

    @Test
    fun `feeling defaults to happy, now to false, and the text is trimmed`() {
        val s = Say.parse("""{"text": "  Hi  "}""")
        assertEquals(Line("Hi", Expression.HAPPY), s.line)
        assertFalse(s.now)
    }

    @Test
    fun `feelings are the face's labels, whatever the case or spacing`() {
        assertEquals(Expression.PASSIONATE, Say.parse("""{"text": "x", "feeling": " Passionate "}""").line.feeling)
        assertEquals(Expression.IDLE, Say.parse("""{"text": "x", "feeling": "IDLE"}""").line.feeling)
    }

    @Test
    fun `the longest line is accepted`() {
        val text = "a".repeat(Line.MAX_CHARS)
        assertEquals(text, Say.parse("""{"text": "$text"}""").line.text)
    }

    @Test
    fun `refuses what cannot be said, with the reason`() {
        assertRefused("""{"text": ""}""", "text is empty")
        assertRefused("""{"text": "   "}""", "text is empty")
        assertRefused("""{"feeling": "happy"}""", "text is empty")
        assertRefused("""{"text": "${"a".repeat(Line.MAX_CHARS + 1)}"}""", "text is longer than 2000 characters")
        assertRefused(
            """{"text": "x", "feeling": "smug"}""",
            "unknown feeling \"smug\"; one of idle surprised sad happy angry passionate annoyed excited",
        )
        assertRefused("not json", "the body is not JSON")
        assertRefused("", "the body is not JSON")
        assertRefused("[1, 2]", "the body is not JSON")
    }

    private fun assertRefused(body: String, why: String) {
        try {
            Say.parse(body)
            fail("accepted: $body")
        } catch (e: IllegalArgumentException) {
            assertEquals(why, e.message)
        }
    }

    @Test
    fun `said is id and queued`() {
        val json = Said(7, 2).toJson()
        assertEquals(7, json.getInt("id"))
        assertEquals(2, json.getInt("queued"))
    }

    @Test
    fun `a quiet snapshot has nulls, not missing keys`() {
        val json = Snapshot(Brain.State.IDLE, null, emptyList(), null).toJson()
        assertEquals("idle", json.getString("state"))
        assertTrue(json.has("line"))
        assertTrue(json.isNull("line"))
        assertEquals(0, json.getJSONArray("queue").length())
        assertTrue(json.has("error"))
        assertTrue(json.isNull("error"))
    }

    @Test
    fun `a busy snapshot lists the line, the queue and the error`() {
        val json = Snapshot(
            Brain.State.SPEAKING,
            Entry(3, Line("Hi", Expression.HAPPY)),
            listOf(Entry(4, Line("Bye", Expression.SAD)), Entry(5, Line("Wait", Expression.ANGRY))),
            Failure(2, "ElevenLabs 401: Invalid API key"),
        ).toJson()
        assertEquals("speaking", json.getString("state"))
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
}
```

- [ ] **Step 2: Run them to see them fail to compile**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.LineTest' --console=plain -q`
Expected: `BUILD FAILED` with `Unresolved reference 'remote'` (the package does not exist yet).

- [ ] **Step 3: Write `Line.kt`**

Create `app/src/main/java/com/acmqu/acmo/remote/Line.kt`:

```kotlin
package com.acmqu.acmo.remote

import com.acmqu.acmo.Brain
import com.acmqu.acmo.face.Expression
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One line for ACMO to say, and the face it makes while saying it. */
data class Line(val text: String, val feeling: Expression) {
    companion object {
        /** Longer than any line an operator types; far under ElevenLabs' limit. */
        const val MAX_CHARS = 2000
        val DEFAULT_FEELING = Expression.HAPPY
    }
}

/** The body of `POST /say`: the line, and whether it cuts in ([now]) or waits its turn. */
data class Say(val line: Line, val now: Boolean) {
    companion object {
        /** Throws [IllegalArgumentException] whose message is the 400 reply's `error`. */
        fun parse(json: String): Say {
            val o = try {
                JSONObject(json)
            } catch (_: JSONException) {
                throw IllegalArgumentException("the body is not JSON")
            }
            val text = o.optString("text", "").trim()
            require(text.isNotEmpty()) { "text is empty" }
            require(text.length <= Line.MAX_CHARS) { "text is longer than ${Line.MAX_CHARS} characters" }
            val label = o.optString("feeling", "")
            val feeling = if (label.isBlank()) {
                Line.DEFAULT_FEELING
            } else {
                Expression.fromLabel(label) ?: throw IllegalArgumentException(
                    "unknown feeling \"${label.trim()}\"; one of ${Expression.entries.joinToString(" ") { it.label }}",
                )
            }
            return Say(Line(text, feeling), o.optBoolean("now", false))
        }
    }
}

/** A line once the Brain has it: numbered, so the console can tell them apart. */
data class Entry(val id: Int, val line: Line) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", line.text)
        .put("feeling", line.feeling.label)
}

/** The reply to `POST /say`: the line's id, and how many remote lines are ahead of it. */
data class Said(val id: Int, val queued: Int) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("queued", queued)
}

/** The last line that could not be spoken, and why. */
data class Failure(val id: Int, val message: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("message", message)
}

/** What `GET /state` reports: [line] is the remote line playing, null during a Gemini reply or when quiet. */
data class Snapshot(val state: Brain.State, val line: Entry?, val queue: List<Entry>, val error: Failure?) {
    fun toJson(): JSONObject = JSONObject()
        .put("state", state.name.lowercase())
        .put("line", line?.toJson() ?: JSONObject.NULL)
        .put("queue", JSONArray().apply { queue.forEach { put(it.toJson()) } })
        .put("error", error?.toJson() ?: JSONObject.NULL)
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.LineTest' --console=plain -q`
Expected: no output (all green). If `refuses what cannot be said` fails on `[1, 2]`: `JSONObject("[1, 2]")` must throw `JSONException` — it does with `org.json` 20250107 ("A JSONObject text must begin with '{'").

- [ ] **Step 5: Commit**

```sh
git add app/src/main/java/com/acmqu/acmo/remote/Line.kt app/src/test/java/com/acmqu/acmo/LineTest.kt
git commit -m "feat(remote): the wire types and the /say body parser

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: `ElevenLabs.kt` — streamed text-to-speech into a sink

**Files:**
- Create: `app/src/main/java/com/acmqu/acmo/voice/ElevenLabs.kt`
- Test: `app/src/test/java/com/acmqu/acmo/ElevenLabsTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/acmqu/acmo/ElevenLabsTest.kt`:

```kotlin
package com.acmqu.acmo

import com.acmqu.acmo.voice.ElevenLabs
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The streaming client against a local server that plays ElevenLabs. */
class ElevenLabsTest {

    private class RecordingSink : ElevenLabs.Sink {
        private val chunks = mutableListOf<ByteArray>()
        @Volatile var finished = 0
        @Volatile var failure: String? = null
        val done = CountDownLatch(1)

        override fun play(pcm: ByteArray) {
            synchronized(chunks) { chunks += pcm }
        }

        override fun finish() {
            finished++
            done.countDown()
        }

        override fun fail(message: String) {
            failure = message
            done.countDown()
        }

        fun chunkCount(): Int = synchronized(chunks) { chunks.size }
        fun audio(): ByteArray = synchronized(chunks) { chunks.fold(ByteArray(0)) { acc, c -> acc + c } }
    }

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun client() = ElevenLabs("key-123", "voice-abc", baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `asks for the line as streamed pcm_24000 from the flash model`() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(10))))
        val sink = RecordingSink()
        client().stream("Hello there", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))

        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/v1/text-to-speech/voice-abc/stream?output_format=pcm_24000", r.path)
        assertEquals("key-123", r.getHeader("xi-api-key"))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(r.body.readUtf8())
        assertEquals("Hello there", body.getString("text"))
        assertEquals("eleven_flash_v2_5", body.getString("model_id"))
    }

    @Test
    fun `the audio arrives in chunks, in order, then finish`() {
        val audio = ByteArray(20_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(audio), 3000))
        val sink = RecordingSink()
        client().stream("Hello", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertNull(sink.failure)
        assertEquals(1, sink.finished)
        assertTrue("expected several chunks, got ${sink.chunkCount()}", sink.chunkCount() > 1)
        assertArrayEquals(audio, sink.audio())
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
    fun `error bodies in the shapes ElevenLabs uses`() {
        assertEquals("Invalid API key", ElevenLabs.errorMessage("""{"detail":{"status":"invalid_api_key","message":"Invalid API key"}}"""))
        assertEquals("quota_exceeded", ElevenLabs.errorMessage("""{"detail":{"status":"quota_exceeded"}}"""))
        assertEquals("Not Found", ElevenLabs.errorMessage("""{"detail":"Not Found"}"""))
        assertEquals("<html>oops</html>", ElevenLabs.errorMessage("<html>oops</html>"))
        assertEquals("no details", ElevenLabs.errorMessage(""))
        assertEquals("no details", ElevenLabs.errorMessage(null))
    }
}
```

- [ ] **Step 2: Run them to see them fail to compile**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.ElevenLabsTest' --console=plain -q`
Expected: `BUILD FAILED` with `Unresolved reference 'ElevenLabs'`.

- [ ] **Step 3: Write `ElevenLabs.kt`**

Create `app/src/main/java/com/acmqu/acmo/voice/ElevenLabs.kt`:

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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs text-to-speech, streamed. [stream] asks for a line as raw
 * 24 kHz mono 16-bit PCM -- exactly what [AudioOut] plays, so nothing is
 * decoded -- and hands the bytes to a [Sink] chunk by chunk as they arrive,
 * on OkHttp's thread. The first chunk is usually heard within half a second.
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
    interface Sink {
        /** Some of the audio, in order. OkHttp's thread. */
        fun play(pcm: ByteArray)

        /** The whole line has been delivered. */
        fun finish()

        /** No more audio is coming: an HTTP error, a timeout, a dropped connection. Never after a cancel. */
        fun fail(message: String)
    }

    private val url = "$baseUrl/v1/text-to-speech/$voiceId/stream?output_format=$OUTPUT_FORMAT"

    /** Starts streaming [text] into [sink]. Cancel the returned call to stop; the sink then hears nothing more. */
    fun stream(text: String, sink: Sink): Call {
        val body = JSONObject().put("text", text).put("model_id", MODEL).toString()
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
                        val why = errorMessage(r.body?.string())
                        Log.w(TAG, "HTTP ${r.code}: $why")
                        sink.fail("ElevenLabs ${r.code}: $why")
                        return
                    }
                    val input = r.body?.byteStream() ?: run {
                        sink.finish()
                        return
                    }
                    val buf = ByteArray(CHUNK_BYTES)
                    try {
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) sink.play(buf.copyOf(n))
                        }
                    } catch (e: IOException) {
                        if (call.isCanceled()) return
                        Log.w(TAG, "stream ended early", e)
                        sink.fail("ElevenLabs: ${e.message ?: e.javaClass.simpleName}")
                        return
                    }
                    if (!call.isCanceled()) sink.finish()
                }
            }
        })
        return call
    }

    companion object {
        private const val TAG = "ElevenLabs"
        const val BASE_URL = "https://api.elevenlabs.io"

        /** The lowest-latency model (about 75 ms), 32 languages including Arabic. */
        const val MODEL = "eleven_flash_v2_5"

        /** Raw 24 kHz mono 16-bit PCM: what AudioOut plays, available on every tier. */
        const val OUTPUT_FORMAT = "pcm_24000"

        /** Jessica -- "playful, bright, warm" -- one of ElevenLabs' stock voices. */
        const val DEFAULT_VOICE_ID = "cgSgspJ2msm6clMCkdW9"

        /** About 85 ms of audio per read. */
        const val CHUNK_BYTES = 4096

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** ElevenLabs' error body: `{"detail": {"status": ..., "message": ...}}`, or `{"detail": "..."}`, or not JSON at all. */
        fun errorMessage(body: String?): String {
            if (body.isNullOrBlank()) return "no details"
            return try {
                when (val detail = JSONObject(body).opt("detail")) {
                    is JSONObject -> detail.optString("message").ifBlank { detail.optString("status").ifBlank { detail.toString() } }
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

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.ElevenLabsTest' --console=plain -q`
Expected: no output (green). The cancel test takes about 1.3 s by design.

- [ ] **Step 5: Commit**

```sh
git add app/src/main/java/com/acmqu/acmo/voice/ElevenLabs.kt app/src/test/java/com/acmqu/acmo/ElevenLabsTest.kt
git commit -m "feat(voice): ElevenLabs text-to-speech, streamed as 24 kHz PCM

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: `RemoteServer.kt` — the HTTP routes

**Files:**
- Create: `app/src/main/java/com/acmqu/acmo/remote/RemoteServer.kt`
- Test: `app/src/test/java/com/acmqu/acmo/RemoteServerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/acmqu/acmo/RemoteServerTest.kt`:

```kotlin
package com.acmqu.acmo

import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.remote.Entry
import com.acmqu.acmo.remote.Failure
import com.acmqu.acmo.remote.Line
import com.acmqu.acmo.remote.RemoteServer
import com.acmqu.acmo.remote.Said
import com.acmqu.acmo.remote.Snapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** The real server on a free port, against a fake Brain. */
class RemoteServerTest {

    private class FakeHost : RemoteServer.Host {
        val said = mutableListOf<Pair<Line, Boolean>>()
        var hushed = 0
        var snapshot = Snapshot(Brain.State.IDLE, null, emptyList(), null)

        override fun say(line: Line, now: Boolean): Said {
            said += line to now
            return Said(7, 2)
        }

        override fun hush() {
            hushed++
        }

        override fun snapshot(): Snapshot = snapshot
    }

    private class Reply(val code: Int, val body: String, val headers: Map<String, String?>)

    private lateinit var host: FakeHost
    private lateinit var server: RemoteServer

    @Before
    fun start() {
        host = FakeHost()
        server = RemoteServer(0, host, onMain = { it() }, hasKey = true)
        assertTrue(server.startListening())
    }

    @After
    fun stop() {
        server.stopListening()
    }

    private fun request(method: String, path: String, body: String? = null, on: RemoteServer = server): Reply {
        val c = URL("http://127.0.0.1:${on.listeningPort}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")   // no charset on purpose: the server must assume UTF-8
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
        val headers = listOf(
            "Access-Control-Allow-Origin", "Access-Control-Allow-Methods", "Access-Control-Allow-Headers", "Access-Control-Max-Age",
        ).associateWith { c.getHeaderField(it) }
        c.disconnect()
        return Reply(code, text, headers)
    }

    @Test
    fun `say hands the parsed line to the host and answers with id and queued`() {
        val r = request("POST", "/say", """{"text": "Hello there", "feeling": "excited", "now": true}""")
        assertEquals(200, r.code)
        assertEquals(listOf(Line("Hello there", Expression.EXCITED) to true), host.said)
        val json = JSONObject(r.body)
        assertEquals(7, json.getInt("id"))
        assertEquals(2, json.getInt("queued"))
    }

    @Test
    fun `say reads the body as UTF-8 whatever the content type says`() {
        val r = request("POST", "/say", """{"text": "مرحبا يا أكمو"}""")
        assertEquals(200, r.code)
        assertEquals("مرحبا يا أكمو", host.said.single().first.text)
    }

    @Test
    fun `a bad line is 400 with the reason, and never reaches the host`() {
        val r = request("POST", "/say", """{"text": "   "}""")
        assertEquals(400, r.code)
        assertEquals("text is empty", JSONObject(r.body).getString("error"))
        assertEquals(400, request("POST", "/say", "not json").code)
        assertTrue(host.said.isEmpty())
    }

    @Test
    fun `say is 503 without an ElevenLabs key`() {
        val keyless = RemoteServer(0, host, onMain = { it() }, hasKey = false)
        assertTrue(keyless.startListening())
        try {
            val r = request("POST", "/say", """{"text": "Hello"}""", on = keyless)
            assertEquals(503, r.code)
            assertTrue(JSONObject(r.body).getString("error").contains("ELEVENLABS_API_KEY"))
            assertTrue(host.said.isEmpty())
        } finally {
            keyless.stopListening()
        }
    }

    @Test
    fun `say is 503 when the main thread never answers`() {
        val stuck = RemoteServer(0, host, onMain = { /* never runs it */ }, hasKey = true)
        assertTrue(stuck.startListening())
        try {
            val r = request("POST", "/say", """{"text": "Hello"}""", on = stuck)   // waits out the 2 s main-thread timeout
            assertEquals(503, r.code)
            assertEquals("the app is not responding", JSONObject(r.body).getString("error"))
        } finally {
            stuck.stopListening()
        }
    }

    @Test
    fun `stop hushes`() {
        val r = request("POST", "/stop")
        assertEquals(200, r.code)
        assertTrue(JSONObject(r.body).getBoolean("ok"))
        assertEquals(1, host.hushed)
    }

    @Test
    fun `state is the snapshot as JSON`() {
        host.snapshot = Snapshot(
            Brain.State.SPEAKING,
            Entry(3, Line("Hi", Expression.HAPPY)),
            listOf(Entry(4, Line("Bye", Expression.SAD))),
            Failure(2, "ElevenLabs 401: Invalid API key"),
        )
        val r = request("GET", "/state")
        assertEquals(200, r.code)
        val json = JSONObject(r.body)
        assertEquals("speaking", json.getString("state"))
        assertEquals(3, json.getJSONObject("line").getInt("id"))
        assertEquals("sad", json.getJSONArray("queue").getJSONObject(0).getString("feeling"))
        assertEquals("ElevenLabs 401: Invalid API key", json.getJSONObject("error").getString("message"))
    }

    @Test
    fun `the preflight and every reply carry the CORS headers`() {
        val preflight = request("OPTIONS", "/say")
        assertEquals(204, preflight.code)
        assertEquals("*", preflight.headers["Access-Control-Allow-Origin"])
        assertEquals("GET, POST, OPTIONS", preflight.headers["Access-Control-Allow-Methods"])
        assertEquals("Content-Type", preflight.headers["Access-Control-Allow-Headers"])
        assertEquals("86400", preflight.headers["Access-Control-Max-Age"])
        assertEquals("*", request("GET", "/state").headers["Access-Control-Allow-Origin"])
        assertEquals("*", request("GET", "/nothing").headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun `the root explains itself and anything else is 404`() {
        val root = request("GET", "/")
        assertEquals(200, root.code)
        assertTrue(root.body.contains("POST /say"))
        assertEquals(404, request("GET", "/nothing").code)
        assertEquals(404, request("POST", "/state").code)
        assertEquals("no such route", JSONObject(request("GET", "/nothing").body).getString("error"))
    }

    @Test
    fun `start and stop are safe to repeat`() {
        assertTrue(server.startListening())   // already listening
        server.stopListening()
        server.stopListening()                // already stopped
        assertTrue(server.startListening())   // listens again
        assertEquals(200, request("GET", "/").code)
    }
}
```

- [ ] **Step 2: Run them to see them fail to compile**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.RemoteServerTest' --console=plain -q`
Expected: `BUILD FAILED` with `Unresolved reference 'RemoteServer'`.

- [ ] **Step 3: Write `RemoteServer.kt`**

Create `app/src/main/java/com/acmqu/acmo/remote/RemoteServer.kt`:

```kotlin
package com.acmqu.acmo.remote

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The console's way in: a small HTTP server on the tablet.
 *
 *   POST /say    {text, feeling, now}  -> {id, queued}                  a line for ACMO to say
 *   POST /stop                         -> {ok}                          be quiet, forget the queue
 *   GET  /state                        -> {state, line, queue, error}   what ACMO is doing
 *   GET  /                             -> this, in plain text
 *
 * NanoHTTPD answers each request on its own thread; the one call into the app
 * is run on the main thread through [onMain] and waited for, so the [Host]
 * (the Brain) stays main-thread-only. Every reply carries permissive CORS
 * headers, so a page served from anywhere on the network can call it.
 *
 * There is no authentication: whoever is on the Wi-Fi can make ACMO talk.
 */
class RemoteServer(
    private val port: Int,
    private val host: Host,
    /** Runs a block on the main thread. Tests pass `{ it() }`. */
    private val onMain: (() -> Unit) -> Unit,
    /** Without an ElevenLabs key, /say is refused up front with a 503 that says so. */
    private val hasKey: Boolean,
) : NanoHTTPD(port) {

    /** The Brain, as the server sees it. Main thread. */
    interface Host {
        fun say(line: Line, now: Boolean): Said
        fun hush()
        fun snapshot(): Snapshot
    }

    /** Listens, or says why it cannot. Safe to call again. */
    fun startListening(): Boolean {
        if (isAlive) return true
        return try {
            start(SOCKET_READ_TIMEOUT, true)
            Log.i(TAG, "listening on :$listeningPort")
            true
        } catch (e: IOException) {
            Log.e(TAG, "could not listen on :$port", e)
            false
        }
    }

    fun stopListening() {
        if (!isAlive) return
        stop()
        Log.i(TAG, "stopped")
    }

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            route(session)
        } catch (e: Exception) {
            Log.w(TAG, "${session.method} ${session.uri} failed", e)
            json(Response.Status.INTERNAL_ERROR, error("${e.javaClass.simpleName}: ${e.message}"))
        }
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        // A JSON POST is preflighted with OPTIONS; this makes it once a day, not once a line.
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    private fun route(session: IHTTPSession): Response {
        // Always drained: leftover body bytes would corrupt the next request on a kept-alive connection.
        val body = readBody(session)
        if (session.method == Method.OPTIONS) return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
        return when (session.method to session.uri) {
            Method.POST to "/say" -> say(body)
            Method.POST to "/stop" -> {
                onMainOrNull { host.hush() } ?: return notResponding()
                Log.i(TAG, "stop")
                json(Response.Status.OK, JSONObject().put("ok", true))
            }
            Method.GET to "/state" -> {
                val snapshot = onMainOrNull { host.snapshot() } ?: return notResponding()
                json(Response.Status.OK, snapshot.toJson())
            }
            Method.GET to "/" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", ABOUT)
            else -> json(Response.Status.NOT_FOUND, error("no such route"))
        }
    }

    private fun say(body: String): Response {
        if (!hasKey) return json(Response.Status.SERVICE_UNAVAILABLE, error(NO_KEY))
        val say = try {
            Say.parse(body)
        } catch (e: IllegalArgumentException) {
            return json(Response.Status.BAD_REQUEST, error(e.message ?: "bad request"))
        }
        val said = onMainOrNull { host.say(say.line, say.now) } ?: return notResponding()
        Log.i(TAG, "say${if (say.now) " now" else ""} #${said.id} (${say.line.feeling.label}): \"${say.line.text}\"")
        return json(Response.Status.OK, said.toJson())
    }

    /** The request body as UTF-8, whatever charset the header claims; empty when there is none. */
    private fun readBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var read = 0
        val input = session.inputStream
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    /** Runs [block] on the main thread and returns its result, or null if that took more than [MAIN_TIMEOUT_MS]. */
    private fun <T : Any> onMainOrNull(block: () -> T): T? {
        val done = CountDownLatch(1)
        var result: T? = null
        var failure: Throwable? = null
        onMain {
            try {
                result = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        if (!done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "the main thread did not answer within $MAIN_TIMEOUT_MS ms")
            return null
        }
        failure?.let { throw it }
        return result
    }

    private fun notResponding() = json(Response.Status.SERVICE_UNAVAILABLE, error("the app is not responding"))

    private fun json(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString())

    private fun error(message: String) = JSONObject().put("error", message)

    companion object {
        private const val TAG = "RemoteServer"
        const val PORT = 8765
        private const val MAIN_TIMEOUT_MS = 2000L
        private const val NO_KEY = "no ElevenLabs key: add ELEVENLABS_API_KEY to local.properties and rebuild"
        private val ABOUT = """
            ACMO remote -- type a line, ACMO says it. The console is software/remote in the repo.

              POST /say    {"text": "...", "feeling": "happy", "now": false}  -> {"id": 7, "queued": 0}
              POST /stop                                                    -> {"ok": true}
              GET  /state                                                   -> {"state", "line", "queue", "error"}

            feeling: idle surprised sad happy angry passionate annoyed excited (default happy)
            now: true cuts off whatever is playing; false waits its turn.
        """.trimIndent() + "\n"

        /** The tablet's IPv4 address on the local network (Wi-Fi first), or null. */
        fun localAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.acmqu.acmo.RemoteServerTest' --console=plain -q`
Expected: no output (green); the "never answers" test takes 2 s by design. If `HttpURLConnection` throws on `OPTIONS`, it is because `c.requestMethod = "OPTIONS"` is allowed only for the listed methods — it is on the list (`GET, POST, HEAD, OPTIONS, PUT, DELETE, TRACE`), so that would be a typo.

- [ ] **Step 5: Run the whole suite, then commit**

Run: `./gradlew :app:testDebugUnitTest --console=plain -q` — expected: no output.

```sh
git add app/src/main/java/com/acmqu/acmo/remote/RemoteServer.kt app/src/test/java/com/acmqu/acmo/RemoteServerTest.kt
git commit -m "feat(remote): the tablet's HTTP server -- /say, /stop, /state

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: The Remote row on the settings card

**Files:**
- Modify: `app/src/main/java/com/acmqu/acmo/settings/Settings.kt`
- Modify: `app/src/main/java/com/acmqu/acmo/settings/SettingsPanel.kt`
- Modify: `app/src/main/res/layout/view_settings.xml`
- Modify: `app/src/main/res/values/strings.xml`

No unit test: it is Views and SharedPreferences. It is checked on the tablet in Task 7.

- [ ] **Step 1: The setting**

In `Settings.kt`, after the `devMode` property add:

```kotlin
    /** Listens for the remote console (software/remote) on port 8765. */
    var remote: Boolean
        get() = prefs.getBoolean("remote", true)
        set(v) = prefs.edit().putBoolean("remote", v).apply()
```

- [ ] **Step 2: The strings**

In `strings.xml`, after the `settings_close` line add:

```xml
    <string name="settings_remote">Remote console</string>
    <!-- The line under the Remote pills: the address to type into the console, or why there is none. -->
    <string name="remote_off">off</string>
    <string name="remote_no_wifi">no Wi-Fi address — on the Mac: adb forward tcp:%1$d tcp:%1$d</string>
    <string name="remote_failed">could not listen on :%1$d — is another ACMO running?</string>
```

- [ ] **Step 3: The layout**

In `view_settings.xml`, after the dev-mode `</LinearLayout>` (the one holding `btnDevOff` / `btnDevOn`) and before the `labelVolume` TextView, insert:

```xml
        <TextView
            android:id="@+id/labelRemote"
            style="@style/Eyebrow"
            android:layout_marginTop="22dp"
            android:text="@string/settings_remote" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:orientation="horizontal">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnRemoteOff"
                style="@style/Pill"
                android:text="@string/settings_off" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnRemoteOn"
                style="@style/Pill"
                android:layout_marginStart="10dp"
                android:text="@string/settings_on" />
        </LinearLayout>

        <!-- Where the console should point. Filled in by the activity. -->
        <TextView
            android:id="@+id/remoteAddress"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:fontFamily="@font/jetbrains_mono_bold"
            android:textSize="12sp" />
```

- [ ] **Step 4: The panel**

In `SettingsPanel.kt`:

Replace the class KDoc's first sentence

```kotlin
 * The hidden settings card: light/dark, media volume, screen brightness and
 * the face's primary colour. Theme and colour persist through [Settings];
 * volume is the device's own; brightness is applied to this window.
```

with

```kotlin
 * The hidden settings card: light/dark, dev mode, the remote console, media
 * volume, screen brightness and the face's primary colour. Theme, dev mode,
 * remote and colour persist through [Settings]; volume is the device's own;
 * brightness is applied to this window.
```

After the `private val swatchViews = mutableListOf<View>()` line add:

```kotlin
    /** What the line under the Remote pills says -- the address, or why there is none. The activity knows. */
    var remoteStatus: () -> String = { "" }
```

In `init`, after the two `btnDev...` listeners add:

```kotlin
        b.btnRemoteOff.setOnClickListener { settings.remote = false; changed() }
        b.btnRemoteOn.setOnClickListener { settings.remote = true; changed() }
```

In `refresh()`, change the label loop to include the new label:

```kotlin
        for (label in listOf(b.titleText, b.labelTheme, b.labelDev, b.labelRemote, b.labelVolume, b.labelBrightness, b.labelColour)) {
            label.setTextColor(muted)
        }
        b.hintText.setTextColor(muted)
        b.remoteAddress.setTextColor(muted)
        b.remoteAddress.text = remoteStatus()
```

and after the two `stylePill(b.btnDev..., ...)` lines add:

```kotlin
        stylePill(b.btnRemoteOff, selected = !settings.remote, theme)
        stylePill(b.btnRemoteOn, selected = settings.remote, theme)
```

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug --console=plain -q`
Expected: no output. (View binding generates `labelRemote`, `btnRemoteOff`, `btnRemoteOn`, `remoteAddress` from the ids.)

- [ ] **Step 6: Commit**

```sh
git add app/src/main/java/com/acmqu/acmo/settings app/src/main/res/layout/view_settings.xml app/src/main/res/values/strings.xml
git commit -m "feat(settings): a Remote console row with the address to use

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: `Brain` speaks lines; `MainActivity` owns the server

**Files:**
- Modify: `app/src/main/java/com/acmqu/acmo/Brain.kt` (whole file below)
- Modify: `app/src/main/java/com/acmqu/acmo/MainActivity.kt`

No JVM test: `Brain` is Android through and through (`FaceView`, `AudioOut`). Task 7 exercises every path on the tablet.

- [ ] **Step 1: Replace `Brain.kt` with this**

The changes against the current file: the `eleven` constructor parameter and the `RemoteServer.Host` interface; the queue fields; `start(model)` putting the mic in WAKE when a remote line finished during BOOTING (before, it stayed PAUSED for good); `dropLines()` used by `stop()` and `setForeground(false)`; the `playing != null` guard in `onAudio`; `onSpeechStart` logging a line; `onSpeechEnd` ending a line instead of the conversation; `fail()` keeping its apology in `replyJob` and going idle only if still active; `goIdle()` split into `goIdle()` (drains the queue) and `idle()` (the old body); and the whole "lines from the console" section. Everything else is unchanged.

```kotlin
package com.acmqu.acmo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.acmqu.acmo.face.Expression
import com.acmqu.acmo.face.FaceView
import com.acmqu.acmo.gemini.LiveSession
import com.acmqu.acmo.gemini.Personality
import com.acmqu.acmo.gemini.Sentence
import com.acmqu.acmo.remote.Entry
import com.acmqu.acmo.remote.Failure
import com.acmqu.acmo.remote.Line
import com.acmqu.acmo.remote.RemoteServer
import com.acmqu.acmo.remote.Said
import com.acmqu.acmo.remote.Snapshot
import com.acmqu.acmo.voice.AudioOut
import com.acmqu.acmo.voice.ElevenLabs
import com.acmqu.acmo.voice.MicPipeline
import com.acmqu.acmo.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import org.vosk.Model

/**
 * What ACMO does, as one state machine on the main thread:
 *
 *   IDLE ──wake word──▶ LISTENING ──the model answers──▶ THINKING ──its voice──▶ SPEAKING ──▶ IDLE
 *
 * The wake word is heard on the tablet; from then on the microphone streams
 * into a Gemini Live session, which decides when the person has finished,
 * plans the faces for its answer (one per sentence, through a tool call) and
 * speaks it. The face is idle while waiting, excited from the wake word until
 * the answer begins, then walks the planned faces as the sentences are heard.
 *
 * One session is kept open across exchanges so that follow-ups remember the
 * conversation; it is dropped after [MEMORY_MS] of quiet. Errors are a sad
 * face and a spoken apology, because the face is the only screen there is.
 *
 * The remote console is the other way in: lines an operator typed, spoken in
 * an ElevenLabs voice ([say]). They queue up and play one after another, or
 * cut in. A line is SPEAKING like a reply is, with the face the operator chose.
 */
class Brain(
    private val face: FaceView,
    /** Android's own text-to-speech: only for the things said when the session cannot. */
    private val speaker: Speaker,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val apology: String,
    /** The console's voice; null when no ElevenLabs key was built in. */
    private val eleven: ElevenLabs?,
) : MicPipeline.Listener, RemoteServer.Host {

    enum class State { BOOTING, IDLE, LISTENING, THINKING, SPEAKING }

    @Volatile var state = State.BOOTING
        private set(value) {
            field = value
            onStateChanged?.invoke(value)
        }

    /** Fires on the main thread whenever [state] changes; the dev bar's mic button watches it. */
    var onStateChanged: ((State) -> Unit)? = null

    @Volatile private var mic: MicPipeline? = null
    @Volatile private var session: LiveSession? = null
    private var sessionExpiring = false
    private var resumptionHandle: String? = null
    private var lastExchangeAt = 0L

    private var listenJob: Job? = null    // gives up on a wake that leads nowhere
    private var replyJob: Job? = null     // gives up on a model that never answers, or never stops
    private var forgetJob: Job? = null    // closes a quiet session after MEMORY_MS
    private var previewJob: Job? = null

    // The reply being spoken. Whichever thread sees the reply first creates the player.
    @Volatile private var out: AudioOut? = null
    private var faces: List<Expression> = emptyList()
    private var faceIndex = 0
    private val heard = StringBuilder()
    private val said = StringBuilder()

    // Lines from the console: the one playing, and the ones waiting their turn.
    private val queue = ArrayDeque<Entry>()
    @Volatile private var playing: Entry? = null   // read on the socket thread too
    private var call: Call? = null                 // the ElevenLabs stream of [playing]
    private var nextId = 1
    private var lastFailure: Failure? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(model: Model) {
        mic = MicPipeline(model, this).also { it.start() }
        // A typed prompt or a remote line may still be in flight, or have come and gone, while
        // the model loaded (BOOTING allows both): the microphone joins whatever state that left.
        when (state) {
            State.BOOTING -> goIdle()
            State.IDLE -> mic?.setMode(MicPipeline.Mode.WAKE)
            else -> mic?.setMode(MicPipeline.Mode.PAUSED)
        }
    }

    fun stop() {
        listenJob?.cancel()
        replyJob?.cancel()
        forgetJob?.cancel()
        previewJob?.cancel()
        mic?.stop()
        mic = null
        val o = out
        out = null
        o?.cancel()
        dropLines()
        closeSession()
        speaker.stop()
        state = State.BOOTING
    }

    /** The app left or returned to the foreground. Background microphones are silent on modern Android anyway. */
    fun setForeground(foreground: Boolean) {
        if (mic == null) return
        if (foreground) {
            if (state == State.IDLE) mic?.setMode(MicPipeline.Mode.WAKE)
        } else {
            val o = out
            out = null
            o?.cancel()
            dropLines()
            speaker.stop()
            goIdle()
            mic?.setMode(MicPipeline.Mode.PAUSED)
            closeSession()
        }
    }

    /** Dev mode: listen right now, no wake word needed -- or, if already listening, stop. */
    fun toggleListening() {
        if (mic == null) return   // no microphone yet (permission, model)
        when (state) {
            State.IDLE -> listen()
            State.LISTENING -> {
                mic?.setMode(MicPipeline.Mode.PAUSED)
                goIdle()
            }
            else -> {}
        }
    }

    /** Dev mode: a typed prompt, straight to the model. */
    fun submitText(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        // BOOTING is allowed on purpose: a typed prompt is the one way to talk to
        // ACMO on a device with no microphone, or before the model has loaded.
        if (state != State.IDLE && state != State.LISTENING && state != State.BOOTING) return
        listenJob?.cancel()
        previewJob?.cancel()
        mic?.setMode(MicPipeline.Mode.PAUSED)
        state = State.THINKING
        face.setExpression(Expression.EXCITED)
        Log.i(TAG, "typed: \"$prompt\"")
        openSession().sendText(prompt)
        armReplyTimeout()
    }

    /** A tap on the idle face shows the next expression for a few seconds -- a preview, nothing more. */
    fun previewNext() {
        if (state != State.IDLE) return
        previewJob?.cancel()
        face.setExpression(face.expression.next())
        previewJob = scope.launch {
            delay(4000)
            if (state == State.IDLE) face.setExpression(Expression.IDLE)
        }
    }

    // ---- MicPipeline.Listener (main thread) ----

    override fun onWake() {
        if (state != State.IDLE) {
            // The mic flipped itself to STREAM; this state does not want that.
            mic?.setMode(MicPipeline.Mode.PAUSED)
            return
        }
        listen()
    }

    override fun onSpeech() {
        Log.d(TAG, "speech started")
    }

    override fun onNothingHeard() {
        when (state) {
            State.LISTENING -> shrug()
            State.IDLE -> mic?.setMode(MicPipeline.Mode.WAKE)
            else -> {}
        }
    }

    override fun onMicError(message: String) {
        Log.e(TAG, "microphone: $message")
        face.setExpression(Expression.SAD)
    }

    // ---- listening ----

    private fun listen() {
        previewJob?.cancel()
        state = State.LISTENING
        face.setExpression(Expression.EXCITED)
        val s = openSession()
        mic?.sink = s::sendAudio
        mic?.setMode(MicPipeline.Mode.STREAM)
        listenJob?.cancel()
        listenJob = scope.launch {
            delay(MAX_LISTEN_MS)
            if (state == State.LISTENING) shrug()
        }
    }

    /** Nothing usable was heard: a surprised look, then back to waiting. */
    private fun shrug() {
        listenJob?.cancel()
        mic?.setMode(MicPipeline.Mode.PAUSED)
        face.setExpression(Expression.SURPRISED)
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(1500)
            goIdle()
        }
    }

    // ---- the session ----

    private fun openSession(): LiveSession {
        session?.let { if (!sessionExpiring) return it }
        closeSession()
        val handle = resumptionHandle?.takeIf { SystemClock.elapsedRealtime() - lastExchangeAt < MEMORY_MS }
        val listener = SessionListener()
        val s = LiveSession(apiKey, Personality.setup(handle), listener)
        listener.self = s
        session = s
        sessionExpiring = false
        Log.i(TAG, if (handle != null) "opening a session, continuing the conversation" else "opening a session")
        s.connect()
        return s
    }

    private fun closeSession() {
        val s = session ?: return
        session = null
        sessionExpiring = false
        resumptionHandle = s.resumptionHandle ?: resumptionHandle
        s.close()
    }

    /** The model has started answering: stop listening, start waiting for its voice. */
    private fun replyBegins() {
        if (state == State.LISTENING) {
            listenJob?.cancel()
            mic?.setMode(MicPipeline.Mode.PAUSED)
            state = State.THINKING
        }
        armReplyTimeout()
    }

    private fun armReplyTimeout() {
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(REPLY_TIMEOUT_MS)
            if (state == State.THINKING) fail("no answer within ${REPLY_TIMEOUT_MS / 1000} s")
        }
    }

    /** Callbacks from one session. A session that is no longer [Brain.session] is ignored. */
    private inner class SessionListener : LiveSession.Listener {
        lateinit var self: LiveSession
        private fun current() = self === session

        override fun onReady() {
            if (current()) Log.i(TAG, "session ready")
        }

        override fun onFaces(feelings: List<Expression>) {
            if (!current() || (state != State.LISTENING && state != State.THINKING)) return
            replyBegins()
            faces = feelings
            faceIndex = 0
            face.setExpression(feelings.first())
            Log.i(TAG, "faces: ${feelings.joinToString(" ") { it.label }}")
        }

        override fun onAudio(pcm: ByteArray, bytesBefore: Long) {
            // Socket thread. Anything arriving while idle is a reply to something ACMO gave up on.
            if (!current()) return
            if (playing != null) return   // a remote line has the player; the session was closed, this is a straggler
            val st = state
            if (st != State.LISTENING && st != State.THINKING && st != State.SPEAKING) return
            mic?.setMode(MicPipeline.Mode.PAUSED)   // the robot must not hear itself
            player().play(pcm)
        }

        override fun onTranscript(text: String, input: Boolean, audioBytes: Long) {
            if (!current()) return
            if (input) {
                heard.append(text)
                return
            }
            said.append(text)
            if (state != State.THINKING && state != State.SPEAKING) return
            // The next sentence gets the next planned face, once the audio has got there.
            if (Sentence.endsIn(text) && faceIndex + 1 < faces.size) {
                val next = faces[++faceIndex]
                player().cue(audioBytes + CUE_LEAD_BYTES) {
                    if (state == State.SPEAKING) face.setExpression(next)
                }
            }
        }

        override fun onTurnComplete(audioBytes: Long) {
            if (!current()) return
            if (audioBytes == 0L) return   // the turn that only planned the faces; the spoken one follows
            if (heard.isNotEmpty()) Log.i(TAG, "heard: \"${heard.trim()}\"")
            if (said.isNotEmpty()) Log.i(TAG, "said: \"${said.trim()}\"")
            heard.clear()
            said.clear()
            out?.finish()
        }

        override fun onInterrupted() {
            if (!current()) return
            Log.i(TAG, "interrupted")
            out?.cancel()
        }

        override fun onGoAway(timeLeftMs: Long) {
            if (!current()) return
            Log.i(TAG, "session expiring in $timeLeftMs ms")
            sessionExpiring = true
            if (state == State.IDLE) closeSession()
        }

        override fun onClosed(failure: String?) {
            if (!current()) return
            resumptionHandle = self.resumptionHandle ?: resumptionHandle
            session = null
            if (failure == null) return
            Log.w(TAG, "session lost: $failure")
            if (self.resumed && !self.ready && state == State.LISTENING) {
                // A stale handle, most likely. Once more, from scratch.
                resumptionHandle = null
                val s = openSession()
                mic?.sink = s::sendAudio
                return
            }
            if (state == State.LISTENING || state == State.THINKING || state == State.SPEAKING) fail("session lost")
        }
    }

    // ---- the voice ----

    private fun player(): AudioOut {
        out?.let { return it }
        synchronized(this) {
            out?.let { return it }
            return AudioOut(onStart = ::onSpeechStart, onFinish = ::onSpeechEnd).also { out = it }
        }
    }

    private fun onSpeechStart(o: AudioOut) {
        if (o !== out) return
        if (state == State.LISTENING || state == State.THINKING) {
            replyBegins()
            state = State.SPEAKING
            if (faces.isEmpty()) face.setExpression(Expression.HAPPY)   // it spoke without planning faces
        }
        playing?.let { Log.i(TAG, "line #${it.id} playing") }
        face.setSpeaking(true)
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(MAX_SPEAK_MS)
            if (state == State.SPEAKING && out === o) {
                Log.w(TAG, "the reply ran long; cutting it off")
                o.cancel()
            }
        }
    }

    private fun onSpeechEnd(o: AudioOut) {
        if (o !== out) return
        out = null
        face.setSpeaking(false)
        if (state != State.SPEAKING) return
        if (playing != null) {
            lineEnded()
        } else {
            lastExchangeAt = SystemClock.elapsedRealtime()
            goIdle()
        }
    }

    // ---- lines from the console (RemoteServer.Host, main thread) ----

    override fun say(line: Line, now: Boolean): Said {
        val entry = Entry(nextId++, line)
        if (now) {
            interrupt()
            queue.addFirst(entry)
            playNext()
            return Said(entry.id, 0)
        }
        val ahead = queue.size + (if (playing != null) 1 else 0)
        queue.addLast(entry)
        if (state == State.IDLE || state == State.BOOTING) playNext()
        return Said(entry.id, ahead)
    }

    /** Be quiet: whatever is playing stops, the queue is forgotten, and ACMO waits for its name. */
    override fun hush() {
        Log.i(TAG, "hush")
        interrupt()
        queue.clear()
        idle()
    }

    override fun snapshot(): Snapshot = Snapshot(state, playing, queue.toList(), lastFailure)

    /** Cancels the remote line playing and forgets the ones waiting. */
    private fun dropLines() {
        call?.cancel()
        call = null
        queue.clear()
        playing = null
    }

    /** Cuts off whatever ACMO is doing -- a remote line, or a conversation -- without deciding what comes next. */
    private fun interrupt() {
        // A Gemini exchange in flight: listening, thinking, or its voice playing (a remote line's is not it,
        // and neither is the quiet after a failed one).
        val conversing = state == State.LISTENING || state == State.THINKING ||
            (state == State.SPEAKING && out != null && playing == null)
        listenJob?.cancel()
        replyJob?.cancel()
        previewJob?.cancel()
        mic?.sink = null
        mic?.setMode(MicPipeline.Mode.PAUSED)
        val o = out
        out = null
        o?.cancel()
        call?.cancel()
        call = null
        speaker.stop()
        faces = emptyList()
        faceIndex = 0
        face.setSpeaking(false)
        if (conversing) {
            // Dropping the socket is what keeps the model's late audio out of the player. The
            // resumption handle survives, so the next "hey ACMO" still remembers the conversation.
            Log.i(TAG, "interrupting the conversation")
            closeSession()
            heard.clear()
            said.clear()
        }
        playing = null
    }

    /** Speaks the next line from the console, or goes idle when there is none. */
    private fun playNext() {
        val entry = queue.removeFirstOrNull() ?: run {
            idle()
            return
        }
        val voice = eleven ?: run {
            // Unreachable from the server, which answers 503 without a key.
            lastFailure = Failure(entry.id, "no ElevenLabs key")
            idle()
            return
        }
        listenJob?.cancel()
        replyJob?.cancel()
        previewJob?.cancel()
        mic?.sink = null
        mic?.setMode(MicPipeline.Mode.PAUSED)
        out?.let {   // never expected; a player from before would swallow this line's audio
            out = null
            it.cancel()
        }
        playing = entry
        lastFailure = null
        state = State.SPEAKING
        face.setExpression(entry.line.feeling)
        Log.i(TAG, "line #${entry.id} (${entry.line.feeling.label}): \"${entry.line.text}\"")
        // The player is made here, on the main thread, so the socket thread only ever feeds this one.
        val o = player()
        call = voice.stream(entry.line.text, object : ElevenLabs.Sink {
            override fun play(pcm: ByteArray) = o.play(pcm)
            override fun finish() = o.finish()
            override fun fail(message: String) {
                main.post { lineFailed(entry, o, message) }
            }
        })
    }

    /** The last byte of the line has been heard. */
    private fun lineEnded() {
        val entry = playing ?: return
        Log.i(TAG, "line #${entry.id} done")
        playing = null
        call = null
        playNext()
    }

    /** ElevenLabs could not deliver the line: a sad face for a moment, then on with the queue. */
    private fun lineFailed(entry: Entry, o: AudioOut, message: String) {
        if (entry !== playing) return
        Log.e(TAG, "line #${entry.id} failed: $message")
        lastFailure = Failure(entry.id, message)
        if (out === o) out = null
        o.cancel()
        call = null
        playing = null
        face.setSpeaking(false)
        face.setExpression(Expression.SAD)
        replyJob?.cancel()
        replyJob = scope.launch {
            delay(FAIL_PAUSE_MS)
            playNext()
        }
    }

    // ---- failure, and back to idle ----

    private fun fail(why: String) {
        Log.e(TAG, "conversation failed: $why")
        listenJob?.cancel()
        replyJob?.cancel()
        mic?.setMode(MicPipeline.Mode.PAUSED)
        val o = out
        out = null
        o?.cancel()
        face.setSpeaking(false)
        face.setExpression(Expression.SAD)
        // Kept in replyJob so a line cutting in can stop the apology from going idle underneath it.
        replyJob = scope.launch {
            try {
                speaker.speak(apology, "en")
            } catch (_: Exception) {
            }
            if (isActive) goIdle()
        }
    }

    /** Back to waiting for the wake word -- unless the console has lines waiting, which come first. */
    private fun goIdle() {
        if (queue.isNotEmpty()) playNext() else idle()
    }

    private fun idle() {
        listenJob?.cancel()
        replyJob?.cancel()
        state = State.IDLE
        faces = emptyList()
        faceIndex = 0
        playing = null
        face.setSpeaking(false)
        face.setExpression(Expression.IDLE)
        mic?.sink = null
        mic?.setMode(MicPipeline.Mode.WAKE)
        if (sessionExpiring) closeSession()
        forgetJob?.cancel()
        forgetJob = scope.launch {
            delay(MEMORY_MS)
            if (state == State.IDLE) {
                closeSession()
                resumptionHandle = null
                Log.i(TAG, "forgot the conversation")
            }
        }
    }

    companion object {
        private const val TAG = "Brain"

        /** How long ACMO keeps the thread of a conversation after the last exchange. */
        const val MEMORY_MS = 10 * 60 * 1000L

        /** From the wake word, how long the person may take before ACMO gives up. */
        const val MAX_LISTEN_MS = 20_000L

        /** From the model's first sign of an answer, how long its voice may take to start. */
        const val REPLY_TIMEOUT_MS = 20_000L

        /** No reply is this long; if one is, the socket has stopped saying so. */
        const val MAX_SPEAK_MS = 90_000L

        /** How long the sad face stays after a remote line fails, before the next one. */
        const val FAIL_PAUSE_MS = 1500L

        /**
         * The transcript runs about a second ahead of the audio it describes,
         * so a sentence that ends in a transcript chunk ends in the audio about
         * this much later. Tune it if the face changes early or late.
         */
        const val CUE_LEAD_BYTES = 1000 * AudioOut.BYTES_PER_MS
    }
}
```

- [ ] **Step 2: Wire it in `MainActivity.kt`**

Add two imports (keep the list alphabetical):

```kotlin
import com.acmqu.acmo.remote.RemoteServer
import com.acmqu.acmo.voice.ElevenLabs
```

After `private lateinit var panel: SettingsPanel` add:

```kotlin
    private lateinit var remote: RemoteServer
```

In `onCreate`, replace

```kotlin
        brain = Brain(
            face = binding.face,
            speaker = speaker,
            apiKey = BuildConfig.GEMINI_API_KEY,
            scope = lifecycleScope,
            apology = getString(R.string.speech_apology),
        )
        panel = SettingsPanel(binding.settings, this, settings) { applySettings() }
        applySettings()
```

with

```kotlin
        val eleven = if (BuildConfig.ELEVENLABS_API_KEY.isBlank()) null else ElevenLabs(
            apiKey = BuildConfig.ELEVENLABS_API_KEY,
            voiceId = BuildConfig.ELEVENLABS_VOICE_ID.ifBlank { ElevenLabs.DEFAULT_VOICE_ID },
        )
        brain = Brain(
            face = binding.face,
            speaker = speaker,
            apiKey = BuildConfig.GEMINI_API_KEY,
            scope = lifecycleScope,
            apology = getString(R.string.speech_apology),
            eleven = eleven,
        )
        remote = RemoteServer(RemoteServer.PORT, brain, onMain = { block -> runOnUiThread { block() } }, hasKey = eleven != null)
        panel = SettingsPanel(binding.settings, this, settings) { applySettings() }
        panel.remoteStatus = ::remoteStatus
        applySettings()
        if (eleven == null) {
            Log.w(TAG, "ELEVENLABS_API_KEY is empty -- the remote console gets 503; add it to local.properties and rebuild")
        }
```

Replace `applySettings()` with:

```kotlin
    private fun applySettings() {
        val theme = settings.theme()
        binding.face.setTheme(theme)
        window.decorView.setBackgroundColor(theme.bg)
        panel.applyBrightness()
        if (settings.remote) remote.startListening() else remote.stopListening()
        panel.refresh()
        styleDevBar(theme)
    }

    /** The line under the Remote pills on the settings card: where the console should point. */
    private fun remoteStatus(): String = when {
        !settings.remote -> getString(R.string.remote_off)
        !remote.isAlive -> getString(R.string.remote_failed, RemoteServer.PORT)
        else -> RemoteServer.localAddress()?.let { "http://$it:${RemoteServer.PORT}" }
            ?: getString(R.string.remote_no_wifi, RemoteServer.PORT)
    }
```

In `onDestroy`, before `brain.stop()` add:

```kotlin
        remote.stopListening()
```

- [ ] **Step 3: Build and run the whole suite**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain -q`
Expected: no output. If Kotlin complains that `isActive` is unresolved, the import `kotlinx.coroutines.isActive` is missing.

- [ ] **Step 4: Commit**

```sh
git add app/src/main/java/com/acmqu/acmo/Brain.kt app/src/main/java/com/acmqu/acmo/MainActivity.kt
git commit -m "feat: lines from the console -- queued, cut in, or stopped -- in an ElevenLabs voice

Brain.say queues an Entry and plays it when idle; now=true interrupts a line
or a conversation first. Each line streams from ElevenLabs into the same
AudioOut slot the Live session uses, so the mouth, the watchdog and every
cancel path are shared. MainActivity owns the RemoteServer and starts it
with the Remote setting.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: On the tablet — install, forward, and talk to it with curl

**Files:** none. This is the check of Tasks 1–6 on the Redmi. Do it with the tablet in hand; it speaks out loud.

- [ ] **Step 1: Install and launch**

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
export ANDROID_SERIAL=b15f152c
adb devices            # b15f152c  device
./gradlew :app:installDebug --console=plain -q
adb shell am start -n com.acmqu.acmo/.MainActivity
adb forward tcp:8765 tcp:8765
```

If `installDebug` reports `INSTALL_FAILED_USER_RESTRICTED`, tap *Install* on the tablet within ten seconds and retry (see `../CONNECT.md` §2).

- [ ] **Step 2: Watch the log in a second terminal**

```sh
~/Library/Android/sdk/platform-tools/adb -s b15f152c logcat -v time -s Brain RemoteServer ElevenLabs AudioOut MainActivity
```

Expected within a few seconds of launch: `RemoteServer: listening on :8765`. If instead `MainActivity: ELEVENLABS_API_KEY is empty`, the key is not in `local.properties` — stop and fix that.

- [ ] **Step 3: The routes**

```sh
curl -s localhost:8765/
curl -s -X POST localhost:8765/say -H 'Content-Type: application/json' \
  -d '{"text":"Hello! I am ACMO, the mascot of the ACM chapter at Qatar University.","feeling":"excited"}'
curl -s localhost:8765/state
```

Expected: the plain-text route list; `{"id":1,"queued":0}`; ACMO's face goes excited, the mouth moves while the line plays, then idle; `/state` during the line shows `"state":"speaking"` with `"line":{"id":1,...}` and afterwards `"state":"idle","line":null`. In the log: `RemoteServer: say #1 (excited): "Hello! ..."`, `Brain: line #1 (excited): ...`, `Brain: line #1 playing`, `Brain: line #1 done`. Note the timestamps of `line #1 (excited)` and `line #1 playing`: their difference is the ElevenLabs latency. Send the same line again and note it a second time (warm connection).

- [ ] **Step 4: Queue, cut in, stop, Arabic**

```sh
for t in "One, this is the first line." "Two, this is the second." "Three, and the third."; do
  curl -s -X POST localhost:8765/say -H 'Content-Type: application/json' -d "{\"text\":\"$t\",\"feeling\":\"happy\"}"; echo
done
```

Expected: `{"id":2,"queued":0}` `{"id":3,"queued":1}` `{"id":4,"queued":2}`; the three lines play back to back with no idle face between them. While they play:

```sh
curl -s -X POST localhost:8765/say -H 'Content-Type: application/json' -d '{"text":"Wait, wait, wait!","feeling":"angry","now":true}'
```

Expected: the current line stops mid-word, "Wait, wait, wait!" plays with the angry face, then whatever was still queued continues. Then:

```sh
curl -s -X POST localhost:8765/say -H 'Content-Type: application/json' -d '{"text":"مرحبا! أنا أكمو، تميمة نادي إيه سي إم في جامعة قطر.","feeling":"happy"}'
curl -s -X POST localhost:8765/stop
curl -s localhost:8765/state
```

Expected: the Arabic line is spoken in Arabic (flash v2.5 detects the language); `/stop` cuts it off with `{"ok":true}`; `/state` is idle with an empty queue.

- [ ] **Step 5: A failure, and the wake word afterwards**

```sh
curl -s -X POST localhost:8765/say -H 'Content-Type: application/json' -d '{"text":"","feeling":"happy"}'
curl -s -X POST localhost:8765/say -H 'Content-Type: application/json' -d '{"text":"x","feeling":"smug"}'
```

Expected: `{"error":"text is empty"}` and `{"error":"unknown feeling \"smug\"; one of ..."}` — both HTTP 400 (`curl -i` shows it). To see the failure path end to end, **turn the tablet's Wi-Fi off** (USB adb and the `adb forward` keep working) and send a line: the face goes sad for 1.5 s, then idle, and `/state` shows `"error":{"id":...,"message":"ElevenLabs: Unable to resolve host ..."}` (note the actual text). Queue two lines while offline: both fail in turn, 1.5 s apart, and the queue ends empty. **Turn Wi-Fi back on** and send one more line to see it recover.

Then say **"hey ACMO"** and ask something: the wake-word conversation must work as before, and a line sent while ACMO is *listening* must wait (`queued` counts it) and play after the reply. Finally, five taps in the top-left corner: the card shows the **Remote console** row, *On* selected, and `http://<tablet-ip>:8765` underneath (or the `adb forward` hint when the tablet is on USB only). Check the card still fits on the screen; if the *Close* button is cut off at the bottom, wrap the `settingsCard` LinearLayout's contents in a `ScrollView` (`android:layout_height="wrap_content"`, `android:fillViewport="true"`) and rebuild.

- [ ] **Step 6: Write down what happened**

Record the latency numbers from Step 3 and anything that differed from "Expected" — they go into the final report and the README's latency sentence (Task 10). Nothing to commit unless Step 5 needed the ScrollView; if it did:

```sh
git add app/src/main/res/layout/view_settings.xml
git commit -m "fix(settings): the card scrolls now that it has one more row

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Scaffold the console

**Files:**
- Create: `remote/` via `create-next-app`

- [ ] **Step 1: Scaffold**

From `software/`:

```sh
npx --yes create-next-app@16.3.5 remote --ts --app --empty --eslint --no-tailwind --no-src-dir --no-react-compiler \
  --import-alias "@/*" --use-npm --disable-git --no-agents-md --yes
```

Expected: `Success! Created remote at .../software/remote` after installing packages. The folder holds `app/layout.tsx`, `app/page.tsx`, `eslint.config.mjs`, `next.config.ts`, `package.json`, `tsconfig.json`, `README.md`, `.gitignore` and `node_modules/`. (`--disable-git` matters: this lives inside the app's repo. `--empty` skips the boilerplate page.)

- [ ] **Step 2: Check what git sees**

Run: `git status --short remote/`
Expected: only `?? remote/` — `node_modules/` and `.next/` are covered by `remote/.gitignore`. Run `git status --short --ignored remote/ | head` to confirm `!! remote/node_modules/` shows as ignored.

- [ ] **Step 3: Commit the scaffold as is**

```sh
git add remote
git commit -m "feat(remote): scaffold the console (create-next-app 16.3.5, empty template)

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: The console page

**Files:**
- Create: `remote/lib/acmo.ts`
- Modify: `remote/app/layout.tsx`
- Modify: `remote/app/page.tsx`
- Create: `remote/app/globals.css`
- Modify: `remote/README.md`

- [ ] **Step 1: The client, `remote/lib/acmo.ts`**

```ts
/**
 * The tablet's remote API (RemoteServer.kt in the app): three calls, JSON both
 * ways, CORS open. Failures come back as Error(message) with the server's own
 * wording when it gave one.
 */

export const FEELINGS = ["idle", "surprised", "sad", "happy", "angry", "passionate", "annoyed", "excited"] as const;
export type Feeling = (typeof FEELINGS)[number];

export type Entry = { id: number; text: string; feeling: Feeling };

export type State = {
  state: "booting" | "idle" | "listening" | "thinking" | "speaking";
  /** The remote line being spoken; null during a wake-word reply, or when quiet. */
  line: Entry | null;
  queue: Entry[];
  error: { id: number; message: string } | null;
};

export const DEFAULT_ADDRESS = "http://localhost:8765";

const TIMEOUT_MS = 5000;

/** What the user typed, as a base URL: scheme added, trailing slashes dropped, empty means the default. */
export function normalize(address: string): string {
  let a = address.trim().replace(/\/+$/, "");
  if (a && !/^https?:\/\//i.test(a)) a = `http://${a}`;
  return a || DEFAULT_ADDRESS;
}

async function call<T>(base: string, path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(base + path, { ...init, signal: AbortSignal.timeout(TIMEOUT_MS) });
  } catch {
    throw new Error(`can't reach ${base}`);
  }
  const text = await res.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    // not JSON; the status code is the message then
  }
  if (!res.ok) {
    const error = (data as { error?: string } | null)?.error;
    throw new Error(error ?? `HTTP ${res.status}`);
  }
  return data as T;
}

export function say(base: string, text: string, feeling: Feeling, now: boolean) {
  return call<{ id: number; queued: number }>(base, "/say", {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({ text, feeling, now }),
  });
}

export function stop(base: string) {
  return call<{ ok: boolean }>(base, "/stop", { method: "POST" });
}

export function state(base: string) {
  return call<State>(base, "/state");
}
```

- [ ] **Step 2: The layout, `remote/app/layout.tsx`** (replace the file)

```tsx
import type { Metadata } from "next";
import type { ReactNode } from "react";
import { JetBrains_Mono } from "next/font/google";
import "./globals.css";

const mono = JetBrains_Mono({ subsets: ["latin"], weight: ["400", "700"], display: "swap" });

export const metadata: Metadata = {
  title: "ACMO remote",
  description: "Type a line, ACMO says it.",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body className={mono.className}>{children}</body>
    </html>
  );
}
```

- [ ] **Step 3: The page, `remote/app/page.tsx`** (replace the file)

```tsx
"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import {
  DEFAULT_ADDRESS,
  FEELINGS,
  type Feeling,
  type State,
  normalize,
  say,
  state as fetchState,
  stop,
} from "@/lib/acmo";

/** How often the tablet is asked what it is doing. */
const POLL_MS = 500;
const ADDRESS_KEY = "acmo.address";

function describe(s: State): string {
  switch (s.state) {
    case "listening":
    case "thinking":
      return "talking to someone";
    case "speaking":
      return s.line ? `speaking · ${s.line.feeling}` : "speaking (not a remote line)";
    default:
      return s.state;
  }
}

export default function Page() {
  // null until localStorage has been read, so the default is never written over a saved address.
  const [address, setAddress] = useState<string | null>(null);
  const [feeling, setFeeling] = useState<Feeling>("happy");
  const [text, setText] = useState("");
  const [status, setStatus] = useState<State | null>(null);
  const [reachable, setReachable] = useState<boolean | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [mac, setMac] = useState(true);
  const box = useRef<HTMLTextAreaElement>(null);
  const polling = useRef(false);
  const base = address === null ? null : normalize(address);

  useEffect(() => {
    setAddress(window.localStorage.getItem(ADDRESS_KEY) ?? DEFAULT_ADDRESS);
    setMac(/Mac|iPhone|iPad/.test(navigator.platform));
    box.current?.focus();
  }, []);

  useEffect(() => {
    if (address !== null) window.localStorage.setItem(ADDRESS_KEY, address);
  }, [address]);

  // Ask the tablet what it is doing, twice a second; a slow answer is not asked over.
  useEffect(() => {
    if (base === null) return;
    let alive = true;
    const tick = async () => {
      if (polling.current) return;
      polling.current = true;
      try {
        const s = await fetchState(base);
        if (alive) {
          setStatus(s);
          setReachable(true);
        }
      } catch {
        if (alive) {
          setStatus(null);
          setReachable(false);
        }
      } finally {
        polling.current = false;
      }
    };
    void tick();
    const timer = window.setInterval(() => void tick(), POLL_MS);
    return () => {
      alive = false;
      window.clearInterval(timer);
    };
  }, [base]);

  const send = useCallback(
    async (now: boolean) => {
      const line = text.trim();
      if (!line || base === null) return;
      try {
        await say(base, line, feeling, now);
        setText("");
        setProblem(null);
        if (box.current) box.current.style.height = "auto";
      } catch (e) {
        setProblem((e as Error).message);
      }
      box.current?.focus();
    },
    [base, feeling, text],
  );

  const hush = useCallback(async () => {
    if (base === null) return;
    try {
      await stop(base);
      setProblem(null);
    } catch (e) {
      setProblem((e as Error).message);
    }
  }, [base]);

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Escape") {
      e.preventDefault();
      void hush();
    } else if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      void send(e.metaKey || e.ctrlKey);
    }
  };

  const busy =
    status !== null &&
    (status.state === "listening" || status.state === "thinking" || status.state === "speaking" || status.queue.length > 0);
  const empty = text.trim() === "";
  const mod = mac ? "⌘" : "Ctrl";

  return (
    <main className="console">
      <header className="bar">
        <h1>ACMO remote</h1>
        <label className="address">
          <span
            className={`dot ${reachable === null ? "" : reachable ? "on" : "off"}`}
            title={reachable ? "connected" : "not connected"}
          />
          <input
            value={address ?? ""}
            onChange={(e) => setAddress(e.target.value)}
            spellCheck={false}
            aria-label="tablet address"
          />
        </label>
      </header>

      {reachable === false && (
        <p className="hint">
          Can&apos;t reach {base}. Run <code>adb forward tcp:8765 tcp:8765</code>, or enter the address from ACMO&apos;s
          settings card (five taps in the top-left corner of the face).
        </p>
      )}

      <section className="compose">
        <div className="faces" role="radiogroup" aria-label="face">
          {FEELINGS.map((f) => (
            <button
              key={f}
              type="button"
              role="radio"
              aria-checked={f === feeling}
              className={`pill ${f === feeling ? "selected" : ""}`}
              onClick={() => setFeeling(f)}
            >
              {f}
            </button>
          ))}
        </div>
        <textarea
          ref={box}
          value={text}
          rows={2}
          placeholder="What should ACMO say?"
          aria-label="the line"
          onChange={(e) => {
            setText(e.target.value);
            e.target.style.height = "auto";
            e.target.style.height = `${e.target.scrollHeight}px`;
          }}
          onKeyDown={onKey}
        />
        <div className="actions">
          <button type="button" className="pill selected" onClick={() => void send(false)} disabled={empty}>
            Queue <kbd>⏎</kbd>
          </button>
          <button type="button" className="pill" onClick={() => void send(true)} disabled={empty}>
            Say now <kbd>{mod}⏎</kbd>
          </button>
          <button type="button" className="pill" onClick={() => void hush()} disabled={!busy}>
            Stop <kbd>esc</kbd>
          </button>
        </div>
        {problem && <p className="problem">{problem}</p>}
      </section>

      <section className="status" aria-live="polite">
        {status ? (
          <>
            <p className="now">
              <span className="eyebrow">{describe(status)}</span>
              {status.line && <span>“{status.line.text}”</span>}
            </p>
            {status.queue.length > 0 && (
              <ol className="queue">
                {status.queue.map((e, i) => (
                  <li key={e.id}>
                    <span className="eyebrow">
                      {i + 1} {e.feeling}
                    </span>
                    <span>“{e.text}”</span>
                  </li>
                ))}
              </ol>
            )}
            {status.error && (
              <p className="problem">
                line {status.error.id}: {status.error.message}
              </p>
            )}
          </>
        ) : (
          <p className="eyebrow">{reachable === null ? "connecting…" : "not connected"}</p>
        )}
      </section>
    </main>
  );
}
```

- [ ] **Step 4: The look, `remote/app/globals.css`**

```css
/* The face's palette: paper and ink, four greys, one teal. */
:root {
  --paper: #fbfafb;
  --ink: #010000;
  --gray: #373637;
  --muted: #706d70;
  --teal: #2fbbab;
  --red: #fa4d4d;
  color-scheme: light dark;
}

@media (prefers-color-scheme: dark) {
  :root {
    --paper: #010000;
    --ink: #fbfafb;
    --teal: #3ae4d1;
  }
}

* {
  box-sizing: border-box;
}

html,
body {
  margin: 0;
  background: var(--paper);
  color: var(--ink);
}

body {
  font-size: 14px;
  line-height: 1.5;
}

.console {
  max-width: 760px;
  margin: 0 auto;
  padding: 32px 24px 64px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

/* ---- the bar ---- */

.bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

h1 {
  font-size: 14px;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  margin: 0;
}

.address {
  display: flex;
  align-items: center;
  gap: 10px;
}

.address input {
  font: inherit;
  color: var(--ink);
  background: transparent;
  border: 2px solid var(--gray);
  border-radius: 9999px;
  padding: 6px 14px;
  width: 280px;
}

.address input:focus {
  outline: none;
  border-color: var(--teal);
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--muted);
}

.dot.on {
  background: var(--teal);
}

.dot.off {
  background: var(--red);
}

/* ---- composing ---- */

.eyebrow {
  font-size: 12px;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: var(--muted);
}

.compose {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.faces {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* Every button on the brand's site is a pill with a 2px ring; selected is filled teal with dark text. */
.pill {
  font: inherit;
  font-weight: 700;
  color: var(--ink);
  background: transparent;
  border: 2px solid var(--teal);
  border-radius: 9999px;
  padding: 8px 18px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.pill.selected {
  background: var(--teal);
  color: #010000;
}

.pill:disabled {
  opacity: 0.4;
  cursor: default;
}

.pill kbd {
  font: inherit;
  font-weight: 400;
  font-size: 11px;
  opacity: 0.7;
}

textarea {
  font: inherit;
  font-size: 18px;
  color: var(--ink);
  background: transparent;
  border: 2px solid var(--teal);
  border-radius: 24px;
  padding: 16px 20px;
  resize: none;
  min-height: 88px;
  width: 100%;
}

/* The brand's one shadow: zero blur, solid teal, offset. */
textarea:focus {
  outline: none;
  box-shadow: 8px 8px 0 var(--teal);
}

.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

/* ---- status ---- */

.status {
  border-top: 2px solid var(--gray);
  padding-top: 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.now {
  margin: 0;
  display: flex;
  gap: 14px;
  align-items: baseline;
  flex-wrap: wrap;
}

.queue {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.queue li {
  display: flex;
  gap: 14px;
  align-items: baseline;
}

.problem {
  margin: 0;
  color: var(--red);
}

.hint {
  margin: 0;
  color: var(--muted);
}

.hint code {
  color: var(--ink);
}
```

- [ ] **Step 5: The README, `remote/README.md`** (replace the file)

````markdown
# ACMO remote

A page for a laptop on the same network as the robot: type a line, pick a
face, and ACMO says it in an ElevenLabs voice. The tablet does the talking
(`remote/RemoteServer.kt` and `voice/ElevenLabs.kt` in the app); this is only
the console. Needs Node 20.9 or newer.

```sh
adb forward tcp:8765 tcp:8765   # the tablet's server, as localhost:8765 -- over USB or adb-over-Wi-Fi
npm install
npm run dev                     # http://localhost:3000
```

Not on adb? Five taps in the top-left corner of the face open ACMO's settings
card; the address under **Remote console** (`http://10.20.55.42:8765` or so)
goes in the box at the top of the page. It is remembered.

- **Enter** queues the line; **Shift+Enter** is a newline; **⌘/Ctrl+Enter**
  says it now, cutting off whatever ACMO is doing; **Esc** stops everything.
- The status under the box is polled from the tablet twice a second: what is
  playing, what is queued, and why the last line failed, if it did.
- This folder is not part of the Gradle build (`settings.gradle.kts` includes
  only `:app`). If Android Studio indexes `node_modules`, right-click the folder
  → *Mark Directory as* → *Excluded*.
````

- [ ] **Step 6: Lint, type-check, build**

From `remote/`:

```sh
npm run lint && npx tsc --noEmit && npm run build
```

Expected: no lint errors, no type errors, and `next build` ends with the route table (`○ /` static). The build needs internet once for the JetBrains Mono download.

- [ ] **Step 7: Run it against the tablet**

With the tablet on adb (`adb forward tcp:8765 tcp:8765` from Task 7 still in place, or run it again):

```sh
npm run dev
```

Open `http://localhost:3000`. Expected: the dot is green and the status reads `idle`. Type a line, press Enter — the box clears, the status shows `speaking · happy “…”` while the tablet talks, then `idle`. Pick `sad`, type two lines quickly (Enter, Enter) — they appear as `queued 1 sad …` and play in order. Type a third and press ⌘Enter — it cuts in. Press Esc mid-line — silence, empty queue. Turn Remote *Off* on the tablet's card — the dot goes red and the hint appears; back *On* — green again. Stop the dev server (Ctrl-C).

- [ ] **Step 8: Commit**

From `software/`:

```sh
git add remote
git commit -m "feat(remote): the console -- a face, a line, queue / say now / stop, live status

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Docs and the memory note

**Files:**
- Modify: `README.md`
- Modify: `../CONNECT.md`
- Modify: `../README.md`
- Modify: `~/.claude/projects/-Users-abdelhakimakhadkhou-Documents-Github-Clones-mascot-robot/memory/acmo-face-app-plan.md`

- [ ] **Step 1: `README.md` (software) — the keys, in Setup step 2**

After the fenced block containing `GEMINI_API_KEY=AIza...` and its "It is baked into the APK ..." paragraph, add:

````markdown
   For the remote console (below), two more lines — the voice id is optional:

   ```
   ELEVENLABS_API_KEY=sk_...
   ELEVENLABS_VOICE_ID=cgSgspJ2msm6clMCkdW9
   ```

   Without the key the app runs as before and the console's sends answer `503`.
````

- [ ] **Step 2: `README.md` (software) — the section**

Before the `## The reply format` heading, insert:

````markdown
## Remote console

`remote/` is a small Next.js page for a laptop on the same network: type a
line, pick one of the eight faces, and ACMO says it in an ElevenLabs voice.
The text is spoken word for word — Gemini is not involved — and streamed:
`eleven_flash_v2_5` returns raw 24 kHz PCM that goes straight into the same
player as Gemini's voice, so the first sound comes about half a second after
Enter (a little more for the first line after a few minutes' quiet).

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
| `GET /state` | | `{"state", "line", "queue", "error"}` |

Lines play back to back with the chosen face; a line ElevenLabs cannot deliver
gets a sad face for a moment and the queue goes on. A line sent while ACMO is
in a wake-word conversation waits for it to end; *Say now* ends it.

**There is no authentication:** anyone on the Wi-Fi can make ACMO talk while
this is on. Switch it off in the settings card at a venue you do not trust.
````

- [ ] **Step 3: `README.md` (software) — the tree and the tuning table**

In the `## What's here` tree:

- After the line `software/`, add `├── remote/                      the operator's console: a Next.js page, not part of the Gradle build`.
- After the `Speaker.kt` line, add `│   │   ├── ElevenLabs.kt        the console's voice: ElevenLabs text-to-speech, streamed as 24 kHz PCM`.
- Change `│   └── settings/` to `│   ├── settings/` and, after its `SettingsPanel.kt` line, add:
  ```
  │   └── remote/
  │       ├── RemoteServer.kt      the console's way in: POST /say, /stop and GET /state on port 8765
  │       └── Line.kt              a line and its feeling; the wire types
  ```

In the `## Tuning` table, add rows at the end:

```markdown
| `ElevenLabs.MODEL` | The ElevenLabs model for console lines. `eleven_flash_v2_5` is the fastest; `eleven_v3_conversational` is richer and slower. | `eleven_flash_v2_5` |
| `ElevenLabs.DEFAULT_VOICE_ID` | The voice when `ELEVENLABS_VOICE_ID` is not set. Any id from ElevenLabs' `GET /v1/voices`. | Jessica |
| `RemoteServer.PORT` | Where the tablet listens for the console. | 8765 |
```

If Task 7 measured a first-sound latency clearly different from "about half a second", change that phrase in Step 2's paragraph to what was measured.

- [ ] **Step 4: `../CONNECT.md`**

In §4, after the `adb -s <serial> install -r ...` block and its `-r replaces ...` paragraph, add:

````markdown
The remote console (`software/remote`) talks to the app on port 8765. Forward
it once per adb session and the console's default address just works:

```sh
adb forward tcp:8765 tcp:8765        # localhost:8765 on the Mac -> the app on the tablet
```
````

In §7 (wireless debugging), at the end of the section, add one sentence:

```markdown
The same `adb forward tcp:8765 tcp:8765` works over Wi-Fi adb, so the remote
console needs no cable either.
```

- [ ] **Step 5: `../README.md` (workspace)**

After the paragraph that ends "which is the hook for the face app to make the robot move when it feels like it.", add:

```markdown
The face also takes lines from a laptop: `software/remote` is a small web
console where someone types what ACMO should say, picks a face, and the tablet
says it in an ElevenLabs voice — streamed, so it starts within about half a
second.
```

In the Layout tree, after `└── software/                         ── repo: mascot-robot-software ──`, add:

```
    ├── remote/                       the operator's console: a Next.js page; type a line, ACMO says it
```

- [ ] **Step 6: Commit the software docs; leave the workspace repo to the user**

```sh
git add README.md
git commit -m "docs: the remote console -- setup, routes, tuning

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

`../CONNECT.md` is untracked in the workspace repo and `../README.md` is the user's; report both edits and let them commit there.

- [ ] **Step 7: Update the memory note**

Rewrite `~/.claude/projects/-Users-abdelhakimakhadkhou-Documents-Github-Clones-mascot-robot/memory/acmo-face-app-plan.md` so its body reflects: the Live migration is committed (`4c7925a`); the remote console exists (`software/remote`, tablet server on 8765, ElevenLabs flash v2.5 / pcm_24000, keys `ELEVENLABS_API_KEY` / `ELEVENLABS_VOICE_ID` in `local.properties`); what Task 7 and Task 9 Step 7 actually verified on the Redmi, with the measured latency; and what remains unverified. Keep the frontmatter; update the `description` line's date. Then make sure `MEMORY.md`'s pointer line still describes it.

---

## Self-review

- **Spec coverage.** §1 routes, CORS, 400/503 rules → Task 4 (server) and Task 2 (parser); `queued` semantics → Task 6 `say()`. §2.1–2.3 → Tasks 4, 2, 3; `DEFAULT_VOICE_ID`, model, output format, timeouts, error parsing → Task 3. §2.4 state machine (`say`, `interrupt`, `playNext`, `lineEnded`, `lineFailed`, `hush`, `goIdle` draining, `onAudio` guard, `setForeground`/`stop` dropping lines, `start(model)`) → Task 6. §2.5 settings row, address line, server ownership → Tasks 5 and 6. §3 console (files, shortcuts, polling, address memory, hint, Stop enabling, look) → Tasks 8 and 9. §4 docs → Task 10; tests → Tasks 2–4; tablet verification → Task 7 and Task 9 Step 7; commits → each task (finer-grained than the spec's four, on purpose).
- **Placeholders.** None: every code step is complete; the one measured value (latency) has a stated default and a step to replace it.
- **Type consistency.** `RemoteServer.Host { say(Line, Boolean): Said; hush(); snapshot(): Snapshot }` is what `Brain` overrides in Task 6 and what `FakeHost` implements in Task 4. `ElevenLabs.Sink { play; finish; fail }` is used identically in Tasks 3 and 6. `Say.parse` / `Line.MAX_CHARS` / `Entry` / `Failure` / `Snapshot.toJson` match between Tasks 2, 4 and 6. `RemoteServer(port, host, onMain, hasKey)`, `startListening()`, `stopListening()`, `listeningPort`, `isAlive`, `PORT`, `localAddress()` match between Tasks 4 and 6. `Settings.remote`, `SettingsPanel.remoteStatus`, the four strings and the three view ids match between Tasks 5 and 6. `lib/acmo.ts` exports (`FEELINGS`, `Feeling`, `State`, `DEFAULT_ADDRESS`, `normalize`, `say`, `stop`, `state`) are exactly what `page.tsx` imports.
