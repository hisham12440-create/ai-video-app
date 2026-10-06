package com.montageai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/**
 * Draws every frame of the montage: the active shot's image under its camera motion, the
 * transition into it from the shot before, a karaoke caption of the narration, and an optional
 * light color grade. Pure Canvas drawing so [VideoEncoder] can call it once per output frame.
 */
class Renderer(
    private val ctx: Context,
    private val project: Project,
    private val plan: EditPlan,
    private val outW: Int,
    private val outH: Int,
) {
    private val opts = project.options
    private val media = project.media
    private val pathById = media.associateBy({ it.id }, { it.path })

    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint().apply { color = Color.WHITE }
    private val gradePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Gentle contrast + a touch of warmth and saturation, applied to the whole frame.
        val m = ColorMatrix()
        val sat = ColorMatrix().apply { setSaturation(1.08f) }
        val contrast = 1.07f
        val translate = -0.5f * contrast * 255f + 128f
        val c = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate + 4f,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate - 4f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        m.postConcat(sat)
        m.postConcat(c)
        colorFilter = ColorMatrixColorFilter(m)
    }
    private val vignette: Shader = RadialGradient(
        outW / 2f, outH / 2f, max(outW, outH) * 0.72f,
        intArrayOf(0x00000000, 0x00000000, 0x3A000000),
        floatArrayOf(0f, 0.62f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val vignettePaint = Paint().apply { shader = vignette }

    private val captionFace = face("fonts/Tajawal-Bold.ttf", Typeface.DEFAULT_BOLD)
    private val captionSize = if (opts.aspect == AspectRatio.PORTRAIT) outW * 0.062f else outH * 0.072f
    private val captionPad = captionSize * 0.32f
    private val bgRectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xBF14130E.toInt() }
    private val highlightPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = opts.highlight.argb }

    // ---------------------------------------------------------------- image cache

    private class Loaded(val bitmap: Bitmap, val fax: Float, val fay: Float)

    private val imageCache = HashMap<String, Loaded?>()

    private fun loaded(path: String): Loaded? = imageCache.getOrPut(path) {
        try {
            val targetLong = (max(outW, outH) * 1.45f).toInt().coerceAtLeast(480)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@getOrPut null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetLong) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@getOrPut null
            val small = downsampleFor(bmp, 64)
            val f = Saliency.focus(small.pixels, small.w, small.h)
            Loaded(bmp, f[0] * 2f - 1f, f[1] * 2f - 1f)
        } catch (e: Exception) {
            null
        }
    }

    private class Small(val pixels: IntArray, val w: Int, val h: Int)

    private fun downsampleFor(src: Bitmap, longSide: Int): Small {
        val scale = longSide.toFloat() / max(src.width, src.height)
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        return Small(px, w, h)
    }

    // ---------------------------------------------------------------- shot lookup

    private fun shotAt(t: Double): Int {
        val shots = plan.shots
        if (shots.isEmpty()) return -1
        var lo = 0
        var hi = shots.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (shots[mid].start <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    // ---------------------------------------------------------------- drawing

    fun draw(c: Canvas, t: Double) {
        c.drawColor(Color.BLACK)
        if (plan.shots.isEmpty()) {
            drawCaption(c, t)
            return
        }
        val idx = shotAt(t)
        val shot = plan.shots[idx]
        val tr = shot.transition
        val inTransition = idx > 0 && tr.seconds > 0.0 && t < shot.start + tr.seconds

        if (!inTransition) {
            drawShot(c, shot, t, dx = 0f, scaleMul = 1f, alpha = 255)
        } else {
            val prev = plan.shots[idx - 1]
            val p = ((t - shot.start) / tr.seconds).coerceIn(0.0, 1.0).toFloat()
            drawTransition(c, prev, shot, t, tr, p)
        }

        if (opts.grade) {
            c.saveLayer(0f, 0f, outW.toFloat(), outH.toFloat(), gradePaint)
            c.restore()
            c.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), vignettePaint)
        }
        drawCaption(c, t)
    }

    private fun drawTransition(c: Canvas, prev: Shot, cur: Shot, t: Double, tr: Transition, p: Float) {
        val dir = if (cur.reverse) -1f else 1f
        when (tr) {
            Transition.FADE -> {
                drawShot(c, prev, t, 0f, 1f, 255)
                drawShot(c, cur, t, 0f, 1f, (255 * p).toInt())
            }
            Transition.SLIDE -> {
                drawShot(c, prev, t, dir * p * outW, 1f, 255)
                drawShot(c, cur, t, -dir * (1f - p) * outW, 1f, 255)
            }
            Transition.PUSH -> {
                drawShot(c, prev, t, -dir * p * outW, 1f, 255)
                drawShot(c, cur, t, dir * (1f - p) * outW, 1f, 255)
            }
            Transition.ZOOM -> {
                drawShot(c, prev, t, 0f, 1f + 0.22f * p, (255 * (1f - p)).toInt())
                drawShot(c, cur, t, 0f, 1.18f - 0.18f * p, 255)
            }
            Transition.WHIP -> {
                drawShot(c, prev, t, dir * p * outW * 1.3f, 1f, 255)
                drawShot(c, cur, t, -dir * (1f - p) * outW * 1.3f, 1f, 255)
            }
            Transition.FLASH -> {
                if (p < 0.5f) drawShot(c, prev, t, 0f, 1f, 255) else drawShot(c, cur, t, 0f, 1f, 255)
                val flash = 1f - kotlin.math.abs(p * 2f - 1f)
                if (flash > 0.02f) {
                    flashPaint.alpha = (flash * 255).toInt()
                    c.drawRect(0f, 0f, outW.toFloat(), outH.toFloat(), flashPaint)
                }
            }
            else -> drawShot(c, cur, t, 0f, 1f, 255)
        }
    }

    private fun drawShot(c: Canvas, shot: Shot, t: Double, dx: Float, scaleMul: Float, alpha: Int) {
        if (alpha <= 0) return
        val loaded = loaded(pathById[shot.mediaId] ?: "") ?: run {
            // Missing/unreadable image: a plain dark card so the timing still plays.
            val p = Paint().apply { color = 0xFF222220.toInt(); this.alpha = alpha }
            c.drawRect(dx, 0f, dx + outW, outH.toFloat(), p)
            return
        }
        val dur = max(0.05, shot.end - shot.start)
        val local = ((t - shot.start) / dur).coerceIn(0.0, 1.15).toFloat()
        val pose = Camera.pose(shot.motion, local.coerceIn(0f, 1f), shot.tight, loaded.fax, loaded.fay)

        val bmp = loaded.bitmap
        val cover = max(outW.toFloat() / bmp.width, outH.toFloat() / bmp.height)
        val total = cover * pose.scale * scaleMul
        val drawnW = bmp.width * total
        val drawnH = bmp.height * total
        val x0 = lerp(0f, outW - drawnW, (pose.ax + 1f) / 2f) + dx
        val y0 = lerp(0f, outH - drawnH, (pose.ay + 1f) / 2f)

        bgPaint.alpha = alpha
        c.drawBitmap(bmp, null, RectF(x0, y0, x0 + drawnW, y0 + drawnH), bgPaint)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // ---------------------------------------------------------------- captions

    private class CapLayout(val layout: StaticLayout, val ranges: List<IntRange>, val chunk: CaptionChunk)

    private val capCache = HashMap<CaptionChunk, CapLayout>()
    private val maxCapWidth = (outW * 0.9f).toInt()

    private fun capFor(chunk: CaptionChunk): CapLayout = capCache.getOrPut(chunk) {
        val sb = StringBuilder()
        val ranges = ArrayList<IntRange>()
        for (w in chunk.words) {
            val start = sb.length
            sb.append(w.text)
            ranges.add(start until sb.length)
            sb.append(' ')
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = captionFace
            textSize = captionSize
            color = Color.WHITE
        }
        val layout = StaticLayout.Builder.obtain(sb.toString(), 0, sb.length, paint, maxCapWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
        CapLayout(layout, ranges, chunk)
    }

    private fun drawCaption(c: Canvas, t: Double) {
        if (opts.captions == CaptionStyle.OFF) return
        val chunk = plan.captions.firstOrNull { t >= it.showFrom && t < it.showTo } ?: return
        val cap = capFor(chunk)
        val lay = cap.layout
        val x = (outW - maxCapWidth) / 2f
        val y = outH * 0.78f - lay.height / 2f

        c.save()
        c.translate(x, y)
        val bgR = RectF(-captionPad * 1.6f, -captionPad, lay.width + captionPad * 1.6f, lay.height + captionPad)
        c.drawRoundRect(bgR, captionPad, captionPad, bgRectPaint)

        if (opts.captions == CaptionStyle.KARAOKE) {
            for ((i, w) in chunk.words.withIndex()) {
                if (t < w.start) continue
                val r = cap.ranges[i]
                for (rect in glyphRects(lay, r.first, r.last + 1)) {
                    val pad = 3f
                    c.drawRoundRect(
                        RectF(rect.left - pad, rect.top + pad, rect.right + pad, rect.bottom - pad),
                        6f, 6f, highlightPillPaint,
                    )
                }
            }
        }
        lay.draw(c)
        c.restore()
    }

    private fun glyphRects(q: StaticLayout, s: Int, e: Int): List<RectF> {
        if (e <= s) return emptyList()
        val l0 = q.getLineForOffset(s)
        val l1 = q.getLineForOffset(max(s, e - 1))
        val out = ArrayList<RectF>()
        for (l in l0..l1) {
            val a = max(s, q.getLineStart(l))
            val b = min(e, q.getLineEnd(l))
            if (b <= a) continue
            val rtl = q.getParagraphDirection(l) == Layout.DIR_RIGHT_TO_LEFT
            val xa = if (a == q.getLineStart(l)) {
                if (rtl) q.getLineRight(l) else q.getLineLeft(l)
            } else q.getPrimaryHorizontal(a)
            val xb = if (b == q.getLineEnd(l)) {
                if (rtl) q.getLineLeft(l) else q.getLineRight(l)
            } else q.getPrimaryHorizontal(b)
            out.add(RectF(min(xa, xb), q.getLineTop(l).toFloat(), max(xa, xb), q.getLineBottom(l).toFloat()))
        }
        return out
    }

    private fun face(path: String, fallback: Typeface): Typeface =
        try {
            Typeface.createFromAsset(ctx.assets, path)
        } catch (e: Exception) {
            fallback
        }
}
