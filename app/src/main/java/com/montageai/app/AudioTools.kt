package com.montageai.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class Pcm(val sampleRate: Int, val samples: ShortArray)

object AudioTools {

    private class ShortAccumulator {
        private var data = ShortArray(1 shl 20)
        private var size = 0
        fun add(v: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): ShortArray = data.copyOf(size)
    }

    /** Decodes any audio file the device supports to mono 16-bit PCM. */
    fun decodeToMono(path: String): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        require(trackIndex >= 0 && format != null) { "الملف لا يحتوي على مسار صوتي." }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val acc = ShortAccumulator()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val buf = codec.getOutputBuffer(outIdx)!!
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                val frames = sb.remaining() / channels
                for (i in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += sb.get().toInt()
                    acc.add((sum / channels).toShort())
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val nf = codec.outputFormat
                sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        codec.stop()
        codec.release()
        extractor.release()
        return Pcm(sampleRate, acc.toArray())
    }

    /** Peak-normalizes the narration so that the sound effects sit at a stable level under it. */
    fun normalize(samples: ShortArray, target: Float = 0.9f) {
        var peak = 1
        for (v in samples) {
            val a = abs(v.toInt())
            if (a > peak) peak = a
        }
        val gain = target * 32767f / peak
        for (i in samples.indices) {
            samples[i] = (samples[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun mix(voice: ShortArray, sampleRate: Int, events: List<Pair<Double, ShortArray>>, sfxGain: Float): ShortArray {
        val out = voice.copyOf()
        for ((time, sfx) in events) {
            val start = (time * sampleRate).toInt()
            for (i in sfx.indices) {
                val idx = start + i
                if (idx < 0 || idx >= out.size) continue
                val v = out[idx] + (sfx[i] * sfxGain).toInt()
                out[idx] = v.coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }
}

/**
 * Synthesized Foley-style effects (paper, marker, pencil, wood, sub drop).
 * They are generated procedurally so the app needs no audio assets; swap in real
 * recordings later if you want a richer sound.
 */
object Sfx {
    private fun buffer(sr: Int, seconds: Double) = ShortArray((sr * seconds).toInt())

    private fun put(out: ShortArray, i: Int, v: Float) {
        out[i] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
    }

    fun paperFlip(sr: Int): ShortArray {
        val out = buffer(sr, 0.35)
        val rnd = Random(11)
        var prev = 0f
        for (i in out.indices) {
            val t = i / sr.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            val hp = noise - prev * 0.85f
            prev = noise
            val env = minOf(1f, t / 0.02f) * exp(-t * 11f)
            put(out, i, hp * env * 0.5f)
        }
        return out
    }

    fun markerSqueak(sr: Int): ShortArray {
        val out = buffer(sr, 0.5)
        val rnd = Random(21)
        for (i in out.indices) {
            val t = i / sr.toFloat()
            val freq = 1900f + 500f * sin(2.0 * PI * 3.0 * t).toFloat()
            val tone = sin(2.0 * PI * freq * t).toFloat() * 0.10f
            val noise = (rnd.nextFloat() * 2f - 1f) * 0.12f
            val env = minOf(1f, t / 0.03f) * minOf(1f, (0.5f - t) / 0.08f)
            put(out, i, (tone + noise) * env)
        }
        return out
    }

    fun pencilScratch(sr: Int): ShortArray {
        val out = buffer(sr, 0.7)
        val rnd = Random(31)
        var prev = 0f
        for (i in out.indices) {
            val t = i / sr.toFloat()
            val noise = rnd.nextFloat() * 2f - 1f
            val hp = noise - prev * 0.9f
            prev = noise
            val mod = 0.55f + 0.45f * sin(2.0 * PI * 22.0 * t).toFloat()
            val env = minOf(1f, t / 0.04f) * minOf(1f, (0.7f - t) / 0.1f)
            put(out, i, hp * mod * env * 0.30f)
        }
        return out
    }

    fun woodClick(sr: Int): ShortArray {
        val out = buffer(sr, 0.15)
        val rnd = Random(41)
        for (i in out.indices) {
            val t = i / sr.toFloat()
            val tone = sin(2.0 * PI * 620.0 * t).toFloat() * exp(-t * 55f)
            val tick = (rnd.nextFloat() * 2f - 1f) * exp(-t * 180f)
            put(out, i, (tone * 0.5f + tick * 0.4f))
        }
        return out
    }

    fun subDrop(sr: Int): ShortArray {
        val out = buffer(sr, 1.0)
        var phase = 0.0
        for (i in out.indices) {
            val t = i / sr.toFloat()
            val freq = 95.0 - 50.0 * minOf(1.0, t.toDouble() / 0.6)
            phase += 2.0 * PI * freq / sr
            val env = minOf(1f, t / 0.01f) * exp(-t * 3.2f)
            put(out, i, sin(phase).toFloat() * env * 0.6f)
        }
        return out
    }
}
