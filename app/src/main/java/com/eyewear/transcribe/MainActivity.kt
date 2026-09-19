package com.eyewear.transcribe

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eyewear.transcribe.databinding.ActivityMainBinding
import com.eyewear.transcribe.record.RecordLog
import com.eyewear.transcribe.record.RecordScheduler
import com.eyewear.transcribe.record.RecordingSession
import com.eyewear.transcribe.record.ScheduleReceiver
import com.eyewear.transcribe.sync.NutstoreWebDav
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val dayChips = ArrayList<TextView>()
    private var daysMask = 0

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

        daysMask = RecordScheduler.getDaysMask(this)
        buildDayChips()

        binding.btnToggle.setOnClickListener { ensurePermissionsThenToggle() }
        binding.tvStartTime.setOnClickListener { pickTime(isStart = true) }
        binding.tvEndTime.setOnClickListener { pickTime(isStart = false) }
        binding.btnOpenLog.setOnClickListener {
            startActivity(android.content.Intent(this, LogActivity::class.java))
        }

        binding.switchSaveWav.setOnCheckedChangeListener { _, _ -> updateHints() }

        binding.switchSchedule.setOnCheckedChangeListener { _, checked ->
            if (checked && !RecordScheduler.canScheduleExact(this)) {
                Toast.makeText(this, "请允许「闹钟和提醒」权限", Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)) }
            }
            RecordScheduler.setEnabled(this, checked)
            updateScheduleUi()
        }

        binding.switchNutstore.setOnCheckedChangeListener { _, checked ->
            if (checked && !NutstoreWebDav.configured(this)) {
                Toast.makeText(this, "请先配置坚果云账号与应用密码", Toast.LENGTH_LONG).show()
                showNutstoreDialog()
            }
            NutstoreWebDav.setEnabled(this, checked && NutstoreWebDav.configured(this))
            binding.switchNutstore.isChecked = NutstoreWebDav.isEnabled(this)
            updateHints()
        }
        binding.btnNutstoreSettings.setOnClickListener { showNutstoreDialog() }

        binding.switchSchedule.isChecked = RecordScheduler.isEnabled(this)
        binding.switchNutstore.isChecked = NutstoreWebDav.isEnabled(this) && NutstoreWebDav.configured(this)
        if (NutstoreWebDav.isEnabled(this) && !NutstoreWebDav.configured(this)) {
            NutstoreWebDav.setEnabled(this, false)
        }
        updateScheduleUi()
        updateHints()

        lifecycleScope.launch {
            RecordingSession.snap.collectLatest { s ->
                binding.tvStatus.text = s.status.ifEmpty {
                    getString(if (s.running) R.string.listening else R.string.idle)
                }
                binding.tvChannel.text = "${getString(R.string.channel)}：${s.channel}"
                binding.levelBar.progress = (s.level01 * 100).toInt().coerceIn(0, 100)
                binding.tvSync.text = s.syncState
                binding.btnToggle.text = getString(if (s.running) R.string.stop else R.string.start)
                if (!s.message.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, s.message, Toast.LENGTH_LONG).show()
                    RecordingSession.consumeMessage()
                }
                if (!s.running && s.lastFile != null) {
                    binding.tvEngineHint.text = String.format(
                        Locale.CHINA,
                        "上次文件：%s（%.1fMB）",
                        s.lastFile,
                        s.lastFileSizeMb
                    )
                } else {
                    updateHints()
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

    private fun buildDayChips() {
        binding.rowDays.removeAllViews()
        dayChips.clear()
        val pad = (8 * resources.displayMetrics.density).toInt()
        for (dow in 1..7) {
            val tv = TextView(this).apply {
                text = RecordScheduler.DAY_LABELS[dow - 1]
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(pad, pad, pad, pad)
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.marginEnd = pad / 2
                layoutParams = lp
                setOnClickListener {
                    val bit = 1 shl (dow - 1)
                    daysMask = daysMask xor bit
                    if (daysMask == 0) {
                        daysMask = bit
                        Toast.makeText(context, "至少选择一天", Toast.LENGTH_SHORT).show()
                    }
                    persistDays()
                    styleChips()
                    updateScheduleUi()
                }
            }
            dayChips.add(tv)
            binding.rowDays.addView(tv)
        }
        styleChips()
    }

    private fun styleChips() {
        dayChips.forEachIndexed { i, tv ->
            val on = (daysMask and (1 shl i)) != 0
            tv.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (on) R.color.chip_on_text else R.color.text_secondary
                )
            )
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun persistDays() {
        val (sh, sm) = RecordScheduler.getStart(this)
        val (eh, em) = RecordScheduler.getEnd(this)
        RecordScheduler.saveTimes(this, sh, sm, eh, em, daysMask)
    }

    private fun pickTime(isStart: Boolean) {
        val cur = if (isStart) RecordScheduler.getStart(this) else RecordScheduler.getEnd(this)
        TimePickerDialog(
            this,
            { _, h, m ->
                val (sh, sm) = RecordScheduler.getStart(this)
                val (eh, em) = RecordScheduler.getEnd(this)
                if (isStart) RecordScheduler.saveTimes(this, h, m, eh, em, daysMask)
                else RecordScheduler.saveTimes(this, sh, sm, h, m, daysMask)
                updateScheduleUi()
            },
            cur.first, cur.second, true
        ).show()
    }

    private fun updateScheduleUi() {
        val (sh, sm) = RecordScheduler.getStart(this)
        val (eh, em) = RecordScheduler.getEnd(this)
        binding.tvStartTime.text = String.format(Locale.CHINA, "开始 %02d:%02d", sh, sm)
        binding.tvEndTime.text = String.format(Locale.CHINA, "结束 %02d:%02d", eh, em)
        daysMask = RecordScheduler.getDaysMask(this)
        styleChips()
        val enabled = binding.switchSchedule.isChecked
        binding.tvScheduleHint.text = if (enabled) {
            "已启用 · ${RecordScheduler.label(this)}\n请保持眼镜连接，并将本应用设为后台无限制"
        } else {
            "开启开关后，按所选星期与北京时间自动开录/停录"
        }
    }

    private fun updateHints() {
        if (RecordingSession.isRunning()) return
        val save = if (binding.switchSaveWav.isChecked) "M4A开" else "M4A关"
        val sync = if (NutstoreWebDav.isEnabled(this)) "坚果云开" else "坚果云关"
        binding.tvEngineHint.text = "$save · $sync"
        binding.tvNutstoreHint.text =
            if (NutstoreWebDav.configured(this)) NutstoreWebDav.settingsLabel(this)
            else "需填写邮箱 + 应用密码（坚果云 → 账户信息 → 第三方应用管理）"
    }

    private fun showNutstoreDialog() {
        val density = resources.displayMetrics.density
        fun field(hint: String, text: String, password: Boolean = false): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(text)
                inputType = if (password)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT
                textSize = 14f
            }
        }
        val url = field("WebDAV 地址", NutstoreWebDav.baseUrl(this))
        val user = field("用户名（邮箱）", NutstoreWebDav.user(this))
        val pass = field("应用密码（非登录密码）", NutstoreWebDav.pass(this), password = true)
        val dir = field("远程文件夹", NutstoreWebDav.dir(this))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(url)
            addView(user)
            addView(pass)
            addView(dir)
        }

        AlertDialog.Builder(this)
            .setTitle("坚果云 WebDAV")
            .setMessage("地址默认 https://dav.jianguoyun.com/dav/\n应用密码在坚果云网页端生成")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                NutstoreWebDav.save(
                    this,
                    url.text.toString(),
                    user.text.toString(),
                    pass.text.toString(),
                    dir.text.toString()
                )
                if (NutstoreWebDav.configured(this)) {
                    NutstoreWebDav.setEnabled(this, true)
                    binding.switchNutstore.isChecked = true
                    Toast.makeText(this, "已保存坚果云配置", Toast.LENGTH_SHORT).show()
                    RecordLog.append(this, "坚果云配置已更新", NutstoreWebDav.settingsLabel(this))
                } else {
                    binding.switchNutstore.isChecked = false
                    NutstoreWebDav.setEnabled(this, false)
                    Toast.makeText(this, "用户名或应用密码为空", Toast.LENGTH_SHORT).show()
                }
                updateHints()
            }
            .setNegativeButton("取消", null)
            .show()
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
        if (missing.isEmpty()) {
            startSession(trigger)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startSession(trigger: String) {
        val ok = RecordingSession.start(
            this,
            saveFile = binding.switchSaveWav.isChecked,
            trigger = trigger
        )
        if (!ok) {
            Toast.makeText(this, "启动失败，请检查眼镜连接与权限", Toast.LENGTH_LONG).show()
        }
    }
}
