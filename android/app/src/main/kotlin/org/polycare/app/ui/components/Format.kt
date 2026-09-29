package org.polycare.app.ui.components

/** "just now", "5 min ago", "3 h ago", "2 days ago". Display only; never used for ordering. */
fun agoLabel(thenMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    return when {
        s < 45 -> "just now"
        s < 90 * 60 -> "${(s / 60).coerceAtLeast(1)} min ago"
        s < 36 * 3600 -> "${s / 3600} h ago"
        else -> "${s / 86400} days ago"
    }
}

/** 0 B, 812 B, 1.4 KB, 2.3 MB. */
fun bytesLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
