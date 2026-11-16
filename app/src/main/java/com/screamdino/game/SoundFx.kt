package com.screamdino.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Sound effects synthesised in code (no audio assets needed). */
class SoundFx {
    private val rate = 22050
    private val tracks = HashMap<String, AudioTrack>()
    @Volatile var enabled = true
    var onPlay: (() -> Unit)? = null

    init {
        try {
            tracks["jump"] = make(sweep(360f, 840f, 0.16f, 0.30f))
            tracks["hit"] = make(noise(0.38f, 0.55f))
            tracks["point"] = make(sweep(880f, 880f, 0.07f, 0.25f) + sweep(1320f, 1320f, 0.12f, 0.25f))
        } catch (_: Exception) {}
    }

    fun play(name: String) {
        if (!enabled) return
        val t = tracks[name] ?: return
        try { t.stop(); t.reloadStaticData(); t.play() } catch (_: Exception) {}
        onPlay?.invoke()
    }

    fun release() {
        for (t in tracks.values) try { t.release() } catch (_: Exception) {}
        tracks.clear()
    }

    private fun make(data: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        return t
    }

    private fun sweep(f0: Float, f1: Float, dur: Float, vol: Float): ShortArray {
        val n = (rate * dur).toInt()
        val out = ShortArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val p = i / n.toFloat()
            ph += 2.0 * PI * (f0 + (f1 - f0) * p) / rate
            val env = min(1f, p * 25f) * (1f - p)
            out[i] = (sin(ph) * env * vol * 32767.0).toInt().toShort()
        }
        return out
    }

    private fun noise(dur: Float, vol: Float): ShortArray {
        val n = (rate * dur).toInt()
        val out = ShortArray(n)
        val r = java.util.Random(7)
        var prev = 0f
        var ph = 0.0
        for (i in 0 until n) {
            val p = i / n.toFloat()
            prev = prev * 0.7f + (r.nextFloat() * 2f - 1f) * 0.3f
            ph += 2.0 * PI * (140f - 90f * p) / rate
            val env = (1f - p) * (1f - p)
            val v = (prev * 0.8f + sin(ph).toFloat() * 0.6f) * env * vol
            out[i] = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return out
    }
}
