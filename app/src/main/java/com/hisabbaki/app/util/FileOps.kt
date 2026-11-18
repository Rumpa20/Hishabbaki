package com.hisabbaki.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.IOException

object FileOps {

    /** Temporary files that can be shared through the FileProvider (cache/shared/). */
    fun shareDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, "shared")
        dir.mkdirs()
        return dir
    }

    fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
        return if (cleaned.isBlank() || cleaned.all { it == '_' }) "ledger" else cleaned
    }

    fun share(ctx: Context, file: File, mime: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = mime
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, chooserTitle))
    }

    /** UTF-8 with BOM so Excel shows Bengali / Hindi text correctly. */
    fun writeText(ctx: Context, uri: Uri, text: String) {
        val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open file")
        out.use {
            it.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            it.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    fun copyFileToUri(ctx: Context, file: File, uri: Uri) {
        val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open file")
        out.use { o ->
            FileInputStream(file).use { input -> input.copyTo(o) }
        }
    }
}
