package com.eyewear.transcribe.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PCM(16k/16bit/mono) → AAC(M4A)。
 * 默认 24 kbps，4 小时约 40+MB（WAV 约 440MB）。
 */
class AacSaver(private val context: Context) {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var file: File? = null
    private var pcmBytes = 0L

    private val bufferInfo = MediaCodec.BufferInfo()

    val currentPath: String?
        get() = file?.absolutePath

    val pcmWritten: Long
        get() = pcmBytes

    @Synchronized
    fun start(): Boolean {
        return try {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "recordings")
            if (!dir.exists()) dir.mkdirs()
            val name = "eyewear_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.m4a"
            val f = File(dir, name)

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNELS
            )
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()

            codec = c
            muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            trackIndex = -1
            muxerStarted = false
            file = f
            pcmBytes = 0
            true
        } catch (t: Throwable) {
            Log.e(TAG, "aac start failed", t)
            releaseInternal()
            false
        }
    }

    @Synchronized
    fun write(pcm: ByteArray) {
        val c = codec ?: return
        val m = muxer ?: return
        try {
            pcmBytes += pcm.size
            val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex < 0) return
            val inBuf = c.getInputBuffer(inIndex) ?: return
            inBuf.clear()
            val n = minOf(inBuf.remaining(), pcm.size)
            inBuf.put(pcm, 0, n)
            c.queueInputBuffer(inIndex, 0, n, presentationTimeUs(), 0)
            drainEncoder(c, m, endOfStream = false)
        } catch (t: Throwable) {
            Log.e(TAG, "aac write failed", t)
        }
    }

    @Synchronized
    fun stop(): File? {
        val f = file
        val c = codec
        val m = muxer
        file = null
        codec = null
        muxer = null
        if (f == null || c == null || m == null) return null
        try {
            // EOS
            val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                c.queueInputBuffer(inIndex, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainEncoder(c, m, endOfStream = true)
            runCatching { c.stop() }
            runCatching { c.release() }
            if (muxerStarted) {
                runCatching { m.stop() }
            }
            runCatching { m.release() }
            exportToDownloads(f)
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("audio/mp4"), null)
            return f
        } catch (t: Throwable) {
            Log.e(TAG, "aac stop failed", t)
            return f
        } finally {
            pcmBytes = 0
            muxerStarted = false
        }
    }

    private fun releaseInternal() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { muxer?.release() }
        codec = null
        muxer = null
        file = null
    }

    private var lastPtsUs = 0L

    private fun presentationTimeUs(): Long {
        // 16k * 2bytes = 32000 bytes/s → 32 bytes/ms
        lastPtsUs = pcmBytes * 1_000_000L / (AudioConfig.SAMPLE_RATE * AudioConfig.BYTES_PER_FRAME)
        return lastPtsUs
    }

    private fun drainEncoder(c: MediaCodec, m: MediaMuxer, endOfStream: Boolean) {
        while (true) {
            val outIndex = c.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) error("format changed twice")
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0 && muxerStarted) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            m.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun exportToDownloads(src: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EyewearTranscribe")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    src.inputStream().use { it.copyTo(os) }
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "EyewearTranscribe"
                )
                if (!dir.exists()) dir.mkdirs()
                src.copyTo(File(dir, src.name), overwrite = true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "export downloads skipped: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "AacSaver"
        private const val TIMEOUT_US = 10_000L
        /** 语音 24kbps 足够；要更小可改 16000 */
        private const val BIT_RATE = 24_000
    }
}
