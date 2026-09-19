package com.eyewear.transcribe.record

import android.content.Context
import com.eyewear.transcribe.audio.AacSaver
import com.eyewear.transcribe.audio.BtMicRecorder
import com.eyewear.transcribe.service.RecordForegroundService
import com.eyewear.transcribe.sync.NutstoreWebDav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

object RecordingSession {

    data class Snapshot(
        val running: Boolean = false,
        val status: String = "",
        val channel: String = "—",
        val level01: Float = 0f,
        val lastFile: String? = null,
        val lastFileSizeMb: Double = 0.0,
        val message: String? = null,
        val logTail: String = "",
        val syncState: String = ""
    )

    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap.asStateFlow()

    private var recorder: BtMicRecorder? = null
    private var saver: AacSaver? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = Executors.newSingleThreadExecutor()

    fun isRunning(): Boolean = _snap.value.running

    @Synchronized
    fun start(context: Context, saveFile: Boolean = true, trigger: String = "手动"): Boolean {
        if (_snap.value.running) return true
        val app = context.applicationContext
        val startBj = RecordScheduler.fmtBj(System.currentTimeMillis())

        val rec = BtMicRecorder(app)
        val fileSaver = if (saveFile) AacSaver(app) else null

        if (!rec.start()) {
            val err = rec.error.value ?: "录音启动失败"
            RecordLog.append(app, "录音启动失败[$trigger]", err)
            _snap.value = _snap.value.copy(
                running = false,
                message = err,
                logTail = RecordLog.readRecent(app)
            )
            rec.release()
            return false
        }
        if (fileSaver != null && !fileSaver.start()) {
            RecordLog.append(app, "音频文件创建失败[$trigger]")
            _snap.value = _snap.value.copy(
                running = false,
                message = "音频文件创建失败",
                logTail = RecordLog.readRecent(app)
            )
            rec.release()
            return false
        }

        rec.onPcm = { pcm -> fileSaver?.write(pcm) }

        recorder = rec
        saver = fileSaver

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

        RecordLog.append(app, "开始录音[$trigger]", "BJ $startBj saveM4a=$saveFile")
        _snap.value = Snapshot(
            running = true,
            status = "正在听…",
            channel = _snap.value.channel,
            lastFile = _snap.value.lastFile,
            lastFileSizeMb = _snap.value.lastFileSizeMb,
            logTail = RecordLog.readRecent(app),
            syncState = ""
        )

        runCatching { RecordForegroundService.start(app) }
        return true
    }

    @Synchronized
    fun stop(context: Context, trigger: String = "手动"): File? {
        val rec = recorder
        val fileSaver = saver
        recorder = null
        saver = null

        rec?.onPcm = null
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        val f = fileSaver?.stop()

        runCatching { RecordForegroundService.stop(context.applicationContext) }

        val app = context.applicationContext
        val endBj = RecordScheduler.fmtBj(System.currentTimeMillis())
        val mb = f?.let { it.length() / 1024.0 / 1024.0 } ?: 0.0

        if (f != null) {
            RecordLog.append(
                app,
                "结束并保存[$trigger]",
                "${f.name} ${String.format(Locale.US, "%.2f", mb)}MB ${f.absolutePath} BJ $endBj"
            )
            maybeUpload(app, f)
        } else {
            RecordLog.append(app, "结束录音[$trigger]", "无文件 BJ $endBj")
        }

        _snap.value = Snapshot(
            running = false,
            status = "待机",
            channel = "—",
            level01 = 0f,
            lastFile = f?.absolutePath ?: _snap.value.lastFile,
            lastFileSizeMb = if (f != null) mb else _snap.value.lastFileSizeMb,
            message = f?.let { "已保存 ${String.format(Locale.US, "%.1f", mb)}MB" },
            logTail = RecordLog.readRecent(app),
            syncState = if (f != null && NutstoreWebDav.isEnabled(app)) "同步中…" else ""
        )
        return f
    }

    private fun maybeUpload(ctx: Context, file: File) {
        if (!NutstoreWebDav.isEnabled(ctx) || !NutstoreWebDav.configured(ctx)) {
            RecordLog.append(ctx, "坚果云同步跳过", "未启用或未配置")
            return
        }
        _snap.value = _snap.value.copy(syncState = "同步中…")
        io.execute {
            try {
                val url = NutstoreWebDav.upload(ctx, file)
                RecordLog.append(ctx, "坚果云同步成功", url)
                _snap.value = _snap.value.copy(
                    syncState = "已同步坚果云",
                    message = "已同步到坚果云",
                    logTail = RecordLog.readRecent(ctx)
                )
            } catch (t: Throwable) {
                RecordLog.append(ctx, "坚果云同步失败", t.message ?: "unknown")
                _snap.value = _snap.value.copy(
                    syncState = "同步失败",
                    message = "坚果云同步失败: ${t.message}",
                    logTail = RecordLog.readRecent(ctx)
                )
            }
        }
    }

    fun consumeMessage() {
        _snap.value = _snap.value.copy(message = null)
    }

    fun refreshLog(ctx: Context) {
        _snap.value = _snap.value.copy(logTail = RecordLog.readRecent(ctx.applicationContext))
    }
}
