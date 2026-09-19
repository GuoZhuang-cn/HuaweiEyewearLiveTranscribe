package com.eyewear.transcribe.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.eyewear.transcribe.sync.NutstoreWebDav
import java.io.File
import java.util.concurrent.Executors

class ScheduleReceiver : BroadcastReceiver() {

    private val io = Executors.newSingleThreadExecutor()

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = context.applicationContext
        Log.i(TAG, "onReceive $action")
        when (action) {
            ACTION_SCHEDULE_STOP -> {
                // 定时结束停录即可；开录在 MainActivity 处理
                val f = RecordingSession.stop(app)
                val time = RecordScheduler.fmtBj(System.currentTimeMillis())
                if (f != null) {
                    RecordLog.append(app, "定时结束并保存", "${f.name} ${f.length()} bytes $time")
                    syncIfNeeded(app, f)
                    Toast.makeText(app, "定时结束：${f.name}", Toast.LENGTH_LONG).show()
                } else {
                    RecordLog.append(app, "定时结束", "无文件")
                    Toast.makeText(app, "定时结束", Toast.LENGTH_SHORT).show()
                }
                RecordScheduler.rescheduleIfEnabled(app)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                RecordScheduler.rescheduleIfEnabled(app)
                RecordLog.append(app, "开机/升级后重新排程", RecordScheduler.label(app))
            }
        }
    }

    private fun syncIfNeeded(ctx: Context, file: File) {
        if (!NutstoreWebDav.isEnabled(ctx) || !NutstoreWebDav.configured(ctx)) {
            RecordLog.append(ctx, "未启用坚果云同步", file.name)
            return
        }
        io.execute {
            try {
                val url = NutstoreWebDav.upload(ctx, file)
                RecordLog.append(ctx, "坚果云同步成功", url)
            } catch (t: Throwable) {
                RecordLog.append(ctx, "坚果云同步失败", "${file.name} ${t.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ScheduleReceiver"
        const val ACTION_SCHEDULE_START = "com.eyewear.transcribe.SCHEDULE_START"
        const val ACTION_SCHEDULE_STOP = "com.eyewear.transcribe.SCHEDULE_STOP"
    }
}
