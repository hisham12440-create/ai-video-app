package com.montageai.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** transcribe -> decode -> direct (plan) -> mix sound -> render + encode. */
class Pipeline(private val ctx: Context) {

    suspend fun run(
        audio: PickedAudio,
        settings: Settings,
        sources: List<SourceCard>,
        report: (String, Float) -> Unit,
    ): File = withContext(Dispatchers.Default) {
        require(settings.openAiKey.isNotBlank()) { "أدخل مفتاح OpenAI من الإعدادات (لتفريغ الصوت وتوقيت الكلمات)." }
        require(sources.isNotEmpty()) { "أضف مصدراً واحداً على الأقل." }
        if (audio.file.length() > 25L * 1024 * 1024) {
            throw IOException("حجم الملف الصوتي أكبر من 25 ميغابايت. اختصره أو اضغطه ثم أعد المحاولة.")
        }

        report("جاري تفريغ الصوت وتوقيت الكلمات…", 0.05f)
        val words = Transcriber.transcribe(settings.openAiKey, audio.file, audio.mime)

        report("جاري تجهيز الصوت…", 0.2f)
        val pcm = AudioTools.decodeToMono(audio.file.path)
        AudioTools.normalize(pcm.samples)
        val sr = pcm.sampleRate
        val duration = pcm.samples.size / sr.toDouble()

        val usedClaude = settings.anthropicKey.isNotBlank()
        report(
            if (usedClaude) "المخرج يكتب خطة المونتاج…" else "توزيع المصادر تلقائياً (لا يوجد مفتاح Claude)…",
            0.3f,
        )
        val plan = Director.plan(settings, words, sources, duration)
        if (plan.scenes.isEmpty()) throw IOException("لم يتم إنشاء أي مشهد.")

        report("جاري مزج المؤثرات الصوتية…", 0.35f)
        val paper = Sfx.paperFlip(sr)
        val wood = Sfx.woodClick(sr)
        val marker = Sfx.markerSqueak(sr)
        val pencil = Sfx.pencilScratch(sr)
        val drop = Sfx.subDrop(sr)
        val events = ArrayList<Pair<Double, ShortArray>>()
        for (s in plan.scenes) {
            events.add(Pair(s.start, paper))
            events.add(Pair(s.start + 0.3, wood))
            if (s.highlightPhrase.isNotBlank()) events.add(Pair(s.highlightAt - 0.07, marker))
            if (s.conclusion.isNotBlank()) {
                events.add(Pair(s.conclusionAt, pencil))
                events.add(Pair(s.conclusionAt + 0.9, drop))
            }
        }
        val mixed = AudioTools.mix(pcm.samples, sr, events, 0.28f)

        val width = 720
        val height = 1280
        val renderer = IpeRenderer(ctx, plan, sources.associateBy { it.id }, width, height)
        val out = File(ctx.cacheDir, "montage_${System.currentTimeMillis()}.mp4")
        report("جاري رسم الفيديو وترميزه…", 0.4f)
        VideoEncoder.encode(
            outFile = out,
            width = width,
            height = height,
            fps = 30,
            durationSec = duration + 0.5,
            pcm = mixed,
            sampleRate = sr,
            draw = { canvas, t -> renderer.draw(canvas, t) },
            onProgress = { p -> report("جاري رسم الفيديو وترميزه…", 0.4f + 0.55f * p) },
        )
        report("تم", 1f)
        out
    }
}
