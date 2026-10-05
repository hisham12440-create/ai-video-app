package com.montageai.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** decode audio -> plan from the user's timings -> mix sound -> render + encode. No network, no keys. */
class Pipeline(private val ctx: Context) {

    suspend fun run(
        project: Project,
        report: (String, Float) -> Unit,
    ): File = withContext(Dispatchers.Default) {
        val sources = project.sources
        require(sources.isNotEmpty()) { "أضف مصدراً واحداً على الأقل." }
        val audioPath = project.audioPath
        if (audioPath == null || !File(audioPath).exists()) throw IOException("اختر التسجيل الصوتي أولاً.")
        val opts = project.options

        report("جاري تجهيز الصوت…", 0.1f)
        val pcm = AudioTools.decodeToMono(audioPath)
        AudioTools.normalize(pcm.samples)
        val sr = pcm.sampleRate
        val duration = pcm.samples.size / sr.toDouble()

        report("بناء الخطة من توقيتاتك…", 0.3f)
        val plan = Director.manualPlan(sources, duration)
        if (plan.scenes.isEmpty()) throw IOException("لم يتم إنشاء أي مشهد.")

        val mixed = if (opts.sfx) {
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
            AudioTools.mix(pcm.samples, sr, events, opts.sfxGain)
        } else {
            pcm.samples
        }

        val q = opts.quality
        val renderer = IpeRenderer(ctx, plan, sources.associateBy { it.id }, opts, q.width, q.height)
        val out = File(ctx.cacheDir, "montage_${System.currentTimeMillis()}.mp4")
        report("جاري رسم الفيديو وترميزه…", 0.4f)
        VideoEncoder.encode(
            outFile = out,
            width = q.width,
            height = q.height,
            fps = q.fps,
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
