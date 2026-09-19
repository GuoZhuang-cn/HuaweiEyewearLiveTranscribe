package com.eyewear.transcribe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eyewear.transcribe.databinding.ActivityMainBinding
import com.eyewear.transcribe.record.RecordScheduler
import com.eyewear.transcribe.record.RecordingSession
import com.eyewear.transcribe.record.ScheduleReceiver
import com.eyewear.transcribe.sync.NutstoreWebDav
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            maybeHandleScheduleStart()
        } else {
            Toast.makeText(this, "需要麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener { ensurePermissionsThenToggle() }
        binding.btnOpenLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        binding.btnOpenSettings.setOnClickListener { openSettings() }
        binding.btnSettings.setOnClickListener { openSettings() }

        lifecycleScope.launch {
            RecordingSession.snap.collectLatest { s ->
                binding.tvStatus.text = s.status.ifEmpty {
                    getString(if (s.running) R.string.listening else R.string.idle)
                }
                binding.tvChannel.text = "输入通道：${s.channel}"
                binding.levelBar.progress = (s.level01 * 100).toInt().coerceIn(0, 100)
                binding.btnToggle.text = getString(if (s.running) R.string.stop else R.string.start)

                if (!s.message.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, s.message, Toast.LENGTH_LONG).show()
                    RecordingSession.consumeMessage()
                }

                renderStats(s)
            }
        }

        renderStats(RecordingSession.snap.value)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        renderStats(RecordingSession.snap.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun saveM4a(): Boolean {
        return getSharedPreferences("app_modes", MODE_PRIVATE).getBoolean("save_m4a", true)
    }

    private fun renderStats(s: RecordingSession.Snapshot) {
        binding.tvStatM4a.text = if (saveM4a()) "开" else "关"
        binding.tvStatSync.text = if (NutstoreWebDav.isEnabled(this) && NutstoreWebDav.configured(this)) "开" else "关"
        binding.tvStatSchedule.text = if (RecordScheduler.isEnabled(this)) "开" else "关"
        binding.tvStatScheduleLabel.text =
            if (RecordScheduler.isEnabled(this)) "定时录音"
            else "定时未启用"

        if (s.lastFile != null) {
            binding.tvStatLast.text = String.format(Locale.CHINA, "%.1fM", s.lastFileSizeMb)
            binding.tvEngineHint.text = "上次保存：${String.format(Locale.CHINA, "%.1f", s.lastFileSizeMb)}MB"
            binding.tvLastPath.text = s.lastFile
        } else {
            binding.tvStatLast.text = "—"
            binding.tvEngineHint.text = if (s.running) "录音中…" else "录音后显示文件信息"
            binding.tvLastPath.text = ""
        }
        binding.tvSync.text = s.syncState
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ScheduleReceiver.ACTION_SCHEDULE_START) {
            ensurePermissionsThenToggle(forceStart = true, trigger = "定时")
            RecordScheduler.rescheduleIfEnabled(this)
        }
    }

    private fun maybeHandleScheduleStart() {
        if (intent?.action == ScheduleReceiver.ACTION_SCHEDULE_START && !RecordingSession.isRunning()) {
            ensurePermissionsThenToggle(forceStart = true, trigger = "定时")
        }
    }

    private fun ensurePermissionsThenToggle(forceStart: Boolean = false, trigger: String = "手动") {
        if (RecordingSession.isRunning() && !forceStart) {
            RecordingSession.stop(this, trigger)
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
        if (missing.isEmpty()) startSession(trigger)
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startSession(trigger: String) {
        val ok = RecordingSession.start(
            this,
            saveFile = saveM4a(),
            trigger = trigger
        )
        if (!ok) {
            Toast.makeText(this, "启动失败，请检查眼镜连接与权限", Toast.LENGTH_LONG).show()
        }
    }
}
