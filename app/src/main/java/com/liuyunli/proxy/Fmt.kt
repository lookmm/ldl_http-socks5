package com.liuyunli.proxy

/** 00:00 或 1:02:03。 */
internal fun fmtDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

/** B / KB / MB / GB，保留一位小数。 */
internal fun fmtRate(bytesPerSec: Long): String = fmtBytes(bytesPerSec) + "/s"

internal fun fmtBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

/** 紧凑格式：1.5K / 4.2M / 512B，perSec 时追加 /s。用于一行放四格的窄空间。 */
internal fun fmtTiny(bytes: Long, perSec: Boolean = false): String {
    val n = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024L * 1024 -> String.format("%.1fK", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1fM", bytes / 1048576.0)
        else -> String.format("%.2fG", bytes / 1073741824.0)
    }
    return if (perSec) "$n/s" else n
}
