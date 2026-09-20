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

    private open class RecordingSink : ElevenLabs.Sink {
        /** Every call, in order: `play:<bytes>` or `timed:<characters>`. */
        val events = mutableListOf<String>()
        val timings = mutableListOf<LongArray>()
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

        override fun timed(atByte: LongArray) {
            synchronized(this) {
                timings += atByte
                events += "timed:${atByte.size}"
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
        assertEquals(listOf("timed:8", "play:100"), sink.events)
        assertArrayEquals(
            longArrayOf(48_000, 72_000, 96_000, 120_000, 144_000, 168_000, 192_000, 216_000),
            sink.timings.single(),
        )
    }

    @Test
    fun `a byte offset is a whole frame`() {
        // 0.012525 s is 601.2 bytes; a frame is two bytes, so the cue lands on 600.
        server.enqueue(MockResponse().setBody(obj(pcm(2), "a", from = 0.012525)))
        val sink = RecordingSink()
        client().stream("a", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertArrayEquals(longArrayOf(600), sink.timings.single())
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
        // Two deviations from the usual shape: no audio_base64 key at all, and an explicit null.
        val noKey =
            """{"alignment": {"characters": ["a", "b"], "character_start_times_seconds": [0.0, 0.5], "character_end_times_seconds": [0.5, 1.0]}}"""
        val nullAudio =
            """{"audio_base64": null, "alignment": {"characters": ["c"], "character_start_times_seconds": [1.0], "character_end_times_seconds": [1.5]}}"""
        server.enqueue(MockResponse().setBody(noKey + "\n" + nullAudio + "\n" + obj(pcm(4))))
        val sink = RecordingSink()
        client().stream("abc", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertNull(sink.failure)
        assertEquals(listOf("timed:2", "timed:1", "play:4"), sink.events)
        assertArrayEquals(longArrayOf(0, 24_000), sink.timings[0])
        assertArrayEquals(longArrayOf(48_000), sink.timings[1])
        assertEquals(1, sink.finished)
    }

    @Test
    fun `a line that is not what ElevenLabs sends fails the line`() {
        // Each body, and the chunks of audio the sink should have heard before the bad line stopped the read.
        val bad = listOf(
            "not json at all\n" to 0,
            (obj(pcm(10)) + """{"audio_base64": "@@@ not base64 @@@", "alignment": null}""" + "\n") to 1,
            ("""{"audio_base64": "", "alignment": {"characters": ["a", "b"], "character_start_times_seconds": [0.0]}}""" + "\n") to 0,
            ("""{"audio_base64": 42, "alignment": null}""" + "\n") to 0,
            ("""{"audio_base64": "", "alignment": {"character_start_times_seconds": [0.0]}}""" + "\n") to 0,
            ("""{"audio_base64": "", "alignment": {"characters": ["a"]}}""" + "\n") to 0,
            ("""{"audio_base64": "", "alignment": "x"}""" + "\n") to 0,
            ("""{"audio_base64": "", "alignment": {"characters": ["a"], "character_start_times_seconds": ["soon"]}}""" + "\n") to 0,
        )
        for ((body, chunks) in bad) {
            server.enqueue(MockResponse().setBody(body))
            val sink = RecordingSink()
            client().stream("Hi", sink)
            assertTrue(body, sink.done.await(5, TimeUnit.SECONDS))
            assertEquals(body, "ElevenLabs: bad chunk", sink.failure)
            assertEquals(body, 0, sink.finished)
            assertEquals(body, chunks, sink.chunkCount())
        }
    }

    @Test
    fun `a sink that throws still ends the line`() {
        server.enqueue(MockResponse().setBody(obj(pcm(10))))
        val sink = object : RecordingSink() {
            override fun play(pcm: ByteArray): Unit = throw IllegalStateException("boom")
        }
        client().stream("Hi", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertEquals("ElevenLabs: IllegalStateException", sink.failure)
        assertEquals(0, sink.finished)
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
