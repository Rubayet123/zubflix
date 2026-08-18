package com.example.zubflix

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.R
import com.example.zubflix.util.DebugLogger

class DebugLogsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_logs)

        val tvLogs = findViewById<TextView>(R.id.tv_logs)
        tvLogs.text = DebugLogger.getLogs().joinToString("\n")

        findViewById<Button>(R.id.btn_copy).setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Debug Logs", tvLogs.text.toString())
            clipboard.setPrimaryClip(clip)
            android.widget.Toast.makeText(this, "Logs copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            DebugLogger.clearLogs()
            tvLogs.text = ""
        }
    }
}
