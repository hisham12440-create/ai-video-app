package com.montageai.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * decode audio -> plan the edit (beats, cuts, motion, transitions, captions, SFX) -> synth + mix
 * the sound effects -> render + encode. No network, no keys, no AI model required (the transcript
 * is optional and only sharpens the timing).
 */
class Pipeline(private val ctx: Context) {

    suspend fun run(
        project: Project,
        transcript: List<Word>?,
        report: (String, Float) -> Unit,
    ): File = withContext(Dispatchers.Default) {
        require(project.media.isNotEmpty()) { "أضف صورة واحدة على الأقل." }
        val audioPath = project.audioPath
        if (audioPath == null || !File(audioPath).exists()) throw IOException("اختر التسجيل الصوتي أولاً.")
        val opts = project.options

        report("جاري تجهيز الصوت…", 0.08f)
        val pcm = AudioTools.decodeToMono(audioPath)
        AudioTools.normalize(pcm.samples)
        val sr = pcm.sampleRate
        val duration = pcm.samples.size / sr.toDouble()

        report("جاري بناء المخطط (القطع، الحركة، الانتقالات)…", 0.2f)
        val plan = Planner.plan(project, transcript, duration)
        if (plan.shots.isEmpty()) throw IOException("لم يتم إنشاء أي مشهد. تأكد من وجود صور وصوت صالحين.")

        val mixed = if (opts.sfx && plan.sfx.isNotEmpty()) {
            report("جاري توليد ومزج المؤثرات الصوتية…", 0.32f)
            val placed = plan.sfx.map { e ->
                AudioTools.Placed(e.time, SfxSynth.render(e.kind, sr, e.variant), e.gain)
            }
            AudioTools.mixDucked(pcm.samples, sr, placed, opts.sfxGain)
        } else {
            pcm.samples
        }
        AudioTools.fadeEdges(mixed, sr, 0.12, 0.35)

        val aspect = opts.aspect
        val renderer = Renderer(ctx, project, plan, aspect.width, aspect.height)
        val out = File(ctx.cacheDir, "montage_${System.currentTimeMillis()}.mp4")
        report("جاري رسم الفيديو وترميزه…", 0.4f)
        VideoEncoder.encode(
            outFile = out,
            width = aspect.width,
            height = aspect.height,
            fps = opts.fps,
            durationSec = plan.duration + 0.4,
            pcm = mixed,
            sampleRate = sr,
            draw = { canvas, t -> renderer.draw(canvas, t) },
            onProgress = { p -> report("جاري رسم الفيديو وترميزه…", 0.4f + 0.55f * p) },
        )
        report("تم", 1f)
        out
    }
}
