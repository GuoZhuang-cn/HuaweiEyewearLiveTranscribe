package com.eyewear.transcribe.record

import android.content.Context
import com.eyewear.transcribe.BuildConfig
import com.eyewear.transcribe.asr.AsrEngine
import com.eyewear.transcribe.asr.AsrEngines
import com.eyewear.transcribe.audio.AacSaver
import com.eyewear.transcribe.audio.BtMicRecorder
import com.eyewear.transcribe.service.RecordForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

object RecordingSession {

    data class Snapshot(
        val running: Boolean = false,
        val status: String = "",
        val channel: String = "—",
        val level01: Float = 0f,
        val lastFile: String? = null,
        val lastFileSizeMb: Double = 0.0,
        val transcript: String = "",
        val message: String? = null
    )

    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()

    private var recorder: BtMicRecorder? = null
    private var saver: AacSaver? = null
    private var asr: AsrEngine? = null
    private val transcriptBuf = StringBuilder()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun isRunning(): Boolean = _snap.value.running

    @Synchronized
    fun start(context: Context, saveFile: Boolean = true, useCloud: Boolean = false): Boolean {
        if (_snap.value.running) return true
        val app = context.applicationContext

        val rec = BtMicRecorder(app)
        val fileSaver = if (saveFile) AacSaver(app) else null
        val engine: AsrEngine? = if (useCloud) {
            if (BuildConfig.XFYUN_APP_ID.isNotBlank() &&
                BuildConfig.XFYUN_API_KEY.isNotBlank() &&
                BuildConfig.XFYUN_API_SECRET.isNotBlank()
            ) {
                AsrEngines.xfyunRtasr(
                    BuildConfig.XFYUN_APP_ID,
                    BuildConfig.XFYUN_API_KEY,
                    BuildConfig.XFYUN_API_SECRET
                )
            } else AsrEngines.debug()
        } else null

        if (!rec.start()) {
            val err = rec.error.value ?: "录音启动失败"
            _snap.value = _snap.value.copy(running = false, message = err)
            rec.release()
            return false
        }
        if (fileSaver != null && !fileSaver.start()) {
            _snap.value = _snap.value.copy(running = false, message = "音频文件创建失败")
            rec.release()
            return false
        }

        engine?.start(
            onPartial = { t ->
                val shown = if (transcriptBuf.isEmpty()) t else {
                    val lastNl = transcriptBuf.lastIndexOf("\n")
                    transcriptBuf.substring(0, lastNl + 1) + t
                }
                _snap.value = _snap.value.copy(transcript = shown)
            },
            onFinal = { t ->
                if (t.isNotBlank()) {
                    if (transcriptBuf.isNotEmpty() && !transcriptBuf.endsWith("\n")) transcriptBuf.append('\n')
                    transcriptBuf.append(t)
                    if (!t.endsWith("\n")) transcriptBuf.append('\n')
                    _snap.value = _snap.value.copy(transcript = transcriptBuf.toString())
                }
            },
            onError = { msg -> _snap.value = _snap.value.copy(message = msg) }
        )

        rec.onPcm = { pcm ->
            fileSaver?.write(pcm)
            engine?.feed(pcm)
        }

        recorder = rec
        saver = fileSaver
        asr = engine

        // 观察录音器状态
        scope.launch {
            rec.state.collect { st ->
                if (!_snap.value.running) return@collect
                val status = when (st) {
                    BtMicRecorder.State.IDLE -> "待机"
                    BtMicRecorder.State.RECORDING -> "正在听…"
                    BtMicRecorder.State.PAUSED_CALL -> "来电中，已暂停"
                    BtMicRecorder.State.ERROR -> rec.error.value ?: "错误"
                }
                _snap.value = _snap.value.copy(status = status)
            }
        }
        scope.launch {
            rec.channelLabel.collect {
                if (_snap.value.running) _snap.value = _snap.value.copy(channel = it)
            }
        }
        scope.launch {
            rec.levels.collect {
                if (_snap.value.running) _snap.value = _snap.value.copy(level01 = it.rms01)
            }
        }
        scope.launch {
            rec.error.collect { err ->
                if (!err.isNullOrBlank() && _snap.value.running) {
                    _snap.value = _snap.value.copy(message = err)
                }
            }
        }

        _snap.value = Snapshot(
            running = true,
            status = "正在听…",
            channel = _snap.value.channel,
            lastFile = _snap.value.lastFile,
            lastFileSizeMb = _snap.value.lastFileSizeMb,
            transcript = if (useCloud) transcriptBuf.toString() else _snap.value.transcript
        )

        runCatching { RecordForegroundService.start(app) }
        return true
    }

    @Synchronized
    fun stop(context: Context): File? {
        val rec = recorder
        val fileSaver = saver
        val engine = asr
        recorder = null
        saver = null
        asr = null

        rec?.onPcm = null
        runCatching { engine?.stop() }
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        val f = fileSaver?.stop()

        runCatching { RecordForegroundService.stop(context.applicationContext) }

        val mb = f?.let { it.length() / 1024.0 / 1024.0 } ?: 0.0
        _snap.value = Snapshot(
            running = false,
            status = "待机",
            channel = "—",
            level01 = 0f,
            lastFile = f?.absolutePath ?: _snap.value.lastFile,
            lastFileSizeMb = if (f != null) mb else _snap.value.lastFileSizeMb,
            transcript = transcriptBuf.toString(),
            message = f?.let { "已保存 ${String.format(Locale.US, "%.1f", mb)}MB" }
        )
        return f
    }

    fun consumeMessage() {
        _snap.value = _snap.value.copy(message = null)
    }

    fun clearTranscript() {
        transcriptBuf.setLength(0)
        _snap.value = _snap.value.copy(transcript = "")
    }
}
