package com.eyewear.transcribe.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "onReceive $action")
        when (action) {
            ACTION_SCHEDULE_STOP -> {
                val f = RecordingSession.stop(context)
                Toast.makeText(
                    context,
                    if (f != null) "定时结束，已保存 ${f.name}" else "定时结束",
                    Toast.LENGTH_LONG
                ).show()
                // 继续排明天
                RecordScheduler.rescheduleIfEnabled(context)
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                RecordScheduler.rescheduleIfEnabled(context)
            }
        }
    }

    companion object {
        private const val TAG = "ScheduleReceiver"
        const val ACTION_SCHEDULE_START = "com.eyewear.transcribe.SCHEDULE_START"
        const val ACTION_SCHEDULE_STOP = "com.eyewear.transcribe.SCHEDULE_STOP"
    }
}
