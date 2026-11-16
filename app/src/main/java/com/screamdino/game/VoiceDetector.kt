package com.screamdino.game

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Real-time, on-device shout detector.
 * Reads 20 ms PCM frames, computes loudness (dBFS) and fires [onShout] on a loud onset.
 * Audio is analysed in memory frame by frame and immediately discarded: never saved or sent anywhere.
 */
class VoiceDetector(private val onShout: () -> Unit) {

    @Volatile var levelDb = -100f
        private set
    @Volatile var thresholdDb = -17f
    @Volatile var suppressUntil = 0L
    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    /** Sensitivity 0..100 -> threshold from -5 dBFS (needs a very loud shout) to -35 dBFS (very sensitive). */
    fun setSensitivity(s: Int) { thresholdDb = -5f - 0.3f * s }

    private fun build(src: Int, size: Int): AudioRecord? = try {
        val r = AudioRecord(src, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
    } catch (e: Exception) { null }

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val size = maxOf(minBuf, FRAME * 2 * 4)
        val rec = build(MediaRecorder.AudioSource.VOICE_RECOGNITION, size)
            ?: build(MediaRecorder.AudioSource.MIC, size) ?: return false
        running = true
        thread = Thread { loop(rec) }.also { it.start() }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        try { thread?.join(400) } catch (_: InterruptedException) {}
        thread = null
        levelDb = -100f
    }

    private fun loop(rec: AudioRecord) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val buf = ShortArray(FRAME)
        var floor = -70f          // slowly adapting background-noise estimate
        var armed = true
        var last = 0L
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, FRAME)
                if (n < 0) break
                if (n == 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                val rms = sqrt(sum / n)
                val db = if (rms < 1.0) -100f else (20.0 * log10(rms / 32768.0)).toFloat()
                levelDb = db
                val thr = thresholdDb
                if (db < thr - 3f) floor += (db - floor) * 0.01f
                floor = floor.coerceIn(-90f, -35f)
                val now = System.nanoTime() / 1_000_000
                if (db < thr - 6f) armed = true
                // Trigger only on a loud onset well above background noise (ordinary talking stays below).
                if (armed && db > thr && db - floor > 14f && now > suppressUntil && now - last > 250) {
                    armed = false
                    last = now
                    onShout()
                }
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            running = false
        }
    }

    private companion object {
        const val RATE = 16000
        const val FRAME = 320 // 20 ms
    }
}
