package com.montageai.app

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Speech-to-text with per-word timestamps (OpenAI Whisper API). */
object Transcriber {
    fun transcribe(apiKey: String, file: File, mime: String, language: String = "ar"): List<Word> {
        val resp = Http.postMultipart(
            url = "https://api.openai.com/v1/audio/transcriptions",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            fields = mapOf(
                "model" to "whisper-1",
                "response_format" to "verbose_json",
                "timestamp_granularities[]" to "word",
                "language" to language,
            ),
            fileField = "file",
            file = file,
            mime = mime,
        )
        val arr = JSONObject(resp).optJSONArray("words")
            ?: throw IOException("لم يرجع الخادم توقيت الكلمات. جرّب ملف صوت آخر.")
        val out = ArrayList<Word>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Word(o.getString("word").trim(), o.getDouble("start"), o.getDouble("end")))
        }
        if (out.isEmpty()) throw IOException("لم يتم التعرف على أي كلام في الملف الصوتي.")
        return out
    }
}
