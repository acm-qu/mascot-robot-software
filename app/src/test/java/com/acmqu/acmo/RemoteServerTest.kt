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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
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

    @Test
    fun `replies are never gzipped, even for a browser that accepts it`() {
        // HttpURLConnection never asks for gzip, so every other test sees a different wire format from a browser's.
        for ((method, path) in listOf("OPTIONS" to "/say", "GET" to "/state")) {
            val c = URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.setRequestProperty("Accept-Encoding", "gzip")
            assertEquals(if (method == "OPTIONS") 204 else 200, c.responseCode)
            assertNull("$method $path", c.getHeaderField("Content-Encoding"))
            assertNull("$method $path", c.getHeaderField("Transfer-Encoding"))
            if (method == "GET") assertEquals("idle", JSONObject(c.inputStream.bufferedReader().readText()).getString("state"))
            c.disconnect()
        }
    }

    @Test
    fun `a body that claims to be huge is refused unread, and the server survives`() {
        Socket("127.0.0.1", server.listeningPort).use { s ->
            s.soTimeout = 5000
            val request = "POST /say HTTP/1.1\r\nHost: acmo\r\nContent-Type: application/json\r\nContent-Length: 2000000000\r\n\r\n{}"
            s.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            s.getOutputStream().flush()
            val status = s.getInputStream().bufferedReader().readLine()
            assertTrue("got: $status", status.startsWith("HTTP/1.1 413"))
        }
        assertTrue(host.said.isEmpty())
        assertEquals(200, request("GET", "/").code)
    }

    @Test
    fun `a host that throws is a 500 that says why`() {
        val broken = object : RemoteServer.Host {
            override fun say(line: Line, now: Boolean): Said = throw IllegalStateException("no voice today")
            override fun hush() {}
            override fun snapshot(): Snapshot = host.snapshot
        }
        val s = RemoteServer(0, broken, onMain = { it() }, hasKey = true)
        assertTrue(s.startListening())
        try {
            val r = request("POST", "/say", """{"text": "Hello"}""", on = s)
            assertEquals(500, r.code)
            assertEquals("IllegalStateException: no voice today", JSONObject(r.body).getString("error"))
        } finally {
            s.stopListening()
        }
    }
}
