import java.net.URI
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

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

android {
    namespace = "com.acmqu.acmo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.acmqu.acmo"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        // The remote console's voice. A blank voice means ElevenLabs.DEFAULT_VOICE_ID.
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"$elevenLabsApiKey\"")
        buildConfigField("String", "ELEVENLABS_VOICE_ID", "\"$elevenLabsVoiceId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // android.util.Log returns 0 instead of throwing "not mocked", so the server and
        // the ElevenLabs client, which log, can be unit tested on the JVM.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ---------------------------------------------------------------------------
// The wake-word model. Vosk's small English model is 40 MB, so it is not in git:
// this task fetches it once into src/main/assets/model-en-us (gitignored) and
// preBuild depends on it. Delete that folder to force a fresh download.
val voskModelUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
val voskModelDir = layout.projectDirectory.dir("src/main/assets/model-en-us").asFile

val downloadVoskModel by tasks.registering {
    description = "Downloads the Vosk small English model into src/main/assets/model-en-us."
    val dir = voskModelDir
    val zip = layout.buildDirectory.file("vosk-model.zip")
    onlyIf { !dir.resolve("uuid").exists() }
    doLast {
        val zipFile = zip.get().asFile
        zipFile.parentFile.mkdirs()
        logger.lifecycle("Downloading $voskModelUrl (40 MB) ...")
        URI(voskModelUrl).toURL().openStream().use { input ->
            zipFile.outputStream().use { output -> input.copyTo(output) }
        }
        dir.deleteRecursively()
        dir.mkdirs()
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            generateSequence { zin.nextEntry }.forEach { entry ->
                // Entries look like "vosk-model-small-en-us-0.15/am/final.mdl"; drop the top folder.
                val rel = entry.name.substringAfter('/', "")
                if (rel.isEmpty() || entry.isDirectory) return@forEach
                val out = dir.resolve(rel)
                out.parentFile.mkdirs()
                out.outputStream().use { zin.copyTo(it) }
            }
        }
        zipFile.delete()
        // Written last, so an interrupted download runs again. The app compares it
        // with the copy it unpacked on the device to know when to unpack again.
        dir.resolve("uuid").writeText(UUID.randomUUID().toString())
    }
}

tasks.named("preBuild") {
    dependsOn(downloadVoskModel)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // The remote console's server on the tablet.
    implementation(libs.nanohttpd)
    implementation(libs.vosk.android)
    // Vosk reaches its native library through JNA; its POM asks for the AAR flavour.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    testImplementation(libs.junit)
    // Android's org.json is a stub on the JVM; the real one makes the parsers testable.
    testImplementation(libs.json)
    // A local HTTP server that plays ElevenLabs in the client's tests.
    testImplementation(libs.okhttp.mockwebserver)
}
