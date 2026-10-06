package com.montageai.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    /** Short-time loudness of the narration, one value per [FRAME] seconds, roughly 0..1. */
    fun envelope(voice: ShortArray, sampleRate: Int, frame: Double = 0.02): FloatArray {
        val per = max(1, (sampleRate * frame).toInt())
        val n = voice.size / per + 1
        val out = FloatArray(n)
        for (f in 0 until n) {
            var sum = 0.0
            val s = f * per
            val e = min(voice.size, s + per)
            for (i in s until e) {
                val v = voice[i] / 32768.0
                sum += v * v
            }
            out[f] = if (e > s) sqrt(sum / (e - s)).toFloat() else 0f
        }
        // fast attack, slower release, so the effects come back smoothly after a word
        var level = 0f
        val release = 0.9f
        for (f in 0 until n) {
            level = if (out[f] > level) out[f] else level * release + out[f] * (1f - release)
            out[f] = level
        }
        return out
    }

    class Placed(val startSec: Double, val pcm: ShortArray, val gain: Float)

    /**
     * Mixes the effects under the narration. Effects are lowered while the narrator speaks (ducking)
     * so a whoosh never covers a word, and the result is limited so it cannot clip.
     */
    fun mixDucked(voice: ShortArray, sampleRate: Int, placed: List<Placed>, master: Float): ShortArray {
        val acc = FloatArray(voice.size)
        for (i in voice.indices) acc[i] = voice[i].toFloat()
        val frame = 0.02
        val env = envelope(voice, sampleRate, frame)
        val per = max(1, (sampleRate * frame).toInt())
        for (p in placed) {
            val start = (p.startSec * sampleRate).toInt()
            for (i in p.pcm.indices) {
                val idx = start + i
                if (idx < 0 || idx >= acc.size) continue
                val e = env[min(env.size - 1, idx / per)]
                val duck = 1f - 0.6f * min(1f, e / 0.2f)
                acc[idx] += p.pcm[i] * p.gain * master * duck
            }
        }
        val out = ShortArray(voice.size)
        for (i in acc.indices) out[i] = acc[i].toInt().coerceIn(-32768, 32767).toShort()
        return out
    }

    /** Fades the start and the end so the video does not begin or stop abruptly. */
    fun fadeEdges(samples: ShortArray, sampleRate: Int, inSec: Double, outSec: Double) {
        val a = (inSec * sampleRate).toInt()
        val b = (outSec * sampleRate).toInt()
        for (i in 0 until min(a, samples.size)) samples[i] = (samples[i] * (i / a.toFloat())).toInt().toShort()
        for (k in 0 until min(b, samples.size)) {
            val i = samples.size - 1 - k
            samples[i] = (samples[i] * (k / b.toFloat())).toInt().toShort()
        }
    }
}
