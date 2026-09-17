package com.eyewear.transcribe.asr

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generic streaming ASR over WebSocket.
 *
 * Wire 16 kHz mono PCM frames to your provider, parse partial/final JSON.
 * Implement [buildStartPayload] + [parseServerMessage] for 讯飞 / 阿里云 / 华为云 etc.
 *
 * Demo uses a simple JSON protocol:
 *   client → {"type":"start","sample_rate":16000,"format":"pcm"}
 *   client → binary PCM frames
 *   client → {"type":"stop"}
 *   server → {"type":"partial"|"final"|"error","text":"..."}
 */
class WebSocketAsrEngine(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val startPayload: JSONObject = JSONObject()
        .put("type", "start")
        .put("sample_rate", 16000)
        .put("format", "pcm")
        .put("channel", 1),
    private val stopPayload: JSONObject = JSONObject().put("type", "stop"),
    private val parse: (JSONObject) -> Pair<String, String>? = { json ->
        when (json.optString("type")) {
            "partial", "result" -> "partial" to json.optString("text")
            "final" -> "final" to json.optString("text")
            "error" -> "error" to json.optString("message", "ASR error")
            else -> null
        }
    }
) : AsrEngine {

    override val name: String = "Cloud WebSocket"

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private val open = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    override fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (started.getAndSet(true)) return
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }

        ws = client.newWebSocket(builder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open.set(true)
                webSocket.send(startPayload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    val (kind, msg) = parse(json) ?: return@runCatching
                    when (kind) {
                        "partial" -> if (msg.isNotBlank()) onPartial(msg)
                        "final" -> if (msg.isNotBlank()) onFinal(msg)
                        "error" -> onError(msg)
                    }
                }.onFailure { onError("parse: ${it.message}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open.set(false)
                started.set(false)
                onError(t.message ?: "WebSocket failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open.set(false)
                started.set(false)
            }
        })
    }

    override fun feed(pcm: ByteArray) {
        if (!open.get()) return
        ws?.send(pcm.toByteString())
    }

    override fun stop() {
        if (open.get()) {
            runCatching { ws?.send(stopPayload.toString()) }
            ws?.close(1000, "client stop")
        }
        ws = null
        open.set(false)
        started.set(false)
    }
}

/**
 * Factory helpers — fill in your provider URL + auth.
 * Keep secrets out of VCS; use BuildConfig / local.properties in production.
 */
object AsrEngines {
    fun debug(): AsrEngine = DebugAsrEngine()

    /** 讯飞实时语音转写大模型（手机直连，密钥来自 BuildConfig） */
    fun xfyunRtasr(appId: String, apiKey: String, apiSecret: String): AsrEngine =
        XfyunRtasrEngine(appId = appId, apiKey = apiKey, apiSecret = apiSecret)

    /**
     * Example placeholder. Replace [url] with your gateway (e.g. a small relay
     * that holds 讯飞/阿里 credentials server-side — recommended for mobile).
     */
    fun cloudGateway(
        gatewayWss: String,
        token: String? = null
    ): AsrEngine {
        val headers = buildMap {
            token?.let { put("Authorization", "Bearer $it") }
        }
        return WebSocketAsrEngine(url = gatewayWss, headers = headers)
    }
}
