package com.acmqu.acmo.voice

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File

/**
 * Vosk needs its model as plain files, so the copy in assets (put there by the
 * downloadVoskModel Gradle task) is unpacked into internal storage once. The
 * `uuid` file identifies the download; a new one means unpack again.
 */
object ModelInstaller {
    private const val ASSET_DIR = "model-en-us"

    suspend fun load(context: Context): Model = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, ASSET_DIR)
        val assetUuid = context.assets.open("$ASSET_DIR/uuid").bufferedReader().use { it.readText() }.trim()
        val installed = File(target, "uuid").takeIf { it.exists() }?.readText()?.trim()
        if (assetUuid != installed) {
            target.deleteRecursively()
            copyAssetTree(context.assets, ASSET_DIR, target)
        }
        Model(target.absolutePath)
    }

    private fun copyAssetTree(assets: AssetManager, path: String, dest: File) {
        val children = assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetTree(assets, "$path/$child", File(dest, child))
    }
}
