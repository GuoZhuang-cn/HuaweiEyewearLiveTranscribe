package com.eyewear.transcribe.sync

import android.content.Context
import android.util.Log
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 坚果云 WebDAV
 * 默认地址：https://dav.jianguoyun.com/dav/
 * 用户名：注册邮箱；密码：坚果云「第三方应用管理」里生成的应用密码（不是登录密码）
 */
object NutstoreWebDav {

    const val PREF = "nutstore_webdav"
    const val KEY_ENABLED = "enabled"
    const val KEY_URL = "url"
    const val KEY_USER = "user"
    const val KEY_PASS = "app_password"
    const val KEY_DIR = "dir"

    private const val TAG = "NutstoreWebDav"
    private const val DEFAULT_URL = "https://dav.jianguoyun.com/dav/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun save(
        ctx: Context,
        url: String,
        user: String,
        pass: String,
        dir: String
    ) {
        prefs(ctx).edit()
            .putString(KEY_URL, url.ifBlank { DEFAULT_URL })
            .putString(KEY_USER, user.trim())
            .putString(KEY_PASS, pass.trim())
            .putString(KEY_DIR, normalizeDir(dir))
            .apply()
    }

    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun user(ctx: Context): String = prefs(ctx).getString(KEY_USER, "") ?: ""
    fun pass(ctx: Context): String = prefs(ctx).getString(KEY_PASS, "") ?: ""
    fun dir(ctx: Context): String =
        normalizeDir(prefs(ctx).getString(KEY_DIR, "EyewearTranscribe") ?: "EyewearTranscribe")

    fun configured(ctx: Context): Boolean =
        user(ctx).isNotBlank() && pass(ctx).isNotBlank()

    fun settingsLabel(ctx: Context): String {
        val u = user(ctx)
        return if (u.isBlank()) "未配置账号" else "$u → ${dir(ctx)}"
    }

    /** 上传录音文件；失败抛异常，由调用方写日志 */
    @Throws(IOException::class)
    fun upload(ctx: Context, file: File): String {
        if (!file.exists()) throw IOException("文件不存在")
        val user = user(ctx)
        val pass = pass(ctx)
        if (user.isBlank() || pass.isBlank()) throw IOException("未配置坚果云账号/应用密码")

        var base = baseUrl(ctx).trim()
        if (!base.endsWith("/")) base += "/"
        val folder = dir(ctx).trim('/')
        val remotePath = if (folder.isEmpty()) file.name else "$folder/${file.name}"
        val remoteUrl = base + remotePath.trimStart('/')

        // 确保远程目录存在（逐级 MKCOL）
        ensureCollections(base, folder, user, pass)

        val mime = "audio/mp4".toMediaType()
        val body = file.asRequestBody(mime)
        val req = Request.Builder()
            .url(remoteUrl)
            .header("Authorization", Credentials.basic(user, pass))
            .put(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 201 && resp.code != 204) {
                throw IOException("WebDAV PUT 失败 HTTP ${resp.code} ${resp.message}")
            }
        }
        Log.i(TAG, "uploaded $remoteUrl size=${file.length()}")
        return remoteUrl
    }

    private fun ensureCollections(base: String, folder: String, user: String, pass: String) {
        if (folder.isBlank()) return
        val parts = folder.split('/').filter { it.isNotBlank() }
        var cur = ""
        for (p in parts) {
            cur = if (cur.isEmpty()) p else "$cur/$p"
            val url = base + cur
            val req = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(user, pass))
                .method("MKCOL", null)
                .build()
            client.newCall(req).execute().use { resp ->
                // 201 created, 405 already exists
                if (resp.code !in listOf(201, 405, 301, 302)) {
                    Log.w(TAG, "MKCOL $url -> ${resp.code}")
                }
            }
        }
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun normalizeDir(d: String): String =
        d.trim().trim('/').ifBlank { "EyewearTranscribe" }
}
