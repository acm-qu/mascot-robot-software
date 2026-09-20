package com.acmqu.acmo.kneedle

// Reads a command from the user, asks ./needle which tool it maps to, looks
// that tool up in tools.json, and sends the matching letter to the Arduino.
//
// Jars resolve from Maven Central on first run and are cached after. To skip
// resolution entirely, drop the two @file:DependsOn lines, keep the name
// script.kts, and put both jars on the classpath by hand:
//
//   kotlinc -script script.kts -cp "libs/*" -- "do a spin"
//
// Environment:
//   NEEDLE_HOME   directory holding ./needle and tools.json (default: cwd)
//   ARDUINO_PORT  serial port name (default: the first port found)
//   ARDUINO_BAUD  baud rate (default: 9600 - must match Serial.begin())

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.0")
@file:DependsOn("com.fazecast:jSerialComm:2.11.0")

import java.io.File
import com.fazecast.jSerialComm.SerialPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull


// ------------------------------------------------------------------ setup

// ----THIS WHOLE SEGMENT IS UNNEEDED WHEN USING ONLY THE AARCH64 BINARY----------
val baseDir = File(System.getenv("NEEDLE_HOME") ?: ".").absoluteFile
val toolsFile = File(baseDir, "tools.json")

// needle.exe when testing on Windows, needle on the Termux target. The bare
// name is not enough: CreateProcess will happily pick up the extensionless
// aarch64 binary sitting next to it and fail with "not a valid Win32 app".
val needleBin: File = listOf("needle.exe", "needle")
    .map { File(baseDir, it) }
    .firstOrNull { it.isFile }
    ?: File(baseDir, "needle") // missing either way; reported just below

if (!needleBin.isFile || !toolsFile.isFile) {
    System.err.println("needle and tools.json not found in $baseDir")
    System.err.println("run from the kneedle/ directory, or set NEEDLE_HOME to it")
    kotlin.system.exitProcess(1)
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
val declaredTools: Map<String, JsonElement> = try {
    (json.parseToJsonElement(toolsFile.readText()) as JsonArray)
        .mapNotNull { tool -> tool.stringField("name")?.let { it to tool } }
        .toMap()
} catch (e: Exception) {
    System.err.println("could not parse ${toolsFile.name}: ${e.message}")
    kotlin.system.exitProcess(1)
}

// ------------------------------------------------------------------ serial

val baudRate: Int = System.getenv("ARDUINO_BAUD")?.toIntOrNull() ?: 9600
val configuredPort: String? = System.getenv("ARDUINO_PORT")

var arduino: SerialPort? = null

// Opened once and held for the life of the script, because opening the port
// toggles DTR and resets most Arduino boards - reopening per command would
// mean a bootloader wait before every single letter.
//-----THERE'S NO WAY IT NEEDS THIS MUCH CODE----------
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
    // '1'/'0' rather than letters: 's' is already spin's, and the sketch's own
    // servo branches take 'f'/'s'/'a', so the digits stay clear of both.
    "LED on" to {
        println("[tool] LED on - LED lights up, sending '1'")
        '1'
    },
    "LED off" to {
        println("[tool] LED off - LED goes dark, sending '0'")
        '0'
    }
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

fun dispatch(prompt: String) {
    val raw = runNeedle(prompt) ?: return

    val parsed = try {
        json.parseToJsonElement(raw.trim())
    } catch (e: Exception) {
        System.err.println("needle did not return JSON (${e.message}):")
        System.err.println(raw.trim())
        return
    }

    val call = ((parsed as? JsonObject)?.get("function_calls") as? JsonArray)?.firstOrNull()
    if (call == null) {
        println("no tool call for: $prompt")
        return
    }

    val name = call.stringField("name")
    if (name == null) {
        System.err.println("tool call had no name: $raw")
        return
    }

    // the lookup: does this name correspond to a tool declared in tools.json?
    if (!declaredTools.containsKey(name)) {
        System.err.println("needle picked '$name', which is not declared in ${toolsFile.name}")
        System.err.println("declared: ${declaredTools.keys.joinToString(", ")}")
        return
    }

    val tool = tools[name]
    if (tool == null) {
        System.err.println("'$name' is declared in ${toolsFile.name} but has no handler in this script")
        return
    }

    val letter = tool() // prints the tool's own debug line
    println("[$name] serial <- '$letter'")
    sendLetter(letter)
}

// ------------------------------------------------------------------ main

try {
    if (args.isNotEmpty()) {
        dispatch(args.joinToString(" "))
    } else {
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
} finally {
    arduino?.let { if (it.isOpen) it.closePort() }
}
