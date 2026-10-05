package com.montageai.app

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Small observable wrapper around MediaPlayer used by the editor's timeline. */
class AudioPlayerState(path: String?) {
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var failed by mutableStateOf(false)
        private set

    private var mp: MediaPlayer? = null

    init {
        if (path != null) {
            var m: MediaPlayer? = null
            try {
                m = MediaPlayer()
                m.setDataSource(path)
                m.prepare()
                m.setOnCompletionListener {
                    playing = false
                    positionMs = durationMs
                }
                durationMs = max(0, m.duration)
                mp = m
            } catch (e: Exception) {
                try {
                    m?.release()
                } catch (e: Exception) {
                }
                failed = true
            }
        } else {
            failed = true
        }
    }

    fun toggle() {
        if (playing) pause() else play()
    }

    fun play() {
        val m = mp ?: return
        if (positionMs >= durationMs - 50) {
            m.seekTo(0)
            positionMs = 0
        }
        m.start()
        playing = true
    }

    fun pause() {
        val m = mp ?: return
        if (m.isPlaying) m.pause()
        playing = false
    }

    fun seekTo(ms: Int) {
        val target = min(max(0, ms), durationMs)
        mp?.seekTo(target)
        positionMs = target
    }

    fun seekBy(deltaMs: Int) = seekTo(positionMs + deltaMs)

    /** Called from the UI clock while playing. */
    fun tick() {
        val m = mp ?: return
        if (playing) {
            try {
                positionMs = m.currentPosition
            } catch (e: Exception) {
            }
        }
    }

    fun release() {
        try {
            mp?.release()
        } catch (e: Exception) {
        }
        mp = null
        playing = false
    }

    val positionSec: Double get() = positionMs / 1000.0
    val durationSec: Double get() = durationMs / 1000.0
}

@Composable
fun rememberAudioPlayer(path: String?): AudioPlayerState {
    val state = remember(path) { AudioPlayerState(path) }
    DisposableEffect(state) { onDispose { state.release() } }
    LaunchedEffect(state) {
        while (true) {
            state.tick()
            delay(60)
        }
    }
    return state
}

class WaveData(val peaks: FloatArray, val durationSec: Double)

object Waveform {
    /** Decodes the audio and reduces it to `bars` normalized peak values for drawing. */
    fun analyze(path: String, bars: Int = 320): WaveData {
        val pcm = AudioTools.decodeToMono(path)
        val n = pcm.samples.size
        val out = FloatArray(bars)
        val per = max(1, n / bars)
        var top = 1
        for (b in 0 until bars) {
            var m = 0
            val s = b * per
            val e = min(n, s + per)
            for (i in s until e) {
                val v = abs(pcm.samples[i].toInt())
                if (v > m) m = v
            }
            out[b] = m.toFloat()
            if (m > top) top = m
        }
        for (b in 0 until bars) out[b] = out[b] / top
        return WaveData(out, n / pcm.sampleRate.toDouble())
    }
}
