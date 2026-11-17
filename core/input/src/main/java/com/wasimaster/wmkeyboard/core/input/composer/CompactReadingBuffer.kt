package com.wasimaster.wmkeyboard.core.input.composer

/** Inline constraints retain the original taps so backspace can undo a reading pick. */
internal object CompactReadingBuffer {
    private const val OPEN = '\uF000'
    private const val SPLIT = '\uF001'
    private const val CLOSE = '\uF002'
    data class Part(val start: Int, val end: Int, val raw: String, val reading: String? = null)

    fun parts(buffer: String): List<Part> = buildList {
        var at = 0
        while (at < buffer.length) {
            val open = buffer.indexOf(OPEN, at)
            if (open < 0) { add(Part(at, buffer.length, buffer.substring(at))); break }
            if (open > at) add(Part(at, open, buffer.substring(at, open)))
            val split = buffer.indexOf(SPLIT, open + 1)
            val close = if (split >= 0) buffer.indexOf(CLOSE, split + 1) else -1
            if (close < 0) { add(Part(open, buffer.length, buffer.substring(open))); break }
            add(Part(open, close + 1, buffer.substring(open + 1, split), buffer.substring(split + 1, close)))
            at = close + 1
        }
    }

    fun selected(raw: String, reading: String): String = "$OPEN$raw$SPLIT$reading$CLOSE"
    fun literal(buffer: String): String = parts(buffer).joinToString("") { it.reading ?: CompactPinyin.literal(it.raw) }
    fun backspace(buffer: String): String {
        val last = parts(buffer).lastOrNull() ?: return buffer
        return if (last.reading != null) buffer.take(last.start) + last.raw else buffer.dropLast(1)
    }

    fun backspaceSyllable(buffer: String): String {
        val text = buffer.trimEnd('\'')
        val last = parts(text).lastOrNull() ?: return ""
        return if (last.reading != null) text.take(last.start)
            else text.take(last.start) + DoublePinyin.backspaceSyllable(last.raw)
    }

    fun graph(buffer: String, plain: (String) -> Array<List<CompactPinyin.Edge>>): Array<List<CompactPinyin.Edge>> {
        val out = Array(buffer.length + 1) { emptyList<CompactPinyin.Edge>() }
        for (part in parts(buffer)) {
            if (part.reading != null) {
                out[part.start] = listOf(CompactPinyin.Edge(part.end, part.reading, false))
            } else {
                val graph = plain(part.raw)
                for (node in part.raw.indices) out[part.start + node] = graph[node].map { it.copy(to = it.to + part.start) }
            }
        }
        return out
    }
}
