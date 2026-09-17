package com.eyewear.transcribe.audio

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 16kHz / 16bit / mono PCM 写成 WAV。
 * 先写占位头，stop 时回填数据长度。
 */
class WavSaver(private val context: Context) {

    private var fos: FileOutputStream? = null
    private var file: File? = null
    private var dataBytes = 0L

    val currentPath: String?
        get() = file?.absolutePath

    val currentSize: Long
        get() = dataBytes

    @Synchronized
    fun start(): Boolean {
        return try {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "recordings")
            if (!dir.exists()) dir.mkdirs()
            val name = "eyewear_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav"
            val f = File(dir, name)
            val out = FileOutputStream(f)
            out.write(ByteArray(HEADER_SIZE)) // placeholder
            fos = out
            file = f
            dataBytes = 0
            true
        } catch (t: Throwable) {
            Log.e(TAG, "wav start failed", t)
            fos = null
            file = null
            false
        }
    }

    @Synchronized
    fun write(pcm: ByteArray) {
        val out = fos ?: return
        try {
            out.write(pcm)
            dataBytes += pcm.size
        } catch (t: Throwable) {
            Log.e(TAG, "wav write failed", t)
        }
    }

    /** 回填 WAV 头，关闭文件，并尽量拷贝到系统「下载」目录方便取文件 */
    @Synchronized
    fun stop(): File? {
        val f = file
        val out = fos
        fos = null
        file = null
        if (f == null || out == null) return null
        try {
            out.flush()
            out.close()
            patchHeader(f, dataBytes)
            exportToDownloads(f)
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("audio/wav"), null)
            return f
        } catch (t: Throwable) {
            Log.e(TAG, "wav stop failed", t)
            return f
        } finally {
            dataBytes = 0
        }
    }

    private fun patchHeader(f: File, dataSize: Long) {
        try {
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(0)
                raf.write(wavHeader(dataSize))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "patch header failed", t)
        }
    }

    private fun exportToDownloads(src: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, src.name)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EyewearTranscribe")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    src.inputStream().use { it.copyTo(os) }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "EyewearTranscribe")
                if (!dir.exists()) dir.mkdirs()
                src.copyTo(File(dir, src.name), overwrite = true)
            }
        } catch (t: Throwable) {
            // 无存储权限时忽略，原始文件仍在 app 私有目录
            Log.w(TAG, "export downloads skipped: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "WavSaver"
        private const val HEADER_SIZE = 44
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BITS = 16

        fun wavHeader(dataSize: Long): ByteArray {
            val byteRate = SAMPLE_RATE * CHANNELS * BITS / 8
            val blockAlign = CHANNELS * BITS / 8
            val total = HEADER_SIZE + dataSize
            val buf = ByteArray(HEADER_SIZE)
            fun putStr(off: Int, s: String) {
                s.forEachIndexed { i, c -> buf[off + i] = c.code.toByte() }
            }
            fun putInt(off: Int, v: Long) {
                buf[off] = (v and 0xFF).toByte()
                buf[off + 1] = ((v shr 8) and 0xFF).toByte()
                buf[off + 2] = ((v shr 16) and 0xFF).toByte()
                buf[off + 3] = ((v shr 24) and 0xFF).toByte()
            }
            putStr(0, "RIFF")
            putInt(4, total - 8)
            putStr(8, "WAVE")
            putStr(12, "fmt ")
            putInt(16, 16) // PCM fmt chunk size
            buf[20] = 1 // audio format PCM
            buf[21] = 0
            buf[22] = CHANNELS.toByte()
            buf[23] = 0
            putInt(24, SAMPLE_RATE.toLong())
            putInt(28, byteRate.toLong())
            buf[32] = blockAlign.toByte()
            buf[33] = 0
            buf[34] = BITS.toByte()
            buf[35] = 0
            putStr(36, "data")
            putInt(40, dataSize)
            return buf
        }
    }
}
