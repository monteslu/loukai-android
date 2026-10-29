package com.loukai.player

import org.json.JSONObject

/** Word timings in kara atoms aren't reliable, so like Loukai we only use line timing. */
class LyricLine(
    val start: Double,
    val end: Double,
    val text: String,
    val singer: String?,
) {
    /** Backup lines draw at the bottom and never drive the progress bars (same as Loukai). */
    val isBackup: Boolean get() = singer?.startsWith("backup") == true

    /** backup:PA lines bring the vocal stem back in even when vocals are off. */
    val isPunchthrough: Boolean get() = singer == "backup:PA"
}

object Kara {
    /** Parse the kara atom JSON into enabled, non-empty lines sorted by start time. */
    fun parse(json: String): List<LyricLine> {
        val lines = runCatching { JSONObject(json).optJSONArray("lines") }.getOrNull() ?: return emptyList()
        val out = ArrayList<LyricLine>()
        for (i in 0 until lines.length()) {
            val o = lines.optJSONObject(i) ?: continue
            if (o.optBoolean("disabled", false)) continue
            val text = o.optString("text").trim()
            if (text.isEmpty()) continue
            val start = o.optDouble("start", 0.0)
            val end = o.optDouble("end", start + 3)
            val singer = o.optString("singer").takeIf { it.isNotEmpty() }
                ?: if (o.optBoolean("backup", false)) "backup" else null
            out += LyricLine(start, end, text, singer)
        }
        out.sortBy { it.start }
        return out
    }
}
