package com.eyewear.transcribe.audio

object AudioConfig {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_FRAME = 2
    const val FRAME_MS = 20
    const val FRAME_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * FRAME_MS / 1000
    const val MIN_BUFFER_FRAMES = 10
}
