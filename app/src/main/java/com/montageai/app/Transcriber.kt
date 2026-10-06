package com.montageai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Speech to text on the phone: decode, resample to 16 kHz, run Whisper, return timed words. */
object Transcriber {
    private const val WHISPER_RATE = 16_000

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        if (WhisperLib.available) WhisperLib.nativeAbort()
    }

    /** Returns the words, or null if the user cancelled. */
    fun run(ctx: Context, audioPath: String, report: (String, Float) -> Unit): List<Word>? {
        cancelled = false
        val model = ModelStore.file(ctx)
        if (!ModelStore.isReady(ctx)) throw IOException("نزّل موديل التفريغ أولاً.")

        report("جاري تجهيز الصوت…", 0.02f)
        val pcm = AudioTools.decodeToMono(audioPath)
        AudioTools.normalize(pcm.samples)
        val samples = toWhisperInput(pcm)
        if (samples.size < WHISPER_RATE / 2) throw IOException("التسجيل قصير جداً.")
        if (cancelled) return null

        report("جاري تحميل الموديل…", 0.05f)
        WhisperEngine(model.absolutePath).use { engine ->
            if (cancelled) return null
            report("جاري التفريغ…", 0.06f)
            val threads = min(4, max(2, Runtime.getRuntime().availableProcessors()))
            val words = engine.transcribe(samples, "ar", threads) { percent ->
                report("جاري التفريغ… $percent%", 0.06f + 0.94f * (percent.coerceIn(0, 100) / 100f))
            }
            if (cancelled) return null
            return words
        }
    }

    /** Mono 16-bit PCM at any rate -> mono float at 16 kHz (averaging the samples of each output step). */
    fun toWhisperInput(pcm: Pcm): FloatArray {
        val src = pcm.samples
        val sr = pcm.sampleRate
        if (sr == WHISPER_RATE) {
            return FloatArray(src.size) { src[it] / 32768f }
        }
        val ratio = sr.toDouble() / WHISPER_RATE
        val outN = (src.size / ratio).toInt()
        val out = FloatArray(outN)
        for (i in 0 until outN) {
            val a = (i * ratio).toInt()
            var b = ((i + 1) * ratio).toInt()
            if (b <= a) b = a + 1
            if (b > src.size) b = src.size
            var sum = 0L
            for (k in a until b) sum += src[k]
            out[i] = sum.toFloat() / (b - a) / 32768f
        }
        return out
    }
}

/** Keeps each project's transcript on disk so it is only computed once per audio file. */
object TranscriptStore {
    private fun file(ctx: Context, projectId: String) = File(ctx.filesDir, "transcript_$projectId.json")

    private fun key(audioPath: String): String {
        val f = File(audioPath)
        return "${f.length()}-${f.lastModified()}"
    }

    fun load(ctx: Context, projectId: String, audioPath: String): List<Word>? {
        val f = file(ctx, projectId)
        if (!f.exists() || !File(audioPath).exists()) return null
        return try {
            val o = JSONObject(f.readText())
            if (o.optString("key") != key(audioPath)) return null
            val arr = o.getJSONArray("words")
            (0 until arr.length()).map { i ->
                val w = arr.getJSONArray(i)
                Word(w.getString(0), w.getDouble(1), w.getDouble(2))
            }
        } catch (e: Exception) {
            null
        }
    }

    fun save(ctx: Context, projectId: String, audioPath: String, words: List<Word>) {
        val arr = JSONArray()
        for (w in words) arr.put(JSONArray().put(w.text).put(w.start).put(w.end))
        file(ctx, projectId).writeText(JSONObject().put("key", key(audioPath)).put("words", arr).toString())
    }

    fun delete(ctx: Context, projectId: String) {
        file(ctx, projectId).delete()
    }
}
