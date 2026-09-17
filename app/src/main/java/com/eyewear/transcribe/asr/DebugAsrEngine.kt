package com.eyewear.transcribe.asr

import com.eyewear.transcribe.audio.AudioConfig
import kotlin.math.sqrt

/**
 * Verifies the Bluetooth mic path without network: emits energy-based status
 * and simulated short partials so you can confirm glasses audio is arriving.
 * Swap for [WebSocketAsrEngine] once cloud credentials are ready.
 */
class DebugAsrEngine : AsrEngine {
    override val name: String = "Debug"

    private var onPartial: ((String) -> Unit)? = null
    private var running = false
    private var silentFrames = 0
    private var voicedMs = 0
    private var speechBudgetMs = 0
    private var tick = 0

    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onPartial = onPartial
        running = true
        silentFrames = 0
        voicedMs = 0
        speechBudgetMs = 0
        tick = 0
    }

    override fun feed(pcm: ByteArray) {
        if (!running) return
        val rms = rms(pcm)
        val ms = pcm.size * 1000 / (AudioConfig.SAMPLE_RATE * AudioConfig.BYTES_PER_FRAME)
        tick++

        if (rms > 0.08f) {
            silentFrames = 0
            voicedMs += ms
            speechBudgetMs += ms
        } else {
            silentFrames++
        }

        // Every ~1.2s of speech, emit a debug partial (not real ASR).
        if (speechBudgetMs >= 1200) {
            speechBudgetMs = 0
            val peak = "电平 ${(rms * 100).toInt()}%"
            onPartial?.invoke("[调试] 检测到语音 · $peak · 累计 ${(voicedMs / 1000.0).format1()}s")
        }

        if (silentFrames > 50) {
            // ~1s silence marker once per quiet stretch
            if (tick % 50 == 0) {
                onPartial?.invoke("[调试] 环境安静中…")
            }
        }
    }

    override fun stop() {
        running = false
        onPartial = null
    }

    private fun rms(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += (s * s).toLong()
            i += 2
        }
        val n = pcm.size / 2
        if (n == 0) return 0f
        return (sqrt(sum.toDouble() / n) / 4000.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun Double.format1(): String = String.format("%.1f", this)
}
