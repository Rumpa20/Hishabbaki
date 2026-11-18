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

    fun fileNameFor(businessId: Int): String =
        if (businessId <= 1) FILE_NAME else "logo_" + businessId + ".png"

    fun file(ctx: Context, businessId: Int = 1): File {
        return File(ctx.filesDir, fileNameFor(businessId))
    }

    fun loadFile(ctx: Context, businessId: Int = 1): Bitmap? = try {
        BitmapFactory.decodeFile(file(ctx, businessId).path)
    } catch (e: Exception) {
        null
    }

    fun load(ctx: Context, profile: BusinessProfile): Bitmap? {
        if (profile.logoPath.isBlank()) return null
        return try {
            BitmapFactory.decodeFile(File(ctx.filesDir, profile.logoPath).path)
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
    fun saveFromUri(ctx: Context, uri: Uri, businessId: Int = 1): Boolean {
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
            FileOutputStream(file(ctx, businessId)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(ctx: Context, businessId: Int = 1) {
        File(ctx.filesDir, fileNameFor(businessId)).delete()
    }
}
