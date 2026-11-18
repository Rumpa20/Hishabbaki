package com.hisabbaki.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hisabbaki.app.data.BusinessProfile
import java.io.File
import java.io.FileOutputStream

object LogoStore {
    const val FILE_NAME = "business_logo.png"

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun load(ctx: Context, profile: BusinessProfile): Bitmap? {
        if (profile.logoPath.isBlank()) return null
        return try {
            BitmapFactory.decodeFile(file(ctx).path)
        } catch (e: Exception) {
            null
        }
    }

    fun loadFile(ctx: Context): Bitmap? = try {
        BitmapFactory.decodeFile(file(ctx).path)
    } catch (e: Exception) {
        null
    }

    /** Copies the chosen image into app storage, scaled down to at most ~512 px. Blocking. */
    fun saveFromUri(ctx: Context, uri: Uri): Boolean {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            FileOutputStream(file(ctx)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(ctx: Context) {
        file(ctx).delete()
    }
}
