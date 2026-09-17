package com.eyewear.transcribe

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eyewear.transcribe.databinding.ActivityMainBinding
import com.eyewear.transcribe.record.RecordScheduler
import com.eyewear.transcribe.record.RecordingSession
import com.eyewear.transcribe.record.ScheduleReceiver
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            maybeHandleScheduleStart()
            refreshToggleUi()
        } else {
            Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener { ensurePermissionsThenToggle() }

        binding.switchSaveWav.setOnCheckedChangeListener { _, _ ->
            // 模式在开始录音时读取，这里只更新提示
            updateModeHint()
        }
        binding.switchCloud.setOnCheckedChangeListener { _, _ -> updateModeHint() }

        binding.switchSchedule.setOnCheckedChangeListener { _, checked ->
            if (checked && !RecordScheduler.canScheduleExact(this)) {
                Toast.makeText(this, "请允许「闹钟和提醒」权限后再启用定时", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
            }
            RecordScheduler.setEnabled(this, checked)
            updateScheduleLabel()
        }
        binding.tvStartTime.setOnClickListener { pickTime(isStart = true) }
        binding.tvEndTime.setOnClickListener { pickTime(isStart = false) }

        // 加载定时配置
        binding.switchSchedule.isChecked = RecordScheduler.isEnabled(this)
        updateScheduleLabel()
        updateModeHint()

        lifecycleScope.launch {
            RecordingSession.snap.collectLatest { s ->
                binding.tvStatus.text = s.status.ifEmpty { if (s.running) "正在听…" else getString(R.string.idle) }
                binding.tvChannel.text = "${getString(R.string.channel)}：${s.channel}"
                binding.levelBar.progress = (s.level01 * 100).toInt().coerceIn(0, 100)
                binding.tvTranscript.text = s.transcript
                if (!s.message.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, s.message, Toast.LENGTH_LONG).show()
                    RecordingSession.consumeMessage()
                }
                binding.btnToggle.text = getString(if (s.running) R.string.stop else R.string.start)
                if (s.running) {
                    binding.tvEngineHint.text = buildString {
                        append(if (binding.switchSaveWav.isChecked || true) "录音中" else "")
                        if (s.lastFile != null) append(" · 上次 ${String.format(Locale.US, "%.1fMB", s.lastFileSizeMb)}")
                    }
                } else if (s.lastFile != null) {
                    binding.tvEngineHint.text = "上次：${s.lastFile}（${String.format(Locale.US, "%.1f", s.lastFileSizeMb)}MB）"
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ScheduleReceiver.ACTION_SCHEDULE_START) {
            ensurePermissionsThenToggle(forceStart = true)
            RecordScheduler.rescheduleIfEnabled(this)
        }
    }

    private fun maybeHandleScheduleStart() {
        if (intent?.action == ScheduleReceiver.ACTION_SCHEDULE_START && !RecordingSession.isRunning()) {
            ensurePermissionsThenToggle(forceStart = true)
        }
    }

    private fun pickTime(isStart: Boolean) {
        val cur = if (isStart) RecordScheduler.getStart(this) else RecordScheduler.getEnd(this)
        TimePickerDialog(
            this,
            { _, h, m ->
                val (sh, sm) = RecordScheduler.getStart(this)
                val (eh, em) = RecordScheduler.getEnd(this)
                if (isStart) RecordScheduler.saveTimes(this, h, m, eh, em)
                else RecordScheduler.saveTimes(this, sh, sm, h, m)
                updateScheduleLabel()
            },
            cur.first, cur.second, true
        ).show()
    }

    private fun updateScheduleLabel() {
        val enabled = binding.switchSchedule.isChecked
        binding.tvScheduleHint.text = if (enabled) {
            "每日 ${RecordScheduler.label(this)} 自动录音 · 请保持眼镜已连接、系统「蓝牙设备录音」开启，并将本应用设为后台不限制"
        } else {
            "开启后可设置每日自动开始/结束时间"
        }
        binding.tvStartTime.text = "开始 " + String.format("%02d:%02d", RecordScheduler.getStart(this).first, RecordScheduler.getStart(this).second)
        binding.tvEndTime.text = "结束 " + String.format("%02d:%02d", RecordScheduler.getEnd(this).first, RecordScheduler.getEnd(this).second)
    }

    private fun updateModeHint() {
        val wav = if (binding.switchSaveWav.isChecked) "本地M4A：开" else "本地M4A：关"
        val cloud = if (binding.switchCloud.isChecked) "实时转写：开" else "实时转写：关"
        if (!RecordingSession.isRunning()) {
            binding.tvEngineHint.text = "$wav · $cloud"
        }
    }

    private fun refreshToggleUi() {
        binding.btnToggle.text = getString(
            if (RecordingSession.isRunning()) R.string.stop else R.string.start
        )
    }

    private fun ensurePermissionsThenToggle(forceStart: Boolean = false) {
        if (RecordingSession.isRunning() && !forceStart) {
            RecordingSession.stop(this)
            return
        }
        if (RecordingSession.isRunning() && forceStart) return

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
        if (missing.isEmpty()) {
            startSession()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startSession() {
        val ok = RecordingSession.start(
            this,
            saveFile = binding.switchSaveWav.isChecked,
            useCloud = binding.switchCloud.isChecked
        )
        if (!ok) {
            Toast.makeText(this, "启动失败，请检查眼镜连接与权限", Toast.LENGTH_LONG).show()
        }
    }
}
