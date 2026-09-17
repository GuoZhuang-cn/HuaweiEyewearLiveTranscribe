package com.eyewear.transcribe.asr

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 讯飞「实时语音转写大模型」
 *
 * wss://office-api-ast-dx.iflyaisol.com/ast/communicate/v1
 * 鉴权：对业务参数按 key 升序拼接后，用 APISecret 做 HmacSHA1 + Base64
 * 结果：msg_type/result，data 为 JSON 对象（非字符串）
 */
class XfyunRtasrEngine(
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String,
    private val endpoint: String = "wss://office-api-ast-dx.iflyaisol.com/ast/communicate/v1"
) : AsrEngine {

    override val name: String = "讯飞实时转写大模型"

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile
    private var ready = false

    private var sessionId: String = UUID.randomUUID().toString().replace("-", "")

    private val partialBySeg = LinkedHashMap<String, String>()
    private val finalText = StringBuilder()

    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (started.getAndSet(true)) return
        ready = false
        partialBySeg.clear()
        finalText.setLength(0)
        sessionId = UUID.randomUUID().toString().replace("-", "")

        if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            started.set(false)
            onError("请配置 XFYUN_APP_ID / XFYUN_API_KEY / XFYUN_API_SECRET")
            return
        }

        val url = try {
            signedUrl()
        } catch (t: Throwable) {
            started.set(false)
            onError("讯飞签名失败: ${t.message}")
            return
        }

        Log.i(TAG, "connecting session=$sessionId")
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
                Log.i(TAG, "WS open http=${response.code}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text, onPartial, onFinal, onError)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.set(false)
                started.set(false)
                Log.e(TAG, "WS failure http=${response?.code}", t)
                onError("讯飞连接失败: ${t.message} http=${response?.code}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                started.set(false)
                ready = false
                Log.i(TAG, "WS closed code=$code reason=$reason")
            }
        })
    }

    override fun feed(pcm: ByteArray) {
        if (!open.get() || !ready) return
        ws?.send(pcm.toByteString())
    }

    override fun stop() {
        if (open.get()) {
            val end = JSONObject()
                .put("end", true)
                .put("sessionId", sessionId)
            runCatching { ws?.send(end.toString().toByteArray().toByteString()) }
            ws?.close(1000, "client stop")
        }
        ws = null
        open.set(false)
        started.set(false)
        ready = false
        partialBySeg.clear()
    }

    /** 组装带签名的 wss URL */
    private fun signedUrl(): String {
        val utc = utcNow()
        val params = linkedMapOf(
            "accessKeyId" to apiKey,
            "appId" to appId,
            "audio_encode" to "pcm_s16le",
            "lang" to "autodialect",
            "samplerate" to "16000",
            "utc" to utc,
            "uuid" to sessionId,
        )
        val signature = sign(params, apiSecret)

        val qs = buildString {
            params.forEach { (k, v) ->
                if (isNotEmpty()) append('&')
                append(urlEncode(k)).append('=').append(urlEncode(v))
            }
            append("&signature=").append(urlEncode(signature))
        }
        return "$endpoint?$qs"
    }

    private fun handleMessage(
        text: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val root = JSONObject(text)
            val msgType = root.optString("msg_type").ifEmpty { root.optString("action") }
            val resType = root.optString("res_type")
            Log.i(TAG, "msg_type=$msgType res_type=$resType raw=${text.take(280)}")

            when (msgType) {
                "started", "start" -> {
                    ready = true
                    Log.i(TAG, "handshake ok sid=${root.optString("sid")}")
                }
                "error" -> {
                    val desc = root.optString("desc").ifEmpty { text }
                    val code = root.optString("code")
                    onError(humanizeError(code, desc))
                }
                "result" -> {
                    val data = root.opt("data")
                    if (data is JSONObject) {
                        if (resType == "frc" || data.optBoolean("normal", true).not()) {
                            val desc = data.optString("desc", text)
                            onError("讯飞异常: $desc")
                            return
                        }
                        handleAsrData(data, onPartial, onFinal)
                    } else if (data is String && data.isNotBlank()) {
                        // 兼容 data 为字符串的情况
                        handleAsrData(JSONObject(data), onPartial, onFinal)
                    }
                }
                else -> Log.i(TAG, "other msg=$msgType")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "parse fail: $text", t)
            onError("讯飞解析失败: ${t.message}")
        }
    }

    private fun handleAsrData(
        data: JSONObject,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit
    ) {
        val ls = data.optBoolean("ls", false)
        val segId = data.optString("seg_id", data.optInt("seg_id", -1).toString())
        val cn = data.optJSONObject("cn") ?: return
        val st = cn.optJSONObject("st") ?: return
        val type = st.optString("type") // 0 确定 / 1 中间
        val rt = st.optJSONArray("rt") ?: return

        val sb = StringBuilder()
        for (i in 0 until rt.length()) {
            val seg = rt.optJSONObject(i) ?: continue
            val wsArr = seg.optJSONArray("ws") ?: continue
            for (j in 0 until wsArr.length()) {
                val w = wsArr.optJSONObject(j) ?: continue
                val cwArr = w.optJSONArray("cw") ?: continue
                if (cwArr.length() == 0) continue
                val word = cwArr.optJSONObject(0)?.optString("w").orEmpty()
                if (word.isNotBlank()) sb.append(word)
            }
        }
        val sentence = sb.toString()
        if (sentence.isBlank()) return

        val isFinal = ls || type == "0"
        if (isFinal) {
            if (finalText.isNotEmpty() && !finalText.endsWith("\n")) finalText.append('\n')
            finalText.append(sentence)
            if (!sentence.endsWith("\n")) finalText.append('\n')
            partialBySeg.remove(segId)
            // 清掉其它中间段，避免重复
            partialBySeg.clear()
            onFinal(render())
        } else {
            partialBySeg[segId] = sentence
            onPartial(render())
        }
    }

    private fun render(): String {
        val head = finalText.toString()
        val tail = partialBySeg.values.joinToString("")
        return head + tail
    }

    private fun humanizeError(code: String, desc: String): String {
        val hint = when (code) {
            "35001", "100002" -> "鉴权失败，请检查 AppID / APIKey / APISecret 是否为「实时语音转写大模型」服务的密钥"
            "35002" -> "用量不足，请到控制台领取免费额度或购买套餐"
            "35004" -> "appId 不存在"
            "35010", "35017" -> "accessKeyId(APIKey) 不存在或与 appId 不匹配"
            "35014", "100012" -> "本机时间偏差过大，请校准手机时间"
            "35030" -> "签名重复：请保持 uuid 每次会话唯一"
            "35006" -> "并发路数已满"
            else -> ""
        }
        return if (hint.isEmpty()) "讯飞错误 $code: $desc" else "讯飞 $code · $hint\n$desc"
    }

    companion object {
        private const val TAG = "XfyunRtasr"

        /** 2025-09-04T15:38:07+0800 */
        fun utcNow(): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            // Z 输出 +0800；去掉冒号以匹配文档
            return fmt.format(Date()).replace(Regex("([+-]\\d{2})(\\d{2})"), "$1$2")
        }

        fun urlEncode(s: String): String =
            URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /**
         * 参数按 key 升序 → url(k)=url(v)&... → HmacSHA1(apiSecret) → Base64
         */
        fun sign(params: Map<String, String>, apiSecret: String): String {
            val base = params.toSortedMap()
                .entries
                .joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            val raw = mac.doFinal(base.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(raw, Base64.NO_WRAP)
        }
    }
}
