package com.lite.streamview.util

import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Thread-safe real-time logger for on-screen console and debugging.
 */
object AppLogger {
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val history = Collections.synchronizedList(mutableListOf<String>())
    @Volatile
    private var listener: ((String) -> Unit)? = null

    fun setListener(listener: ((String) -> Unit)?) {
        this.listener = listener
    }

    fun log(tag: String, message: String) {
        val time = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "[$time][$tag] $message"
        history.add(line)
        if (history.size > 1000) {
            history.removeAt(0)
        }
        listener?.invoke(line)
    }

    fun getHistory(): String = synchronized(history) {
        history.joinToString("\n")
    }

    fun clear() {
        history.clear()
        listener?.invoke("__CLEAR__")
    }
}
