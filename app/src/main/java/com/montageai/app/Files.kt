package com.montageai.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

fun copyAudio(ctx: Context, uri: Uri): PickedAudio {
    val cr = ctx.contentResolver
    var name = "audio"
    cr.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
    }
    val mime = cr.getType(uri) ?: "audio/mpeg"
    val ext = name.substringAfterLast('.', "").ifBlank {
        if (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")) "m4a"
        else if (mime.contains("wav")) "wav"
        else if (mime.contains("ogg")) "ogg"
        else "mp3"
    }
    val f = File(ctx.cacheDir, "voice.$ext")
    cr.openInputStream(uri)?.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
    return PickedAudio(f, name, mime)
}

fun copyImage(ctx: Context, uri: Uri): String? {
    val f = File(ctx.filesDir, "cover_${UUID.randomUUID()}.img")
    ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
        ?: return null
    return f.absolutePath
}

fun saveToGallery(ctx: Context, file: File): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "montage_${System.currentTimeMillis()}.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MontageAI")
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
    values.clear()
    values.put(MediaStore.Video.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return uri
}
