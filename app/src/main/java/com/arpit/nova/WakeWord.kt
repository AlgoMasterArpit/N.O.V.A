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
        // The gate opens for anything SNR times louder than the room's background noise (3x is about +10 dB),
        // so a quiet room hears you from far away and a noisy room doesn't burn battery.
        // ponytail: lower SNR (e.g. 2.0) for more range, raise it if it wakes on random noise.
        const val SNR = 3.0
        const val MIN_LOUDNESS = 40.0 // floor so pure silence doesn't open the gate
        private const val LEARN_QUIET = 0.05 // learn room noise fast while it's quiet
        private const val LEARN_LOUD = 0.005 // and slowly during constant noise (TV, fan)
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
        var noise = 100.0 // running estimate of the room's background level
        rec.reset()
        ar.startRecording()
        try {
            while (on) {
                val n = ar.read(buf, 0, CHUNK)
                if (n <= 0) continue
                val level = rms(buf, n)
                val loud = level > maxOf(MIN_LOUDNESS, noise * SNR)
                noise += (level - noise) * (if (loud) LEARN_LOUD else LEARN_QUIET)
                if (loud) {
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
