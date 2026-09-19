package com.eyewear.transcribe

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eyewear.transcribe.databinding.ActivityLogBinding
import com.eyewear.transcribe.record.RecordLog
import java.io.File

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setBackgroundColor(getColor(R.color.background))

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { render() }
        binding.btnClear.setOnClickListener {
            try {
                File(RecordLog.path(this)).delete()
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
                render()
            } catch (t: Throwable) {
                Toast.makeText(this, "清空失败: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
        render()
    }

    private fun render() {
        binding.tvLogPath.text = "路径：" + RecordLog.path(this)
        binding.tvLogContent.text = RecordLog.readRecent(this, maxLines = 200)
    }
}
