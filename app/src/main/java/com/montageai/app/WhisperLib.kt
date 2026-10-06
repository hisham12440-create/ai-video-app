package com.montageai.app

import java.io.IOException

interface WhisperProgress {
    fun onProgress(percent: Int)
}

/** Raw JNI entry points of libmontage_whisper.so (whisper.cpp). Use [WhisperEngine] instead. */
object WhisperLib {
    /** False when the native library cannot be loaded (for example on an unsupported CPU). */
    val available: Boolean by lazy {
        try {
            System.loadLibrary("montage_whisper")
            true
        } catch (e: Throwable) {
            false
        }
    }

    external fun nativeInit(path: String): Long
    external fun nativeFree(handle: Long)
    external fun nativeAbort()
    external fun nativeTranscribe(
        handle: Long, samples: FloatArray, lang: String, threads: Int, listener: WhisperProgress?,
    ): Int

    external fun nativeSegmentCount(handle: Long): Int
    external fun nativeSegmentText(handle: Long, index: Int): ByteArray
    external fun nativeSegmentStart(handle: Long, index: Int): Long
    external fun nativeSegmentEnd(handle: Long, index: Int): Long
    external fun nativeSystemInfo(): String
}

/** One loaded Whisper model. Not thread-safe; use from one thread at a time and [close] when done. */
class WhisperEngine(modelPath: String) : AutoCloseable {
    private val handle: Long

    init {
        if (!WhisperLib.available) throw IOException("التفريغ التلقائي غير مدعوم على هذا الجهاز.")
        handle = WhisperLib.nativeInit(modelPath)
        if (handle == 0L) throw IOException("تعذّر تحميل موديل التفريغ. احذفه ونزّله من جديد.")
    }

    /** Returns one [Word] per spoken word, or null if the run was aborted. */
    fun transcribe(samples: FloatArray, language: String, threads: Int, report: (Int) -> Unit): List<Word>? {
        val listener = object : WhisperProgress {
            override fun onProgress(percent: Int) {
                report(percent)
            }
        }
        val rc = WhisperLib.nativeTranscribe(handle, samples, language, threads, listener)
        if (rc == 1) return null
        if (rc != 0) throw IOException("فشل التفريغ (الرمز $rc). جرّب تسجيلاً أقصر أو أعد تشغيل التطبيق.")

        val out = ArrayList<Word>()
        val n = WhisperLib.nativeSegmentCount(handle)
        for (i in 0 until n) {
            val text = String(WhisperLib.nativeSegmentText(handle, i), Charsets.UTF_8).trim()
            if (!isSpeechText(text)) continue
            val start = WhisperLib.nativeSegmentStart(handle, i) / 100.0
            val end = WhisperLib.nativeSegmentEnd(handle, i) / 100.0
            out.add(Word(text, start, if (end >= start) end else start))
        }
        return out
    }

    override fun close() {
        WhisperLib.nativeFree(handle)
    }

    private fun isSpeechText(t: String): Boolean {
        if (t.isBlank()) return false
        // Whisper marks non-speech as [BLANK_AUDIO], (music), ♪ ... and sometimes emits lone punctuation.
        if ((t.startsWith("[") && t.endsWith("]")) || (t.startsWith("(") && t.endsWith(")"))) return false
        return t.any { it.isLetterOrDigit() }
    }
}
