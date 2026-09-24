package com.acmqu.acmo.kneedle

/*
* // Reads a command from the user, asks ./needle which tool it maps to, looks
// that tool up in tools.json, and sends the matching letter to the Arduino.
//
// Two doors in. main() is the command line. runCommand() is the one for other
// code: one prompt in, true out when a letter reached the board. Both walk the
// same path, so a caller gets the whole pipeline - model, lookup, serial - for
// the price of a string.
// This is a compiled source file, not a script, so there are no @file:DependsOn
// lines - both jars (jSerialComm and org.json; Android ships org.json itself,
// a plain JVM does not) have to be on the classpath at compile and run time:
//
//   kotlinc script.kt -cp "libs/'*" -d kneedle.jar
//   java -cp "kneedle.jar;libs/'*" ScriptKt "do a spin"   # ';' is ':' off Windows
// (the single quotations (') after libs/ in the 2 lines avobe are for comments theyre not
* actually a part of the syntax or anytging)
*
// From another file in the same project:
//
//   if (!runCommand("turn the LED on")) System.err.println("nothing sent")
//   close()   // when done: the port is held open between commands
//
// Java sees the same pair as ScriptKt.runCommand(String) and ScriptKt.close().
//
// Environment:
//   NEEDLE_HOME   directory holding ./needle and tools.json (default: cwd)
//   ARDUINO_PORT  serial port name (default: the first port found)
//   ARDUINO_BAUD  baud rate (default: 9600 - must match Serial.begin())

* */

import java.io.File
import com.fazecast.jSerialComm.SerialPort
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.exitProcess
import com.acmqu.acmo.BuildConfig
import com.acmqu.acmo.gemini.GeminiClient
import kotlinx.coroutines.runBlocking

// ------------------------------------------------------------------ setup
val baseDir = File(System.getenv("NEEDLE_HOME") ?: ".").absoluteFile

var geminiClient: GeminiClient? = null
var previousInteractionId: String? = null

fun ensureGeminiClient(): GeminiClient? {
    geminiClient?.let { return it }
    val apiKey = try {
        BuildConfig.GEMINI_API_KEY.ifBlank { System.getenv("GEMINI_API_KEY").orEmpty() }
    } catch (_: Throwable) {
        System.getenv("GEMINI_API_KEY").orEmpty()
    }
    if (apiKey.isBlank()) {
        System.err.println("Gemini API key is empty -- cannot handle fallback conversation")
        return null
    }
    val client = GeminiClient(apiKey)
    geminiClient = client
    return client
}

val toolsFile: File by lazy { findToolsFile(baseDir) }

// Resolved on the first command rather than at class load, so merely calling
// into this file from another one cannot fail: an eager top-level val that
// throws poisons the class for the rest of the JVM's life.
val needleBin: File by lazy { findNeedle(baseDir, toolsFile) }

fun findToolsFile(baseDir: File): File {
    val candidateDirs = listOfNotNull(
        System.getenv("NEEDLE_HOME")?.let { File(it) },
        File(baseDir, "app/src/main/jniLibs"),
        File(baseDir, "src/main/jniLibs"),
        File(baseDir, "jniLibs"),
        File(baseDir, "../../../jniLibs"),
        File(baseDir, "../../../../../jniLibs"),
        baseDir
    )
    for (dir in candidateDirs) {
        val f = File(dir, "tools.json")
        if (f.isFile) return f
    }
    return File(baseDir, "tools.json")
}

fun findNeedle(baseDir: File, toolsFile: File): File {
    val candidateDirs = listOfNotNull(
        System.getenv("NEEDLE_HOME")?.let { File(it) },
        File(baseDir, "app/src/main/jniLibs"),
        File(baseDir, "src/main/jniLibs"),
        File(baseDir, "jniLibs"),
        File(baseDir, "../../../jniLibs"),
        File(baseDir, "../../../../../jniLibs"),
        baseDir
    )
    for (dir in candidateDirs) {
        val binDirect = File(dir, "needle")
        if (binDirect.isFile) return binDirect
        val binArm64 = File(dir, "arm64-v8a/needle")
        if (binArm64.isFile) return binArm64
    }
    if (!toolsFile.isFile) {
        System.err.println("tools.json not found at ${toolsFile.path}")
    }
    System.err.println("needle or tools.json not found in jniLibs or $baseDir")
    System.err.println("ensure needle is in app/src/main/jniLibs/ or set NEEDLE_HOME")
    exitProcess(1)
}

// Raised instead of exiting when needle or tools.json cannot be used. Killing
// the process is fine when this file is the process; it is not fine inside a
// caller's, so the failure travels as an exception and each door reports it
// its own way - runCommand returns false, main prints and exits 1.
class NeedleUnavailable(message: String) : IllegalStateException(message)

// Reads a string field off an object without throwing when it is missing or
// is not a primitive - getString would throw, and optString would hand back ""
// for a missing key and a nested object's JSON text for a bad shape.
fun JSONObject?.stringField(key: String): String? =
    when (val v = this?.opt(key)) {
        null, JSONObject.NULL, is JSONObject, is JSONArray -> null
        else -> v.toString()
    }

// name -> declared tool spec, straight out of tools.json
val declaredTools: Map<String, JSONObject> by lazy {
    needleBin
    loadDeclaredTools(toolsFile)
}
fun loadDeclaredTools(toolsFile: File): Map<String, JSONObject> = try {
    val arr = JSONArray(toolsFile.readText())
    (0 until arr.length())
        .mapNotNull { i -> arr.optJSONObject(i)?.let { tool -> tool.stringField("name")?.let { it to tool } } }
        .toMap()
} catch (e: Exception) {
    System.err.println("could not parse ${toolsFile.name}: ${e.message}")
    exitProcess(1)
}

// ------------------------------------------------------------------ serial

//
val baudRate: Int = System.getenv("ARDUINO_BAUD")?.toIntOrNull() ?: 115200 
val configuredPort: String? = System.getenv("ARDUINO_PORT")
var arduino: SerialPort? = null

// Opened once and held for the life of the script, because opening the port
// toggles DTR and resets most Arduino boards - reopening per command would
// mean a bootloader wait before every single letter.
fun serialPort(): SerialPort? {
    arduino?.let { if (it.isOpen) return it }

    val port = if (configuredPort != null) {
        SerialPort.getCommPort(configuredPort)
    } else {
        val found = SerialPort.getCommPorts()
        if (found.isEmpty()) {
            System.err.println("no serial ports found; set ARDUINO_PORT")
            return null
        }
        if (found.size > 1) {
            System.err.println("several ports: ${found.joinToString(", ") { it.systemPortName }}")
            System.err.println("using ${found[0].systemPortName}; set ARDUINO_PORT to pick another")
        }
        found[0]
    }

    port.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
    port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, 2000)

    if (!port.openPort()) {
        System.err.println("could not open ${port.systemPortName} at $baudRate baud")
        return null
    }

    Thread.sleep(2000) 
    arduino = port
    return port
}

fun sendLetter(letter: Char): Boolean {
    val port = serialPort() ?: return false
    val payload = byteArrayOf(letter.code.toByte())
    val written = port.writeBytes(payload, payload.size)
    if (written != payload.size) {
        System.err.println("serial write of '$letter' failed (wrote $written bytes)")
        return false
    }
    return true
}

// ------------------------------------------------------------------ tools

// Each tool prints what it is about to do, then yields the letter to send.
// The printing is acually for debugging only
// Keyed by the names in tools.json; a tool declared there but missing here is
// reported, not ignored.
val tools: Map<String, () -> Char> = mapOf(
    "spin" to {
        println("[tool] spin - robot spins in place, sending 's'")
        's'
    },
    "dance" to {
        println("[tool] dance - robot dances, sending 'd'")
        'd'
    },

)

// ------------------------------------------------------------------ dispatch

fun runNeedle(prompt: String): String? {
    val workingDir = needleBin.parentFile ?: baseDir
    val proc = try {
        ProcessBuilder(needleBin.path, "--tools", toolsFile.path, "--prompt", prompt)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
    } catch (e: Exception) {
        // needle is an aarch64 Android binary - this is what you get off-device
        System.err.println("could not run ${needleBin.path}: ${e.message}")
        return null
    }
    val stdout = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    if (code != 0) {
        System.err.println("needle exited with $code")
        return null
    }
    return stdout
}

// True only when a letter actually went down the wire. Every other ending 
// is reported on stderr and comes back false, so a caller can branch on the one value.
fun dispatch(prompt: String): Boolean {
    val raw = runNeedle(prompt) ?: return false

    val parsed = try {
        JSONObject(raw.trim())
    } catch (e: Exception) {
        System.err.println("needle did not return a JSON object (${e.message}):")
        System.err.println(raw.trim())
        return false
    }

    val functionCalls = parsed.optJSONArray("function_calls")
    val call = functionCalls?.optJSONObject(0)
    if (call == null) {
        println("no tool call for: $prompt - passing to Gemini for conversation")
        val client = ensureGeminiClient()
        if (client == null) {
            System.err.println("Gemini client unavailable for fallback conversation")
            return false
        }
        return try {
            val reply = runBlocking { client.reply(prompt, previousInteractionId) }
            previousInteractionId = reply.interactionId
            for (segment in reply.segments) {
                println("[gemini] (${segment.feeling.label}) ${segment.text}")
            }
            true
        } catch (e: Exception) {
            System.err.println("Gemini reply failed: ${e.message}")
            false
        }
    }

    val name = call.stringField("name")
    if (name == null) {
        System.err.println("tool call had no name: $raw")
        return false
    }

    // the lookup: does this name correspond to a tool declared in tools.json?
    if (!declaredTools.containsKey(name)) {
        System.err.println("needle picked '$name', which is not declared in ${toolsFile.name}")
        System.err.println("declared: ${declaredTools.keys.joinToString(", ")}")
        return false
    }

    val tool = tools[name]
    if (tool == null) {
        System.err.println("'$name' is declared in ${toolsFile.name} but has no handler in this script")
        return false
    }

    val letter = tool() // prints the tool's own debug line
    println("[$name] serial <- '$letter'")
    return sendLetter(letter)
}

// ------------------------------------------------------------------ api

// The door for other code: hand it one sentence, get back whether the board
// was told anything. Setup trouble is printed rather than thrown, so a caller
// never has to wrap this in a try - and never has its own process exited out
// from under it.
//
//   runCommand("make it dance")
//
// Not thread safe: two prompts at once race for the single held-open port.
fun runCommand(prompt: String): Boolean = try {
    dispatch(prompt)
} catch (e: NeedleUnavailable) {
    System.err.println(e.message)
    false
}

// The names a prompt can resolve to, for a caller that wants to list or check
// them first. Empty when tools.json cannot be read - like runCommand, nothing
// in this section throws at a caller.
fun availableTools(): Set<String> = try {
    declaredTools.keys
} catch (e: NeedleUnavailable) {
    System.err.println(e.message)
    emptySet()
}

// Gives the port back. Safe when nothing was ever opened, and a later
// runCommand just opens it again - at the price of another board reset, so
// call this when the run is over, not between commands.
fun close() {
    arduino?.let { if (it.isOpen) it.closePort() }
    arduino = null
}
// ------------------------------------------------------------------ main

fun main (args: Array<String>) {
    val failed = try {
        if (args.isNotEmpty()) {
            dispatch(args.joinToString(" "))
        } else {
            // declaredTools, not availableTools(): on the command line an
            // unreadable tools.json should stop the run, not open a prompt
            // that can never match anything
            println("tools: ${declaredTools.keys.joinToString(", ")}")
            println("enter a command; blank line or Ctrl-D quits")
            while (true) {
                print("> ")
                System.out.flush()
                val line = readLine()?.trim()
                if (line.isNullOrEmpty()) break
                dispatch(line)
            }
        }

    false
} catch (e: NeedleUnavailable) {
    System.err.println(e.message)
    true
}
finally {
        close()
    }
    // outside the finally: exitProcess skips it, so the port shuts first
    if (failed) exitProcess(1)
}