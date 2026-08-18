package com.example.zubflix.util

object DebugLogger {
    private val logs = mutableListOf<String>()
    var debugEnabled = false

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        if (debugEnabled) {
            logs.add("D/$tag: $message")
        }
    }

    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        if (debugEnabled) {
            logs.add("W/$tag: $message")
        }
    }

    fun e(tag: String, message: String) {
        android.util.Log.e(tag, message)
        if (debugEnabled) {
            logs.add("E/$tag: $message")
        }
    }

    fun getLogs(): List<String> {
        return logs.toList()
    }

    fun clearLogs() {
        logs.clear()
    }
}
