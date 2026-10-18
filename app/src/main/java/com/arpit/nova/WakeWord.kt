package com.arpit.nova

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Sleep-mode ears. The mic stays open, but Vosk only decodes while it's loud enough to be a voice,
 * so a quiet room costs almost no CPU (and battery).
 */
class WakeWord(private val rec: Recognizer, private val onWake: () -> Unit) : Thread("nova-wake") {
    companion object {
        // ponytail: fixed loudness gate. Raise it if background noise keeps it decoding, lower it if it misses you.
        const val LOUDNESS = 500.0
        private const val RATE = 16000
        private const val CHUNK = RATE / 10 // 100 ms
        private const val HANG = 10 // keep decoding 1 s after the voice stops
    }

    @Volatile private var on = true

    fun quit() {
        on = false
        if (currentThread() != this) join(500)
    }

    @SuppressLint("MissingPermission") // the service only runs after RECORD_AUDIO is granted
    override fun run() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
        val buf = ShortArray(CHUNK)
        val prev = ShortArray(CHUNK)
        var prevN = 0
        var hang = 0
        rec.reset()
        ar.startRecording()
        try {
            while (on) {
                val n = ar.read(buf, 0, CHUNK)
                if (n <= 0) continue
                if (rms(buf, n) > LOUDNESS) {
                    if (hang == 0 && prevN > 0) rec.acceptWaveForm(prev, prevN) // the word's soft start
                    hang = HANG
                }
                if (hang > 0) {
                    hang--
                    val text = when {
                        rec.acceptWaveForm(buf, n) -> rec.result
                        hang == 0 -> rec.finalResult
                        else -> ""
                    }
                    if ("nova" in text || "wake up" in text) { on = false; onWake() }
                    if (hang == 0) rec.reset()
                }
                buf.copyInto(prev)
                prevN = n
            }
        } finally {
            ar.stop()
            ar.release()
        }
    }

    private fun rms(b: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += b[i] * b[i].toDouble()
        return sqrt(sum / n)
    }
}
