package com.acmqu.acmo.kneedle

// Reads a command from the user, asks ./needle which tool it maps to, looks
// that tool up in tools.json, and sends the matching letter to the Arduino.
//
// Two doors in. main() is the command line. runCommand() is the one for other
// code: one prompt in, true out when a letter reached the board. Both walk the
// same path, so a caller gets the whole pipeline - model, lookup, serial - for
// the price of a string.
// This is a compiled source file, not a script, so there are no @file:DependsOn
// lines - both jars have to be on the classpath at compile and run time:
//
//   kotlinc script.kt -cp "libs/*" -d kneedle.jar
//   java -cp "kneedle.jar;libs/*" ScriptKt "do a spin"   # ';' is ':' off Windows
//
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

import java.io.File
import jserialcomm.SerialPort as SerialPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.system.exitProcess

// ------------------------------------------------------------------ setup
val baseDir = File(System.getenv("NEEDLE_HOME") ?: ".").absoluteFile
val toolsFile = File(baseDir, "tools.json")

// Resolved on the first command rather than at class load, so merely calling
// into this file from another one cannot fail: an eager top-level val that
// throws poisons the class for the rest of the JVM's life.
val needleBin = File by lazy {findNeedle(baseDir, toolsFile)} // Is ts even necessary?
// This just adds a lil bit of failure safety ig
// also wt is lazy

// Raised instead of exiting when needle or tools.json cannot be used. Killing
// the process is fine when this file is the process; it is not fine inside a
// caller's, so the failure travels as an exception and each door reports it
// its own way - runCommand returns false, main prints and exits 1.
class NeedleUnavailable(message: String) : IllegalStateException(message)


// ts func is loterally just leftovers from the time we had 2 needle bins (for aarch64 & win32)
fun findNeedle(baseDir: File, toolsFile: File): File {
    val bin = File(baseDir, "needle")
    if (!bin.isFile || !toolsFile.isFile) {
        System.err.println("needle and tools.json not found in $baseDir")
        System.err.println("run from the kneedle/ directory, or set NEEDLE_HOME to it")
        exitProcess(1)
    }
    return bin
}

// isLenient tolerates unquoted keys and single quotes, in case needle's output
// is not strictly spec-clean. Nothing here is @Serializable, so the whole file
// stays on the JsonElement tree API and needs no compiler plugin.
val json = Json { isLenient = true; ignoreUnknownKeys = true }

// Reads a string field off an object without throwing when it is missing or
// is not a primitive - jsonObject/jsonPrimitive both throw on a bad shape.
fun JsonElement?.stringField(key: String): String? =
    ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

// name -> declared tool spec, straight out of tools.json
val declaredTools: Map<String, JsonElement> by lazy {
    needleBin
    loadDeclaredTools(toolsFile, json)
}
fun loadDeclaredTools(toolsFile: File, json: Json): Map<String, JsonElement> = try {

    (json.parseToJsonElement(toolsFile.readText()) as JsonArray)
        .mapNotNull { tool -> tool.stringField("name")?.let { it to tool } }
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

    Thread.sleep(2000) // let the board finish resetting before the first write
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
    val proc = try {
        ProcessBuilder(needleBin.path, "--tools", toolsFile.path, "--prompt", prompt)
            .directory(baseDir)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
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

// True only when a letter actually went down the wire. Every other ending -
// needle silent, no tool matched, no handler for it, port shut - is reported
// on stderr and comes back false, so a caller can branch on the one value.
fun dispatch(prompt: String): Boolean {
    val raw = runNeedle(prompt) ?: return false

    val parsed = try {
        json.parseToJsonElement(raw.trim())
    } catch (e: Exception) {
        System.err.println("needle did not return JSON (${e.message}):")
        System.err.println(raw.trim())
        return false
    }

    val call = ((parsed as? JsonObject)?.get("function_calls") as? JsonArray)?.firstOrNull()
    if (call == null) {
        println("no tool call for: $prompt")
        // THIS IS WHERE we pass unknown voice commands to a smarter model
        // If we can trust needle to send empty lists upon recieving an unknown vc
        // Claude turned this func's return type from unit to boolwan so this
        // case might NOT return false
        return false
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
    sendLetter(letter)
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