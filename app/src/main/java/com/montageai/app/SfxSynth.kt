package com.montageai.app

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural sound effects for cuts and transitions: no audio files, no license to track.
 * Every sound is a short burst of noise and/or tone, shaped with an envelope, so it stays small
 * and renders instantly at any sample rate. [variant] (0, 1, 2…) nudges pitch/noise seed so the
 * same transition kind does not sound identical every time it repeats.
 */
object SfxSynth {

    fun render(kind: SfxKind, sampleRate: Int, variant: Int): ShortArray = when (kind) {
        SfxKind.SOFT_SWELL -> swell(sampleRate, variant)
        SfxKind.SWISH -> swish(sampleRate, variant)
        SfxKind.WHOOSH -> whoosh(sampleRate, variant, deep = false)
        SfxKind.DEEP_WHOOSH -> whoosh(sampleRate, variant, deep = true)
        SfxKind.WHIP -> whip(sampleRate, variant)
        SfxKind.FLASH -> flash(sampleRate, variant)
        SfxKind.THUD -> thud(sampleRate, variant)
        SfxKind.TICK -> tick(sampleRate, variant)
    }

    // ------------------------------------------------------------ building blocks

    private fun rng(variant: Int, salt: Int) = Random(variant * 7919 + salt)

    /** Band-limited noise: a random walk, which leans towards low frequencies more than pure white noise. */
    private fun noise(n: Int, rnd: Random, smooth: Int = 1): FloatArray {
        val raw = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        if (smooth <= 1) return raw
        val out = FloatArray(n)
        var acc = 0f
        for (i in 0 until n) {
            acc += (raw[i] - acc) / smooth
            out[i] = acc
        }
        return out
    }

    private fun env(i: Int, n: Int, attack: Float, release: Float): Float {
        val p = i.toFloat() / max(1, n - 1)
        return when {
            p < attack -> p / attack
            else -> exp(-((p - attack) / release) * 3f)
        }
    }

    private fun toShorts(f: FloatArray, gain: Float = 1f): ShortArray {
        val out = ShortArray(f.size)
        for (i in f.indices) out[i] = (f[i] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        return out
    }

    private fun sweepTone(n: Int, sr: Int, f0: Double, f1: Double): FloatArray {
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val f = f0 + (f1 - f0) * t
            phase += 2.0 * PI * f / sr
            out[i] = sin(phase).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------ effects

    /** A gentle rise-and-fall air tone, for fades. */
    private fun swell(sr: Int, variant: Int): ShortArray {
        val dur = 0.45
        val n = (dur * sr).toInt()
        val rnd = rng(variant, 1)
        val base = noise(n, rnd, smooth = 18)
        val tone = sweepTone(n, sr, 520.0 + variant * 30, 340.0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val e = env(i, n, attack = 0.35f, release = 0.5f)
            out[i] = (base[i] * 0.35f + tone[i] * 0.25f) * e
        }
        return toShorts(out, 0.6f)
    }

    /** A quick airy sweep, for slides. */
    private fun swish(sr: Int, variant: Int): ShortArray {
        val dur = 0.28
        val n = (dur * sr).toInt()
        val rnd = rng(variant, 2)
        val hp = noise(n, rnd, smooth = 2)
        val lp = noise(n, rnd, smooth = 14)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val e = env(i, n, attack = 0.1f, release = 0.4f)
            out[i] = (hp[i] * 0.5f + lp[i] * 0.25f) * e
        }
        return toShorts(out, 0.7f)
    }

    /** A broad air-movement burst, for pushes/zooms; [deep] lowers it for the stronger transition. */
    private fun whoosh(sr: Int, variant: Int, deep: Boolean): ShortArray {
        val dur = if (deep) 0.42 else 0.34
        val n = (dur * sr).toInt()
        val rnd = rng(variant, if (deep) 4 else 3)
        val body = noise(n, rnd, smooth = if (deep) 26 else 16)
        val tone = sweepTone(n, sr, if (deep) 180.0 else 300.0, if (deep) 70.0 else 150.0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val e = env(i, n, attack = 0.22f, release = 0.42f)
            out[i] = (body[i] * 0.55f + tone[i] * (if (deep) 0.3f else 0.18f)) * e
        }
        return toShorts(out, if (deep) 0.85f else 0.75f)
    }

    /** A sharp crack with a fast pitch drop, for whip transitions. */
    private fun whip(sr: Int, variant: Int): ShortArray {
        val dur = 0.22
        val n = (dur * sr).toInt()
        val rnd = rng(variant, 5)
        val hiss = noise(n, rnd, smooth = 2)
        val tone = sweepTone(n, sr, 1400.0, 90.0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val e = env(i, n, attack = 0.04f, release = 0.22f)
            out[i] = (tone[i] * 0.55f + hiss[i] * 0.35f) * e
        }
        return toShorts(out, 0.8f)
    }

    /** A bright, very short click-tick, for a flash transition. */
    private fun flash(sr: Int, variant: Int): ShortArray {
        val dur = 0.14
        val n = (dur * sr).toInt()
        val rnd = rng(variant, 6)
        val hiss = noise(n, rnd, smooth = 1)
        val tone = sweepTone(n, sr, 2600.0, 1800.0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val e = env(i, n, attack = 0.02f, release = 0.16f)
            out[i] = (hiss[i] * 0.45f + tone[i] * 0.4f) * e
        }
        return toShorts(out, 0.65f)
    }

    /** A low, soft impact, for a punch-in jump cut. */
    private fun thud(sr: Int, variant: Int): ShortArray {
        val dur = 0.18
        val n = (dur * sr).toInt()
        val rnd = rng(variant, 7)
        val body = noise(n, rnd, smooth = 20)
        val tone = sweepTone(n, sr, 110.0, 55.0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val e = env(i, n, attack = 0.03f, release = 0.3f)
            out[i] = (tone[i] * 0.6f + body[i] * 0.25f) * e
        }
        return toShorts(out, 0.75f)
    }

    /** A tiny neutral tick, for a plain cut. */
    private fun tick(sr: Int, variant: Int): ShortArray {
        val dur = 0.05
        val n = max(1, (dur * sr).toInt())
        val rnd = rng(variant, 8)
        val hiss = noise(n, rnd, smooth = 1)
        val tone = sweepTone(n, sr, 1200.0, 900.0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            val e = env(i, n, attack = 0.05f, release = 0.45f)
            out[i] = (tone[i] * 0.4f + hiss[i] * 0.25f) * e
        }
        return toShorts(out, 0.45f)
    }
}
