package com.eyewear.transcribe

import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.eyewear.transcribe.databinding.ActivitySettingsBinding
import com.eyewear.transcribe.record.RecordLog
import com.eyewear.transcribe.record.RecordScheduler
import com.eyewear.transcribe.record.RecordingSession
import com.eyewear.transcribe.sync.NutstoreWebDav
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val dayChips = ArrayList<TextView>()
    private var daysMask = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        daysMask = RecordScheduler.getDaysMask(this)
        buildDayChips()

        binding.btnBack.setOnClickListener { finish() }

        binding.switchSaveWav.setOnCheckedChangeListener { _, _ -> persistModes() }

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
            updateNutstoreHint()
        }

        binding.btnNutstoreSettings.setOnClickListener { showNutstoreDialog() }
        binding.tvStartTime.setOnClickListener { pickTime(isStart = true) }
        binding.tvEndTime.setOnClickListener { pickTime(isStart = false) }

        // restore UI from prefs
        val sp = getSharedPreferences("app_modes", MODE_PRIVATE)
        binding.switchSaveWav.isChecked = sp.getBoolean("save_m4a", true)
        binding.switchSchedule.isChecked = RecordScheduler.isEnabled(this)
        binding.switchNutstore.isChecked = NutstoreWebDav.isEnabled(this) && NutstoreWebDav.configured(this)
        if (NutstoreWebDav.isEnabled(this) && !NutstoreWebDav.configured(this)) {
            NutstoreWebDav.setEnabled(this, false)
        }
        updateScheduleUi()
        updateNutstoreHint()
    }

    private fun persistModes() {
        getSharedPreferences("app_modes", MODE_PRIVATE)
            .edit()
            .putBoolean("save_m4a", binding.switchSaveWav.isChecked)
            .apply()
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
                ContextCompat.getColor(this, if (on) R.color.chip_on_text else R.color.text_secondary)
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
        binding.tvScheduleHint.text = if (binding.switchSchedule.isChecked) {
            "已启用 · ${RecordScheduler.label(this)}\n请将本应用设为后台无限制，并保持眼镜连接"
        } else {
            "开启后按所选星期与北京时间自动开录 / 停录"
        }
    }

    private fun updateNutstoreHint() {
        binding.tvNutstoreHint.text =
            if (NutstoreWebDav.configured(this)) NutstoreWebDav.settingsLabel(this)
            else "坚果云 → 账户信息 → 第三方应用管理，生成应用密码后填写"
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
            addView(url); addView(user); addView(pass); addView(dir)
        }
        AlertDialog.Builder(this)
            .setTitle("坚果云 WebDAV")
            .setMessage("默认地址 https://dav.jianguoyun.com/dav/")
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
                    RecordLog.append(this, "坚果云配置已更新", NutstoreWebDav.settingsLabel(this))
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                } else {
                    NutstoreWebDav.setEnabled(this, false)
                    binding.switchNutstore.isChecked = false
                    Toast.makeText(this, "用户名或应用密码为空", Toast.LENGTH_SHORT).show()
                }
                updateNutstoreHint()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        fun saveM4aEnabled(activity: AppCompatActivity): Boolean {
            return activity.getSharedPreferences("app_modes", MODE_PRIVATE)
                .getBoolean("save_m4a", true)
        }

        fun isRunning() = RecordingSession.isRunning()
    }
}
