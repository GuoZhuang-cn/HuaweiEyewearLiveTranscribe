package com.eyewear.transcribe.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono PCM from the Bluetooth HFP mic (Eyewear 2 / any BT headset)
 * via AudioManager.setCommunicationDevice + AudioRecord.
 * Requires system toggle 「蓝牙设备录音」 on Huawei phones.
 */
class BtMicRecorder(private val context: Context) {

    enum class State { IDLE, RECORDING, PAUSED_CALL, ERROR }

    data class LevelEvent(val rms01: Float)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var phoneCallback: Any? = null

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _channelLabel = MutableStateFlow("—")
    val channelLabel: StateFlow<String> = _channelLabel.asStateFlow()

    private val _levels = MutableSharedFlow<LevelEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val levels: SharedFlow<LevelEvent> = _levels.asSharedFlow()

    private val _pcm = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pcm: SharedFlow<ByteArray> = _pcm.asSharedFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var onPcm: ((ByteArray) -> Unit)? = null

    fun start(): Boolean {
        if (_state.value == State.RECORDING) return true
        _error.value = null

        val minBuf = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            _error.value = "AudioRecord.getMinBufferSize failed: $minBuf"
            _state.value = State.ERROR
            return false
        }

        val bufferSize = maxOf(minBuf, AudioConfig.FRAME_BYTES * AudioConfig.MIN_BUFFER_FRAMES)

        @SuppressLint("MissingPermission")
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (t: Throwable) {
            _error.value = "AudioRecord create failed: ${t.message}"
            _state.value = State.ERROR
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _error.value = "AudioRecord not initialized (check RECORD_AUDIO)"
            _state.value = State.ERROR
            return false
        }

        val routed = try {
            routeToBluetoothSco()
        } catch (t: Throwable) {
            android.util.Log.w("BtMicRecorder", "route BT failed: ${t.message}")
            false
        }
        _channelLabel.value = try {
            describeInput(routed)
        } catch (t: Throwable) {
            "未知通道（${t.message}）"
        }

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: SecurityException) {
            // MODIFY_AUDIO_SETTINGS may be restricted on some OEMs; capture can still work.
        } catch (_: Throwable) {
        }

        registerPhoneStateListener()

        captureJob = scope.launch {
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    _error.value = "AudioRecord.startRecording 未进入录音状态"
                    _state.value = State.ERROR
                    return@launch
                }
                val frame = ByteArray(AudioConfig.FRAME_BYTES)
                while (isActive) {
                    if (_state.value == State.PAUSED_CALL) {
                        Thread.sleep(50)
                        continue
                    }
                    val read = record.read(frame, 0, frame.size)
                    if (read <= 0) continue
                    val chunk = if (read == frame.size) frame.copyOf() else frame.copyOf(read)
                    _levels.tryEmit(LevelEvent(rms01(chunk)))
                    _pcm.tryEmit(chunk)
                    try {
                        onPcm?.invoke(chunk)
                    } catch (t: Throwable) {
                        android.util.Log.w("BtMicRecorder", "onPcm failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("BtMicRecorder", "capture loop crash", t)
                _error.value = "录音失败: ${t.message}"
                _state.value = State.ERROR
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
            }
        }

        _state.value = State.RECORDING
        return true
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        unregisterPhoneStateListener()
        clearCommunicationDevice()
        runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
        _state.value = State.IDLE
        _channelLabel.value = "—"
    }

    fun release() {
        stop()
        scope.cancel()
    }

    /** Prefer BT SCO mic; returns true if a BT communication device was selected. */
    private fun routeToBluetoothSco(): Boolean {
        val devices = audioManager.availableCommunicationDevices
        val bt = devices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_HEARING_AID
        } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        if (bt == null) return false
        val ok = runCatching { audioManager.setCommunicationDevice(bt) }.getOrDefault(false)
        return ok && bt.type != AudioDeviceInfo.TYPE_BUILTIN_MIC
    }

    private fun clearCommunicationDevice() {
        runCatching { audioManager.clearCommunicationDevice() }
    }

    private fun describeInput(routedToBt: Boolean): String {
        val current = audioManager.communicationDevice
        val name = current?.productName?.toString() ?: current?.type?.toString() ?: "unknown"
        val kind = when (current?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID -> "蓝牙耳机 Mic"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "手机麦"
            else -> name
        }
        return if (routedToBt) "$kind · $name" else "$kind（未选到蓝牙，请检查「蓝牙设备录音」）· $name"
    }

    private fun rms01(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            sum += (s * s).toLong()
            i += 2
        }
        val n = pcm.size / 2
        if (n == 0) return 0f
        val rms = sqrt(sum.toDouble() / n)
        // Speech typically 200–8000; map to 0..1 with soft clip
        return (rms / 4000.0).coerceIn(0.0, 1.0).toFloat()
    }

    private fun registerPhoneStateListener() {
        // Huawei / some OEMs throw SecurityException without READ_PHONE_STATE.
        // Call-pause is optional — never crash the capture path for it.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleCallState(state)
                }
                telephony.registerTelephonyCallback(context.mainExecutor, cb)
                phoneCallback = cb
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleCallState(state)
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneCallback = listener
            }
        } catch (e: SecurityException) {
            phoneCallback = null
            android.util.Log.w("BtMicRecorder", "phone-state listener unavailable: ${e.message}")
        } catch (t: Throwable) {
            phoneCallback = null
            android.util.Log.w("BtMicRecorder", "phone-state listener failed: ${t.message}")
        }
    }

    private fun unregisterPhoneStateListener() {
        val cb = phoneCallback ?: return
        phoneCallback = null
        runCatching {
            if (cb is TelephonyCallback) {
                telephony.unregisterTelephonyCallback(cb)
            } else {
                @Suppress("DEPRECATION")
                telephony.listen(cb as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    private fun handleCallState(state: Int) {
        val inCall = state == TelephonyManager.CALL_STATE_RINGING ||
            state == TelephonyManager.CALL_STATE_OFFHOOK
        if (_state.value == State.IDLE || _state.value == State.ERROR) return
        _state.value = if (inCall) State.PAUSED_CALL else State.RECORDING
    }

    companion object {
        fun hasBtCommunicationDevice(context: Context): Boolean {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.availableCommunicationDevices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }
    }
}
