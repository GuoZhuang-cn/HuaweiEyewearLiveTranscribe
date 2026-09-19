package com.eyewear.transcribe.record

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 每周定时录音：可选星期 + 北京时间（Asia/Shanghai）。
 * 使用 setAlarmClock，到点拉起界面/停录广播。
 */
object RecordScheduler {

    const val PREF = "record_schedule"
    const val KEY_ENABLED = "enabled"
    const val KEY_START_H = "start_h"
    const val KEY_START_M = "start_m"
    const val KEY_END_H = "end_h"
    const val KEY_END_M = "end_m"
    /** 位掩码：bit0=周日 … bit6=周六（与 Calendar.DAY_OF_WEEK 对齐：SUNDAY=1 → bit0） */
    const val KEY_DAYS_MASK = "days_mask"

    private const val REQ_START = 1001
    private const val REQ_STOP = 1002
    private const val TAG = "RecordScheduler"

    val BJ: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    val DAY_LABELS = arrayOf("日", "一", "二", "三", "四", "五", "六")

    fun isEnabled(ctx: Context): Boolean =
        p(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        p(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) scheduleAll(ctx) else cancelAll(ctx)
        RecordLog.append(ctx, if (enabled) "定时已开启" else "定时已关闭", label(ctx))
    }

    fun saveTimes(ctx: Context, sh: Int, sm: Int, eh: Int, em: Int, daysMask: Int) {
        p(ctx).edit()
            .putInt(KEY_START_H, sh)
            .putInt(KEY_START_M, sm)
            .putInt(KEY_END_H, eh)
            .putInt(KEY_END_M, em)
            .putInt(KEY_DAYS_MASK, if (daysMask == 0) defaultWeekdaysMask() else daysMask)
            .apply()
        if (isEnabled(ctx)) scheduleAll(ctx)
        RecordLog.append(ctx, "定时配置已保存", label(ctx))
    }

    fun getStart(ctx: Context): Pair<Int, Int> {
        val x = p(ctx)
        return x.getInt(KEY_START_H, 8) to x.getInt(KEY_START_M, 0)
    }

    fun getEnd(ctx: Context): Pair<Int, Int> {
        val x = p(ctx)
        return x.getInt(KEY_END_H, 17) to x.getInt(KEY_END_M, 0)
    }

    /** 默认工作日 周一~周五 */
    fun defaultWeekdaysMask(): Int {
        // Calendar: SUN=1 MON=2 ... SAT=7
        // mask bit (dow-1): MON bit1 ... FRI bit5
        var m = 0
        for (d in Calendar.MONDAY..Calendar.FRIDAY) m = m or (1 shl (d - 1))
        return m
    }

    fun getDaysMask(ctx: Context): Int =
        p(ctx).getInt(KEY_DAYS_MASK, defaultWeekdaysMask())

    fun isDaySelected(mask: Int, calendarDow: Int): Boolean =
        (mask and (1 shl (calendarDow - 1))) != 0

    fun daysLabel(mask: Int): String {
        val selected = (1..7).filter { isDaySelected(mask, it) }
        return if (selected.isEmpty()) "未选日"
        else selected.joinToString("、") { "周" + DAY_LABELS[it - 1] }
    }

    fun label(ctx: Context): String {
        val (sh, sm) = getStart(ctx)
        val (eh, em) = getEnd(ctx)
        val t = String.format(Locale.CHINA, "%02d:%02d–%02d:%02d", sh, sm, eh, em)
        return "${daysLabel(getDaysMask(ctx))} $t（北京时间）"
    }

    fun scheduleAll(ctx: Context) {
        cancelAll(ctx)
        val mask = getDaysMask(ctx)
        if (mask == 0) {
            RecordLog.append(ctx, "定时未选星期，未排程")
            return
        }
        val (sh, sm) = getStart(ctx)
        val (eh, em) = getEnd(ctx)
        val startAt = nextTrigger(mask, sh, sm)
        val endAt = nextTrigger(mask, eh, em, preferAfter = startAt)
        scheduleAt(ctx, startAt, ScheduleReceiver.ACTION_SCHEDULE_START, REQ_START)
        scheduleAt(ctx, endAt, ScheduleReceiver.ACTION_SCHEDULE_STOP, REQ_STOP)
        RecordLog.append(ctx, "闹钟已排程", "start=$startAt end=$endAt cfg=${label(ctx)}")
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pending(ctx, ScheduleReceiver.ACTION_SCHEDULE_START, REQ_START)) }
        runCatching { am.cancel(pending(ctx, ScheduleReceiver.ACTION_SCHEDULE_STOP, REQ_STOP)) }
    }

    fun rescheduleIfEnabled(ctx: Context) {
        if (isEnabled(ctx)) scheduleAll(ctx)
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    private fun scheduleAt(ctx: Context, trigger: Long, action: String, reqCode: Int) {
        if (trigger <= 0) return
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, action, reqCode)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, pi), pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
            Log.i(TAG, "scheduled $action @ $trigger (${fmtBj(trigger)})")
        } catch (t: Throwable) {
            Log.e(TAG, "schedule failed $action", t)
            RecordLog.append(ctx, "闹钟设置失败", "$action ${t.message}")
        }
    }

    fun fmtBj(millis: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss E", Locale.CHINA)
        f.timeZone = BJ
        return f.format(java.util.Date(millis))
    }

    /** 在选中星期里找下一次 HH:mm（北京时间） */
    private fun nextTrigger(daysMask: Int, hour: Int, minute: Int, preferAfter: Long = 0L): Long {
        val now = System.currentTimeMillis()
        val minT = if (preferAfter > now) preferAfter + 1000L else now
        val cal = Calendar.getInstance(BJ)
        for (add in 0..14) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, add)
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val t = cal.timeInMillis
            if (t > minT && isDaySelected(daysMask, cal.get(Calendar.DAY_OF_WEEK))) {
                return t
            }
        }
        // fallback +1 day
        val c2 = Calendar.getInstance(BJ).apply {
            timeInMillis = now + 24L * 3600 * 1000
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c2.timeInMillis
    }

    private fun pending(ctx: Context, action: String, reqCode: Int): PendingIntent {
        val intent = if (action == ScheduleReceiver.ACTION_SCHEDULE_START) {
            Intent().apply {
                setClassName(ctx.packageName, "com.eyewear.transcribe.MainActivity")
                this.action = action
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        } else {
            Intent(ctx, ScheduleReceiver::class.java).apply { this.action = action }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (action == ScheduleReceiver.ACTION_SCHEDULE_START) {
            PendingIntent.getActivity(ctx, reqCode, intent, flags)
        } else {
            PendingIntent.getBroadcast(ctx, reqCode, intent, flags)
        }
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
