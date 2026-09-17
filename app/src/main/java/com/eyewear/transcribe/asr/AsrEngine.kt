package com.eyewear.transcribe.asr

/**
 * Pluggable ASR backend. Feed 16 kHz mono PCM frames; receive partial/final text on main-safe callbacks.
 */
interface AsrEngine {
    val name: String

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    )

    fun feed(pcm: ByteArray)

    fun stop()
}
