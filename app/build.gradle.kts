import java.net.URI
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

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
    implementation(libs.vosk.android)
    // Vosk reaches its native library through JNA; its POM asks for the AAR flavour.
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    testImplementation(libs.junit)
    // Android's org.json is a stub on the JVM; the real one makes the parsers testable.
    testImplementation(libs.json)
}
