package com.eyewear.transcribe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eyewear.transcribe.asr.AsrEngines
import com.eyewear.transcribe.audio.AacSaver
import com.eyewear.transcribe.audio.BtMicRecorder
import com.eyewear.transcribe.databinding.ActivityMainBinding
import com.eyewear.transcribe.service.RecordForegroundService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var recorder: BtMicRecorder
    private val audioSaver = AacSaver(this)

    private var asr: com.eyewear.transcribe.asr.AsrEngine? = null
    private var running = false
    private val transcript = StringBuilder()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            toggle()
        } else {
            Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        recorder = BtMicRecorder(this)

        binding.btnToggle.setOnClickListener { ensurePermissionsThenToggle() }
        binding.switchCloud.setOnCheckedChangeListener { _, checked ->
            binding.tvEngineHint.text = engineHint(checked)
        }
        binding.switchSaveWav.setOnCheckedChangeListener { _, _ ->
            binding.tvEngineHint.text = engineHint(binding.switchCloud.isChecked)
        }
        // 默认：本地 WAV 开、实时转写关（更稳）
        binding.switchCloud.isChecked = false
        binding.switchSaveWav.isChecked = true
        binding.tvEngineHint.text = engineHint(false)

        lifecycleScope.launch {
            recorder.state.collectLatest { st ->
                binding.tvStatus.text = when (st) {
                    BtMicRecorder.State.IDLE -> getString(R.string.idle)
                    BtMicRecorder.State.RECORDING -> getString(R.string.listening)
                    BtMicRecorder.State.PAUSED_CALL -> getString(R.string.paused_call)
                    BtMicRecorder.State.ERROR -> recorder.error.value ?: "错误"
                }
            }
        }
        lifecycleScope.launch {
            recorder.channelLabel.collectLatest { binding.tvChannel.text = "${getString(R.string.channel)}：$it" }
        }
        lifecycleScope.launch {
            recorder.levels.collectLatest { binding.levelBar.progress = (it.rms01 * 100).toInt() }
        }
        lifecycleScope.launch {
            recorder.error.collectLatest { err ->
                if (!err.isNullOrBlank()) Toast.makeText(this@MainActivity, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        if (running) stopAll()
        recorder.release()
        super.onDestroy()
    }

    private fun ensurePermissionsThenToggle() {
        if (running) {
            stopAll()
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) toggle() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun toggle() {
        try {
            if (!BtMicRecorder.hasBtCommunicationDevice(this)) {
                Toast.makeText(
                    this,
                    "未发现蓝牙耳机设备。请连接智能眼镜后再试。",
                    Toast.LENGTH_LONG
                ).show()
            }

            val saveWav = binding.switchSaveWav.isChecked
            val useCloud = binding.switchCloud.isChecked

            if (!saveWav && !useCloud) {
                Toast.makeText(this, "请至少开启「本地录音」或「实时转写」", Toast.LENGTH_SHORT).show()
                return
            }

            asr = if (useCloud) pickCloudEngine() else null

            val ok = recorder.start()
            if (!ok) {
                Toast.makeText(this, recorder.error.value ?: "录音启动失败", Toast.LENGTH_LONG).show()
                return
            }

            if (saveWav) {
                if (!audioSaver.start()) {
                    Toast.makeText(this, "音频文件创建失败", Toast.LENGTH_LONG).show()
                }
            }

            asr?.let { engine ->
                engine.start(
                    onPartial = { text -> runOnUiThread { appendTranscript(text, isFinal = false) } },
                    onFinal = { text -> runOnUiThread { appendTranscript(text, isFinal = true) } },
                    onError = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
                )
            }

            recorder.onPcm = { pcm ->
                if (saveWav) audioSaver.write(pcm)
                asr?.feed(pcm)
            }

            try {
                RecordForegroundService.start(this)
            } catch (t: Throwable) {
                android.util.Log.w("Eyewear", "foreground service skipped: ${t.message}")
            }
            running = true
            binding.btnToggle.text = getString(R.string.stop)
            binding.tvEngineHint.text = buildString {
                if (saveWav) append("本地 M4A 录音中")
                if (saveWav && useCloud) append(" · ")
                if (useCloud) append("讯飞实时转写中")
            }
        } catch (t: Throwable) {
            android.util.Log.e("Eyewear", "toggle failed", t)
            Toast.makeText(this, "启动失败: ${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG).show()
            runCatching { stopAll() }
        }
    }

    private fun xfyunConfigured(): Boolean =
        BuildConfig.XFYUN_APP_ID.isNotBlank() &&
            BuildConfig.XFYUN_API_KEY.isNotBlank() &&
            BuildConfig.XFYUN_API_SECRET.isNotBlank()

    private fun engineHint(cloud: Boolean): String {
        val wav = if (binding.switchSaveWav.isChecked) "本地WAV：开" else "本地WAV：关"
        val asrPart = when {
            !cloud -> "实时转写：关"
            xfyunConfigured() -> "实时转写：讯飞大模型"
            else -> "实时转写：未配密钥"
        }
        return "$wav · $asrPart"
    }

    private fun pickCloudEngine(): com.eyewear.transcribe.asr.AsrEngine {
        if (!xfyunConfigured()) {
            Toast.makeText(this, "未配置讯飞密钥，仅本地录音", Toast.LENGTH_LONG).show()
            return AsrEngines.debug()
        }
        return AsrEngines.xfyunRtasr(
            BuildConfig.XFYUN_APP_ID,
            BuildConfig.XFYUN_API_KEY,
            BuildConfig.XFYUN_API_SECRET
        )
    }

    private fun stopAll() {
        recorder.onPcm = null
        asr?.stop()
        asr = null
        recorder.stop()
        val audioFile = audioSaver.stop()
        RecordForegroundService.stop(this)
        running = false
        binding.btnToggle.text = getString(R.string.start)
        if (audioFile != null) {
            val mb = audioFile.length() / 1024.0 / 1024.0
            val path = audioFile.absolutePath
            binding.tvEngineHint.text = getString(R.string.wav_saved, path, String.format("%.1fMB", mb))
            Toast.makeText(this, "M4A 已保存 ${String.format("%.1f", mb)}MB", Toast.LENGTH_LONG).show()
        } else {
            binding.tvEngineHint.text = engineHint(binding.switchCloud.isChecked)
        }
    }

    private fun appendTranscript(text: String, isFinal: Boolean) {
        if (text.isBlank()) return
        if (isFinal) {
            if (transcript.isNotEmpty() && !transcript.endsWith("\n")) transcript.append('\n')
            transcript.append(text)
            if (!text.endsWith("\n")) transcript.append('\n')
        } else {
            val lastNl = transcript.lastIndexOf('\n')
            val head = if (lastNl >= 0) transcript.substring(0, lastNl + 1) else ""
            val shown = head + text
            binding.tvTranscript.text = shown
            return
        }
        binding.tvTranscript.text = transcript.toString()
    }
}
