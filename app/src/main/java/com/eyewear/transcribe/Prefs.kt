package com.eyewear.transcribe

import android.content.Context

/** 本地偏好：首页只依赖少量状态，其余进设置页 */
object Prefs {
    private const val PREF = "app_prefs"
    private const val KEY_SAVE_M4A = "save_m4a"

    fun saveM4a(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_SAVE_M4A, true)

    fun setSaveM4a(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SAVE_M4A, on).apply()
    }
}
