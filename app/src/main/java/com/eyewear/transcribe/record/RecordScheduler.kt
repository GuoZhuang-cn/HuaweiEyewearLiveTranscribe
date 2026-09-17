package com.eyewear.transcribe.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

/**
 * 每日定时：到点拉起 Activity 自动开录 / 到点停录。
 * 使用 setAlarmClock，系统允许其唤起界面，便于满足前台服务启动限制。
 */
object RecordScheduler {

    const val PREF = "record_schedule"
    const val KEY_ENABLED = "enabled"
    const val KEY_START_H = "start_h"
    const val KEY_START_M = "start_m"
    const val KEY_END_H = "end_h"
    const val KEY_END_M = "end_m"

    private const val REQ_START = 1001
    private const val REQ_STOP = 1002

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) scheduleAll(ctx) else cancelAll(ctx)
    }

    fun saveTimes(ctx: Context, sh: Int, sm: Int, eh: Int, em: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(KEY_START_H, sh)
            .putInt(KEY_START_M, sm)
            .putInt(KEY_END_H, eh)
            .putInt(KEY_END_M, em)
            .apply()
        if (isEnabled(ctx)) scheduleAll(ctx)
    }

    fun getStart(ctx: Context): Pair<Int, Int> {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getInt(KEY_START_H, 8) to p.getInt(KEY_START_M, 0)
    }

    fun getEnd(ctx: Context): Pair<Int, Int> {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return p.getInt(KEY_END_H, 17) to p.getInt(KEY_END_M, 0)
    }

    fun label(ctx: Context): String {
        val (sh, sm) = getStart(ctx)
        val (eh, em) = getEnd(ctx)
        return String.format("%02d:%02d – %02d:%02d", sh, sm, eh, em)
    }

    fun scheduleAll(ctx: Context) {
        schedule(ctx, getStart(ctx), ScheduleReceiver.ACTION_SCHEDULE_START, REQ_START)
        schedule(ctx, getEnd(ctx), ScheduleReceiver.ACTION_SCHEDULE_STOP, REQ_STOP)
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, ScheduleReceiver.ACTION_SCHEDULE_START, REQ_START))
        am.cancel(pending(ctx, ScheduleReceiver.ACTION_SCHEDULE_STOP, REQ_STOP))
    }

    fun rescheduleIfEnabled(ctx: Context) {
        if (isEnabled(ctx)) scheduleAll(ctx)
    }

    private fun schedule(ctx: Context, hm: Pair<Int, Int>, action: String, reqCode: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, action, reqCode)
        val trigger = nextTriggerMillis(hm.first, hm.second)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, pi), pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
            Log.i("RecordScheduler", "scheduled $action at ${hm.first}:${hm.second} trigger=$trigger")
        } catch (t: Throwable) {
            Log.e("RecordScheduler", "schedule failed $action", t)
        }
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun pending(ctx: Context, action: String, reqCode: Int): PendingIntent {
        val intent = if (action == ScheduleReceiver.ACTION_SCHEDULE_START) {
            // 用显式类名，避免 Class.forName 反射
            Intent().apply {
                setClassName(ctx.packageName, "com.eyewear.transcribe.MainActivity")
                this.action = action
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        } else {
            Intent(ctx, ScheduleReceiver::class.java).apply {
                this.action = action
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == ScheduleReceiver.ACTION_SCHEDULE_START) {
            PendingIntent.getActivity(ctx, reqCode, intent, flags)
        } else {
            PendingIntent.getBroadcast(ctx, reqCode, intent, flags)
        }
    }

    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!target.after(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }
}
