package com.montageai.app

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where the virtual camera looks at a still image.
 * [scale] is the extra zoom on top of "cover the whole frame"; [ax] and [ay] say where the view sits inside the
 * room the zoom leaves: -1 is the left/top edge of the image, 0 the middle, +1 the right/bottom edge.
 */
class Pose(val scale: Float, val ax: Float, val ay: Float)

object Camera {
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Slow in, slow out. */
    fun ease(p: Float): Float {
        val x = p.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * @param p progress through the shot, 0..1
     * @param fax / [fay] where the interesting part of the image is, in the same -1..1 units as [Pose]
     */
    fun pose(m: Motion, p: Float, tight: Boolean, fax: Float, fay: Float): Pose {
        val base = if (tight) 1.24f else 1.0f
        val e = ease(p)
        val bx = fax * 0.55f
        val by = fay * 0.55f
        return when (m) {
            Motion.ZOOM_IN -> Pose(base * lerp(1.04f, 1.20f, e), lerp(0f, bx, e), lerp(0f, by, e))
            Motion.ZOOM_OUT -> Pose(base * lerp(1.20f, 1.04f, e), lerp(bx, 0f, e), lerp(by, 0f, e))
            Motion.PAN_LEFT -> Pose(base * 1.14f, lerp(0.75f, -0.75f, e), by)
            Motion.PAN_RIGHT -> Pose(base * 1.14f, lerp(-0.75f, 0.75f, e), by)
            Motion.PAN_UP -> Pose(base * 1.14f, bx, lerp(0.75f, -0.75f, e))
            Motion.PAN_DOWN -> Pose(base * 1.14f, bx, lerp(-0.75f, 0.75f, e))
            Motion.STILL -> Pose(base * lerp(1.0f, 1.03f, p.coerceIn(0f, 1f)), bx * 0.5f, by * 0.5f)
        }
    }
}

/**
 * A light "where would a person look" estimate for choosing the camera target.
 * Edge energy and local contrast on a tiny copy of the image, weighted towards the middle of the picture.
 * It is a hand-written heuristic (no model, no extra download): fast enough to run on every image.
 */
object Saliency {
    /** Returns (x, y) in 0..1, the point of the image the camera should lean towards. */
    fun focus(px: IntArray, w: Int, h: Int): FloatArray {
        if (w < 4 || h < 4 || px.size < w * h) return floatArrayOf(0.5f, 0.5f)
        val lum = FloatArray(w * h)
        var mean = 0f
        for (i in 0 until w * h) {
            val c = px[i]
            val l = (0.2126f * ((c shr 16) and 255) + 0.7152f * ((c shr 8) and 255) + 0.0722f * (c and 255)) / 255f
            lum[i] = l
            mean += l
        }
        mean /= (w * h)
        val gx = 6
        val gy = 6
        val energy = FloatArray(gx * gy)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val dx = lum[y * w + x + 1] - lum[y * w + x - 1]
                val dy = lum[(y + 1) * w + x] - lum[(y - 1) * w + x]
                val g = sqrt(dx * dx + dy * dy) + 0.6f * abs(lum[y * w + x] - mean)
                val cx = min(gx - 1, x * gx / w)
                val cy = min(gy - 1, y * gy / h)
                energy[cy * gx + cx] += g
            }
        }
        var sum = 0f
        var sx = 0f
        var sy = 0f
        var peak = 0f
        for (i in energy.indices) peak = max(peak, energy[i])
        if (peak <= 1e-4f) return floatArrayOf(0.5f, 0.5f)
        for (cy in 0 until gy) {
            for (cx in 0 until gx) {
                val fx = (cx + 0.5f) / gx
                val fy = (cy + 0.5f) / gy
                val dxc = fx - 0.5f
                val dyc = fy - 0.45f
                val prior = exp(-(dxc * dxc + dyc * dyc) / (2f * 0.35f * 0.35f))
                val e = energy[cy * gx + cx] / peak
                val wgt = e * e * prior
                sum += wgt
                sx += wgt * fx
                sy += wgt * fy
            }
        }
        if (sum <= 1e-6f) return floatArrayOf(0.5f, 0.5f)
        return floatArrayOf((sx / sum).coerceIn(0.15f, 0.85f), (sy / sum).coerceIn(0.15f, 0.85f))
    }
}
