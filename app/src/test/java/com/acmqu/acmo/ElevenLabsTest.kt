package com.acmqu.acmo

import com.acmqu.acmo.voice.ElevenLabs
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
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
        fun chunkSizes(): List<Int> = synchronized(chunks) { chunks.map { it.size } }
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
    fun `reads that end mid-frame still hand over whole frames`() {
        // A 16-bit frame is two bytes; the network hands over any number. The player must never see a half frame.
        val audio = ByteArray(20_001) { (it % 251).toByte() }
        server.enqueue(MockResponse().setChunkedBody(Buffer().write(audio), 3001))
        val sink = RecordingSink()
        client().stream("Hello", sink)
        assertTrue(sink.done.await(5, TimeUnit.SECONDS))
        assertNull(sink.failure)
        assertEquals(1, sink.finished)
        assertTrue("every chunk is whole frames", sink.chunkSizes().all { it % 2 == 0 })
        assertArrayEquals(audio.copyOf(20_000), sink.audio())   // the dangling odd byte is not a frame
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
        server.enqueue(
            MockResponse().setChunkedBody(Buffer().write(ByteArray(200_000)), 3000)
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
    fun `a 2xx with no audio at all just finishes`() {
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
