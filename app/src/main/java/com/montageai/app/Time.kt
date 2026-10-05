package com.montageai.app

import java.util.Locale

/** Parses "35", "35.5", "1:05" or "01:05.5" (Arabic digits and separators accepted). Null if invalid. */
fun parseTime(text: String): Double? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val norm = buildString {
        for (ch in trimmed) {
            when {
                ch in '٠'..'٩' -> append('0' + (ch - '٠'))
                ch == '٫' || ch == '،' || ch == ',' -> append('.')
                ch == '٬' -> Unit
                else -> append(ch)
            }
        }
    }
    val parts = norm.split(':')
    val value = try {
        when (parts.size) {
            1 -> parts[0].toDouble()
            2 -> parts[0].toInt() * 60 + parts[1].toDouble()
            3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toDouble()
            else -> null
        }
    } catch (e: NumberFormatException) {
        null
    }
    return value?.takeIf { it >= 0.0 }
}

fun formatTime(sec: Double): String {
    val m = (sec / 60).toInt()
    val s = sec - m * 60
    return String.format(Locale.US, "%d:%04.1f", m, s)
}
