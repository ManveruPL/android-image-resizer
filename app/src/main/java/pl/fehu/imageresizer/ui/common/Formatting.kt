package pl.fehu.imageresizer.ui.common

import kotlin.math.ln
import kotlin.math.pow

/** "1.2 MB" style formatting; null/negative sizes show as "?". */
fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "?"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    val exponent = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exponent)
    return "%.1f %s".format(value, units[exponent - 1])
}

/** "1m 05s" / "12s" style elapsed-time formatting. */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds.toString().padStart(2, '0')}s" else "${seconds}s"
}
