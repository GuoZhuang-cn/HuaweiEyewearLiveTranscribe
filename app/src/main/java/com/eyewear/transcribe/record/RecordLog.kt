package com.eyewear.transcribe.record

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 录音事件日志：每次开录/结束/同步都写一条，便于追溯文件从哪来。 */
object RecordLog {

    private val bj = TimeZone.getTimeZone("Asia/Shanghai")

    private fun logFile(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "record.log")
    }

    fun append(ctx: Context, event: String, detail: String = "") {
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            fmt.timeZone = bj
            val line = buildString {
                append(fmt.format(Date()))
                append(" [BJ] ")
                append(event)
                if (detail.isNotBlank()) append(" | ").append(detail)
                append("\n")
            }
            logFile(ctx).appendText(line)
        } catch (_: Throwable) {
        }
    }

    fun readRecent(ctx: Context, maxLines: Int = 40): String {
        return try {
            val f = logFile(ctx)
            if (!f.exists()) return "（暂无日志）"
            val lines = f.readLines()
            lines.takeLast(maxLines).joinToString("\n")
        } catch (_: Throwable) {
            "（日志读取失败）"
        }
    }

    fun path(ctx: Context): String = logFile(ctx).absolutePath
}
