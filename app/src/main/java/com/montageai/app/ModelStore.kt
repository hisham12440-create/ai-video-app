package com.montageai.app

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Whisper "small" speech model (quantized q5_1, about 181 MB). It is downloaded once from
 * Hugging Face, or imported from a file, and then everything runs on the phone.
 */
object ModelStore {
    const val FILE_NAME = "ggml-small-q5_1.bin"
    const val URL_STRING = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
    const val APPROX_MB = 181
    private const val MIN_BYTES = 100L * 1024 * 1024

    fun file(ctx: Context): File {
        val dir = File(ctx.filesDir, "models")
        dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    private fun partFile(ctx: Context) = File(file(ctx).parentFile, "$FILE_NAME.part")

    fun isReady(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() >= MIN_BYTES }

    fun partialBytes(ctx: Context): Long = partFile(ctx).let { if (it.exists()) it.length() else 0L }

    fun delete(ctx: Context) {
        file(ctx).delete()
        partFile(ctx).delete()
    }

    /** Downloads (and resumes) the model. Cancel by cancelling the calling coroutine. */
    suspend fun download(ctx: Context, onProgress: (done: Long, total: Long) -> Unit) {
        val target = file(ctx)
        val part = partFile(ctx)
        var done = if (part.exists()) part.length() else 0L

        var conn = open(done)
        var code = conn.responseCode
        if (code == 416) {
            // Our partial file is not usable for a range request: start over.
            conn.disconnect()
            part.delete()
            done = 0L
            conn = open(0L)
            code = conn.responseCode
        }
        try {
            if (code != 200 && code != 206) throw IOException("فشل تنزيل الموديل (HTTP $code). تأكد من الاتصال بالإنترنت.")
            if (code == 200 && done > 0L) {
                // The server ignored the range: restart from zero.
                part.delete()
                done = 0L
            }
            val length = conn.contentLengthLong
            val total = if (length > 0) done + length else -1L

            var lastReport = 0L
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, done > 0L).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            if (total > 0 && part.length() != total) {
                throw IOException("التنزيل لم يكتمل. اضغط «تنزيل» مرة أخرى لاستكماله.")
            }
            if (part.length() < MIN_BYTES) throw IOException("الملف المنزَّل أصغر من المتوقع. أعد المحاولة.")
            if (!hasGgmlMagic(part)) {
                part.delete()
                throw IOException("الملف المنزَّل ليس موديل Whisper صالحاً. أعد المحاولة.")
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("تعذّر حفظ الموديل على الجهاز.")
        } finally {
            conn.disconnect()
        }
    }

    /** Copies a model file picked by the user (any Whisper ggml model works). */
    fun importFrom(ctx: Context, uri: Uri) {
        val part = partFile(ctx)
        part.delete()
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("تعذّر فتح الملف.")
        input.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
        if (!hasGgmlMagic(part)) {
            part.delete()
            throw IOException("هذا الملف ليس موديل Whisper بصيغة ggml.")
        }
        val target = file(ctx)
        target.delete()
        if (!part.renameTo(target)) throw IOException("تعذّر حفظ الموديل على الجهاز.")
    }

    private fun open(from: Long): HttpURLConnection {
        val c = URL(URL_STRING).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        if (from > 0L) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    /** whisper.cpp model files start with the bytes "lmgg" (the int 0x67676d6c, little-endian). */
    private fun hasGgmlMagic(f: File): Boolean = try {
        f.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 0x6c.toByte() && b[1] == 0x6d.toByte() &&
                b[2] == 0x67.toByte() && b[3] == 0x67.toByte()
        }
    } catch (e: Exception) {
        false
    }
}
