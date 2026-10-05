package com.montageai.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offline renderer: draws every frame with a Canvas, encodes H.264 + AAC and muxes to MP4.
 * Frames are pushed as raw YUV so every frame gets an exact timestamp.
 */
object VideoEncoder {

    private class EncodedSample(val data: ByteArray, val ptsUs: Long)

    fun encode(
        outFile: File,
        width: Int,
        height: Int,
        fps: Int,
        durationSec: Double,
        pcm: ShortArray,
        sampleRate: Int,
        draw: (Canvas, Double) -> Unit,
        onProgress: (Float) -> Unit,
    ) {
        val (audioFormat, audioSamples) = encodeAudio(pcm, sampleRate)

        val vfmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        venc.configure(vfmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        venc.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var vTrack = -1
        var aTrack = -1
        var muxStarted = false
        var audioIdx = 0
        val info = MediaCodec.BufferInfo()

        fun writeAudioUpTo(limitUs: Long) {
            if (!muxStarted) return
            val ai = MediaCodec.BufferInfo()
            while (audioIdx < audioSamples.size && audioSamples[audioIdx].ptsUs <= limitUs) {
                val s = audioSamples[audioIdx]
                ai.set(0, s.data.size, s.ptsUs, 0)
                muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), ai)
                audioIdx++
            }
        }

        fun drain(untilEos: Boolean) {
            while (true) {
                val o = venc.dequeueOutputBuffer(info, if (untilEos) 10_000L else 0L)
                if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEos) return
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vTrack = muxer.addTrack(venc.outputFormat)
                    aTrack = muxer.addTrack(audioFormat)
                    muxer.start()
                    muxStarted = true
                } else if (o >= 0) {
                    val buf = venc.getOutputBuffer(o)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                    if (info.size > 0 && muxStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(vTrack, buf, info)
                        writeAudioUpTo(info.presentationTimeUs)
                    }
                    venc.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val pixels = IntArray(width * height)
        val yRow = ByteArray(width)
        val totalFrames = (durationSec * fps).toInt()

        try {
            for (i in 0 until totalFrames) {
                draw(canvas, i.toDouble() / fps)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                var idx = venc.dequeueInputBuffer(10_000)
                while (idx < 0) {
                    drain(false)
                    idx = venc.dequeueInputBuffer(10_000)
                }
                val image = venc.getInputImage(idx) ?: throw IOException("المشفّر لا يدعم الإدخال بالصور.")
                fillImage(image, pixels, width, height, yRow)
                venc.queueInputBuffer(idx, 0, width * height * 3 / 2, i * 1_000_000L / fps, 0)
                drain(false)
                if (i % 5 == 0) onProgress(i.toFloat() / totalFrames)
            }

            var idx = venc.dequeueInputBuffer(10_000)
            while (idx < 0) {
                drain(false)
                idx = venc.dequeueInputBuffer(10_000)
            }
            venc.queueInputBuffer(idx, 0, 0, totalFrames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            writeAudioUpTo(Long.MAX_VALUE)
            if (muxStarted) muxer.stop()
        } finally {
            try { venc.stop() } catch (e: Exception) { }
            venc.release()
            try { muxer.release() } catch (e: Exception) { }
            bitmap.recycle()
        }
    }

    private fun encodeAudio(pcm: ShortArray, sampleRate: Int): Pair<MediaFormat, List<EncodedSample>> {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val out = ArrayList<EncodedSample>()
        var outFormat: MediaFormat? = null
        var pos = 0
        var inputDone = false
        var done = false
        val info = MediaCodec.BufferInfo()

        while (!done) {
            if (!inputDone) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val buf = codec.getInputBuffer(idx)!!
                    buf.clear()
                    val n = minOf(buf.capacity() / 2, pcm.size - pos)
                    if (n <= 0) {
                        codec.queueInputBuffer(
                            idx, 0, 0, pos * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputDone = true
                    } else {
                        buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, pos, n)
                        codec.queueInputBuffer(idx, 0, n * 2, pos * 1_000_000L / sampleRate, 0)
                        pos += n
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                outFormat = codec.outputFormat
            } else if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val arr = ByteArray(info.size)
                    b.position(info.offset)
                    b.limit(info.offset + info.size)
                    b.get(arr)
                    out.add(EncodedSample(arr, info.presentationTimeUs))
                }
                codec.releaseOutputBuffer(o, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
            }
        }
        codec.stop()
        codec.release()
        val format = outFormat ?: throw IOException("فشل ترميز الصوت.")
        return Pair(format, out)
    }

    /** ARGB pixels -> YUV420 planes of the codec's input image (BT.601, limited range). */
    private fun fillImage(image: Image, px: IntArray, w: Int, h: Int, yRow: ByteArray) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val yStride = planes[0].rowStride
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val c = px[base + x]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                yRow[x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            yBuf.position(y * yStride)
            yBuf.put(yRow, 0, w)
        }
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val uStride = planes[1].rowStride
        val vStride = planes[2].rowStride
        val uPix = planes[1].pixelStride
        val vPix = planes[2].pixelStride
        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                val c = px[(2 * y) * w + 2 * x]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                uBuf.put(y * uStride + x * uPix, u.toByte())
                vBuf.put(y * vStride + x * vPix, v.toByte())
            }
        }
    }
}
