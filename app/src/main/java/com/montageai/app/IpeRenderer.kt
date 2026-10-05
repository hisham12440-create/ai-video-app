package com.montageai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the "Interactive Pyramid Editing" (IPE) look frame by frame:
 * parchment background, a source-documentation card with a torn-paper quote,
 * a yellow highlighter swept in sync with the narrator, and a handwritten
 * conclusion with a hand-drawn underline.
 *
 * Fonts: drop Tajawal-Bold.ttf, Amiri-Regular.ttf and Kufam-Regular.ttf into
 * app/src/main/assets/fonts/ and they are picked up automatically; otherwise
 * Android's built-in Arabic fonts are used.
 */
class IpeRenderer(
    private val ctx: Context,
    plan: Plan,
    sources: Map<String, SourceCard>,
    private val opts: ExportOptions,
    private val outW: Int,
    private val outH: Int,
) {
    // The scene is laid out on a fixed 720x1280 canvas and scaled to the output size.
    private val w = 720
    private val h = 1280
    private val scale = outW / 720f
    private val bgPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val theme = opts.paper
    private val parchment = theme.bg
    private val ink = theme.ink
    private val yellow = opts.highlight.argb

    private val titleFace = face("fonts/Tajawal-Bold.ttf", Typeface.DEFAULT_BOLD)
    private val bodyFace = face("fonts/Amiri-Regular.ttf", Typeface.SERIF)
    private val noteFace = face("fonts/Kufam-Regular.ttf", Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC))

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = ink
    }
    private val paperFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = theme.card
    }
    private val coverFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = theme.cover
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = yellow
        alpha = 205
    }
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        color = ink
    }

    private val background: Bitmap = makeBackground()

    private class Visual(
        val scene: Scene,
        val cover: Bitmap?,
        val coverTitle: StaticLayout?,
        val meta: StaticLayout,
        val header: StaticLayout?,
        val quote: StaticLayout,
        val trans: StaticLayout?,
        val note: StaticLayout?,
        val noteRtl: Boolean,
        val paperPath: Path,
        val paperRect: RectF,
        val quoteX: Float,
        val quoteY: Float,
        val headerY: Float,
        val transY: Float,
        val noteY: Float,
        val hiRects: List<RectF>,
        val hiRtl: Boolean,
        val underline: Path?,
    )

    private val visuals: List<Visual> =
        plan.scenes.sortedBy { it.start }.mapNotNull { s -> sources[s.sourceId]?.let { build(s, it) } }

    // ---------------------------------------------------------------- drawing

    fun draw(c: Canvas, t: Double) {
        c.save()
        c.scale(scale, scale)
        c.drawBitmap(background, 0f, 0f, bgPaint)
        val v = activeVisual(t)
        if (v != null) drawScene(c, v, t)
        c.restore()
    }

    private fun drawScene(c: Canvas, v: Visual, t: Double) {
        val local = (t - v.scene.start).toFloat()
        val dur = max(0.1f, (v.scene.end - v.scene.start).toFloat())
        val hi = (v.scene.highlightAt - v.scene.start).toFloat()
        val ci = (v.scene.conclusionAt - v.scene.start).toFloat()

        val zoom = if (opts.zoom) {
            1f + 0.04f * (local / dur).coerceIn(0f, 1f) + 0.12f * focusAmount(local, hi)
        } else 1f
        val hasHi = v.hiRects.isNotEmpty()
        val px = if (hasHi) v.quoteX + v.hiRects[0].centerX() else w / 2f
        val py = if (hasHi) v.quoteY + v.hiRects[0].centerY() else h / 2f

        c.save()
        c.scale(zoom, zoom, px, py)

        block(c, local, 0f, RectF(40f, 90f, w - 40f, 360f)) { drawCover(c, v) }
        block(c, local, 0.25f, v.paperRect) { drawPaper(c, v, local, hi) }
        v.trans?.let { tr ->
            val r = RectF(40f, v.transY, w - 40f, v.transY + tr.height)
            block(c, local, 0.9f, r) {
                c.save()
                c.translate(40f, v.transY)
                tr.draw(c)
                c.restore()
            }
        }
        drawNote(c, v, local, ci)
        c.restore()
    }

    private fun activeVisual(t: Double): Visual? {
        var found: Visual? = null
        for (v in visuals) {
            if (v.scene.start <= t) found = v else break
        }
        return found
    }

    private fun block(c: Canvas, local: Float, delay: Float, bounds: RectF, body: () -> Unit) {
        val p = ((local - delay) / 0.55f).coerceIn(0f, 1f)
        if (p <= 0f) return
        val e = easeOutCubic(p)
        c.save()
        c.translate(0f, (1f - e) * 50f)
        val layer = c.saveLayerAlpha(
            bounds.left - 40f, bounds.top - 60f, bounds.right + 40f, bounds.bottom + 60f,
            (255 * e).toInt(),
        )
        body()
        c.restoreToCount(layer)
        c.restore()
    }

    private fun drawCover(c: Canvas, v: Visual) {
        c.save()
        c.translate(40f, 90f)
        val cover = v.cover
        if (cover != null) {
            c.drawBitmap(cover, 0f, 0f, null)
            c.drawRect(0f, 0f, 190f, 270f, strokePaint)
        } else {
            c.drawRect(0f, 0f, 190f, 270f, coverFill)
            c.drawRect(0f, 0f, 190f, 270f, strokePaint)
            c.save()
            c.translate(12f, 12f)
            v.coverTitle?.draw(c)
            c.restore()
        }
        c.restore()

        c.save()
        c.translate(254f, 90f)
        v.meta.draw(c)
        c.restore()
    }

    private fun drawPaper(c: Canvas, v: Visual, local: Float, hi: Float) {
        c.save()
        c.translate(v.paperRect.left, v.paperRect.top)
        paperFill.setShadowLayer(22f, 0f, 10f, 0x55000000)
        c.drawPath(v.paperPath, paperFill)
        paperFill.clearShadowLayer()
        c.drawPath(v.paperPath, strokePaint)
        c.restore()

        v.header?.let { hd ->
            c.save()
            c.translate(v.quoteX, v.headerY)
            hd.draw(c)
            c.restore()
        }

        c.save()
        c.translate(v.quoteX, v.quoteY)
        val p = easeInOut(((local - hi) / 0.5f).coerceIn(0f, 1f))
        if (p > 0f && v.hiRects.isNotEmpty()) {
            var consumed = p * v.hiRects.sumOf { it.width().toDouble() }.toFloat()
            for (r in v.hiRects) {
                val rw = r.width()
                if (rw <= 0f) continue
                val portion = (consumed / rw).coerceIn(0f, 1f)
                consumed -= rw
                if (portion <= 0f) continue
                if (v.hiRtl) {
                    c.drawRect(r.right - rw * portion, r.top, r.right, r.bottom, highlightPaint)
                } else {
                    c.drawRect(r.left, r.top, r.left + rw * portion, r.bottom, highlightPaint)
                }
            }
        }
        v.quote.draw(c)
        c.restore()
    }

    private fun drawNote(c: Canvas, v: Visual, local: Float, ci: Float) {
        val note = v.note ?: return
        if (local < ci) return
        val pw = w - 80f
        val rp = easeInOut(((local - ci) / 0.9f).coerceIn(0f, 1f))
        c.save()
        c.translate(40f, v.noteY)
        if (v.noteRtl) {
            c.clipRect(pw * (1f - rp), -20f, pw, note.height + 30f)
        } else {
            c.clipRect(0f, -20f, pw * rp, note.height + 30f)
        }
        note.draw(c)
        c.restore()

        val up = ((local - ci - 0.5f) / 0.6f).coerceIn(0f, 1f)
        val ul = v.underline
        if (ul != null && up > 0f) {
            val pm = PathMeasure(ul, false)
            val seg = Path()
            pm.getSegment(0f, pm.length * easeInOut(up), seg, true)
            c.drawPath(seg, underlinePaint)
        }
    }

    // ---------------------------------------------------------------- building

    private fun build(s: Scene, src: SourceCard): Visual {
        val margin = 40f
        val coverW = 190
        val coverH = 270
        val top = 90f
        val metaW = (w - 2 * margin - coverW - 24f).toInt()

        val metaText = buildString {
            if (src.author.isNotBlank()) append("المؤلف: ").append(src.author).append('\n')
            if (src.title.isNotBlank()) append("الكتاب: ").append(src.title).append('\n')
            if (src.publisher.isNotBlank()) append("الناشر والطبعة: ").append(src.publisher).append('\n')
        }.trim()
        val meta = fit(metaText.ifBlank { " " }, titleFace, ink, metaW, coverH, 28f, 16f)
        val cover = loadCover(src.imagePath, coverW, coverH)
        val coverTitle = if (cover == null) {
            fit(src.title.ifBlank { " " }, titleFace, ink, coverW - 24, coverH - 24, 26f, 14f)
        } else null

        val paperW = w - 2 * margin
        val pad = 34f
        val innerW = (paperW - 2 * pad).toInt()
        val header = if (src.location.isNotBlank()) {
            layout(src.location, tp(titleFace, 22f, 0xB01A1A1A.toInt()), innerW)
        } else null
        val headerH = header?.height?.toFloat() ?: 0f
        val paperTop = 400f
        val maxQuoteH = (860f - paperTop - 2 * pad - headerH - 10f).toInt().coerceAtLeast(120)
        val quote = fit(src.quote.ifBlank { " " }, bodyFace, ink, innerW, maxQuoteH, 36f, 20f)
        val paperH = 2 * pad + headerH + 10f + quote.height
        val paperRect = RectF(margin, paperTop, margin + paperW, paperTop + paperH)
        val headerY = paperTop + pad
        val quoteX = margin + pad
        val quoteY = paperTop + pad + headerH + 10f
        val paperPath = tornPath(paperW, paperH, src.id.hashCode().toLong())

        val transY = paperRect.bottom + 36f
        val trans = if (src.translation.isNotBlank()) {
            fit(src.translation, bodyFace, 0xFF333333.toInt(), paperW.toInt(), max(80, (1070f - transY).toInt()), 28f, 16f)
        } else null
        val afterTrans = if (trans != null) transY + trans.height else paperRect.bottom
        val noteY = afterTrans + 40f
        val note = if (s.conclusion.isNotBlank()) {
            fit(s.conclusion, noteFace, ink, paperW.toInt(), max(60, (h - 90f - noteY).toInt()), 40f, 22f)
        } else null
        val noteRtl = note?.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT

        var underline: Path? = null
        if (note != null) {
            var lw = 0f
            for (i in 0 until note.lineCount) lw = max(lw, note.getLineWidth(i))
            val y = noteY + note.height + 12f
            val xs = if (noteRtl) margin + paperW else margin
            val xe = if (noteRtl) xs - lw else xs + lw
            underline = Path().apply {
                moveTo(xs, y)
                quadTo((xs + xe) / 2f, y + 9f, xe, y - 3f)
            }
        }

        val hiRects = highlightRects(quote, src.quote, s.highlightPhrase)
        val hiRtl = quote.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT

        return Visual(
            scene = s, cover = cover, coverTitle = coverTitle, meta = meta, header = header,
            quote = quote, trans = trans, note = note, noteRtl = noteRtl,
            paperPath = paperPath, paperRect = paperRect, quoteX = quoteX, quoteY = quoteY,
            headerY = headerY, transY = transY, noteY = noteY, hiRects = hiRects, hiRtl = hiRtl,
            underline = underline,
        )
    }

    private fun highlightRects(q: StaticLayout, text: String, phrase: String): List<RectF> {
        if (phrase.isBlank()) return emptyList()
        val s = text.indexOf(phrase)
        if (s < 0) return emptyList()
        val e = s + phrase.length
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

    private fun tornPath(pw: Float, ph: Float, seed: Long): Path {
        val rnd = Random(seed)
        val p = Path()
        p.moveTo(0f, 0f)
        var x = 0f
        while (x < pw) {
            x = min(pw, x + 14f + rnd.nextFloat() * 10f)
            p.lineTo(x, rnd.nextFloat() * 7f)
        }
        var y = 0f
        while (y < ph) {
            y = min(ph, y + 40f)
            p.lineTo(pw - rnd.nextFloat() * 3f, y)
        }
        x = pw
        while (x > 0f) {
            x = max(0f, x - (14f + rnd.nextFloat() * 10f))
            p.lineTo(x, ph - rnd.nextFloat() * 9f)
        }
        p.lineTo(rnd.nextFloat() * 3f, ph * 0.5f)
        p.close()
        return p
    }

    private fun makeBackground(): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(parchment)
        val rnd = Random(7)
        val p = Paint().apply { strokeWidth = 1f }
        repeat(1400) {
            p.color = Color.argb(10 + rnd.nextInt(14), 120, 100, 70)
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            c.drawLine(x, y, x + rnd.nextFloat() * 14f - 7f, y + rnd.nextFloat() * 14f - 7f, p)
        }
        val shader = RadialGradient(
            w / 2f, h / 2f, max(w, h) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, 0x33856B3A),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        val vp = Paint().apply { this.shader = shader }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vp)
        return b
    }

    private fun loadCover(path: String?, cw: Int, ch: Int): Bitmap? {
        if (path == null) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= cw * 2 && bounds.outHeight / (sample * 2) >= ch * 2) sample *= 2
            val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = max(cw.toFloat() / src.width, ch.toFloat() / src.height)
            val sw = (cw / scale).toInt().coerceAtMost(src.width)
            val sh = (ch / scale).toInt().coerceAtMost(src.height)
            val sx = (src.width - sw) / 2
            val sy = (src.height - sh) / 2
            val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(
                src, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, cw, ch), Paint(Paint.FILTER_BITMAP_FLAG),
            )
            out
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- text helpers

    private fun face(path: String, fallback: Typeface): Typeface =
        try {
            Typeface.createFromAsset(ctx.assets, path)
        } catch (e: Exception) {
            fallback
        }

    private fun tp(face: Typeface, size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        this.color = color
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setLineSpacing(0f, 1.3f)
            .setIncludePad(false)
            .build()

    private fun fit(
        text: String, face: Typeface, color: Int, width: Int, maxH: Int, start: Float, minSize: Float,
    ): StaticLayout {
        var size = start
        var l = layout(text, tp(face, size, color), width)
        while (l.height > maxH && size > minSize) {
            size -= 2f
            l = layout(text, tp(face, size, color), width)
        }
        return l
    }

    // ---------------------------------------------------------------- easing

    private fun easeOutCubic(p: Float): Float {
        val q = 1f - p
        return 1f - q * q * q
    }

    private fun easeInOut(p: Float): Float = p * p * (3f - 2f * p)

    private fun focusAmount(local: Float, hi: Float): Float {
        val a = (local - (hi - 0.5f)) / 0.5f
        val b = ((hi + 1.4f) - local) / 0.6f
        return easeInOut(min(a, b).coerceIn(0f, 1f))
    }
}
