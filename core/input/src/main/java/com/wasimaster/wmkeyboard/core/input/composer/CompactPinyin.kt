package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Pure keystroke-to-syllable adapter for the shipped 5/5/4 Compact grid.
 *
 * A private-use character records one ambiguous tap; Latin letters remain
 * exact (popup picks, pasted readings). Thus deletion and prefix consumption
 * still count one UTF-16 character per key, without a mutable side channel.
 * Match the finite syllable inventory, never the Cartesian product of letters.
 */
internal object CompactPinyin {
    val defaultGroups: List<String> = listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")
    val groups = defaultGroups
    val supportedGroups = defaultGroups + listOf("tu", "yi", "lm")
    private const val FIRST = '\uE000'

    fun key(text: String, letters: String?): String {
        if (text.length != 1 || letters == null) return text
        val group = letters.lowercase()
        val index = supportedGroups.indexOfFirst { it.length==group.length && it.all { c -> c in group } }
        return if (index >= 0 && group.length > 1 && text[0].lowercaseChar() in group) {
            (FIRST + index).toString()
        } else {
            text
        }
    }

    /** Convenience for fixtures: the Compact taps spelling a full reading. */
    fun encode(reading: String, activeGroups: List<String> = defaultGroups): String = buildString {
        for (c in reading.lowercase()) {
            val group = activeGroups.firstOrNull { c in it }
            append(key(c.toString(), group))
        }
    }

    fun letters(c: Char): String = supportedGroups.getOrNull(c - FIRST) ?: c.lowercaseChar().toString()

    /** Safe, readable fallback even before the Chinese pack has loaded. */
    fun literal(buffer: String): String = buffer.map { letters(it).first() }.joinToString("")

    data class Edge(val to: Int, val reading: String, val abbreviated: Boolean, val cost: Double = 0.0)

    /** Nodes are original key positions, including apostrophes. */
    fun graph(buffer: String, inventory: Set<String>, jianpin: Boolean): Array<List<Edge>> {
        val out = Array(buffer.length + 1) { mutableListOf<Edge>() }
        val lastKey = buffer.indexOfLast { it != '\'' } + 1
        val starts = IntArray(buffer.length + 1) { it }
        for (i in buffer.indices.reversed()) {
            if (buffer[i] == '\'') starts[i] = starts[i + 1]
        }
        val syllables = inventory.filter { it.isNotEmpty() && it.length <= 6 && it.all { c -> c in 'a'..'z' } }.sorted()
        fun matches(start: Int, spelling: String): Boolean =
            start + spelling.length <= buffer.length && spelling.indices.all { i ->
                spelling[i] in letters(buffer[start + i])
            }
        for (from in buffer.indices) {
            val start = starts[from]
            if (start == buffer.length) continue
            fun add(length: Int, syllable: String, abbreviated: Boolean) {
                var to = start + length
                // A trailing separator belongs to the reading it terminates.
                if (to >= lastKey) to = buffer.length
                out[from].add(Edge(to, syllable, abbreviated))
            }
            for (syllable in syllables) {
                if (matches(start, syllable)) add(syllable.length, syllable, false)
                // An unfinished final syllable still offers its word (niha →
                // nihao), without inventing input or crossing a separator.
                val tail = buffer.length - start
                if (lastKey == buffer.length && tail in (if (jianpin) 2 else 1) until syllable.length &&
                    matches(start, syllable.take(tail))
                ) add(tail, syllable, true)
                if (!jianpin) continue
                if (syllable.length > 1 && matches(start, syllable.take(1))) add(1, syllable, true)
                if (syllable.length > 2 && syllable.take(2) in DIGRAPHS && matches(start, syllable.take(2))) {
                    add(2, syllable, true)
                }
            }
        }
        // Keep all complete splits, but not an abbreviated prefix which
        // strands keys that another split can read (x + i'an). With an invalid
        // tail, retain the longest readable prefix so it can still be committed.
        val reachable = BooleanArray(out.size)
        reachable[0] = true
        for (node in buffer.indices) {
            if (reachable[node]) for (edge in out[node]) reachable[edge.to] = true
        }
        val end = reachable.indexOfLast { it }
        val finishes = BooleanArray(out.size)
        finishes[end] = true
        for (node in end - 1 downTo 0) finishes[node] = out[node].any { finishes[it.to] }
        return Array(out.size) { node -> out[node].filter { finishes[it.to] }.distinct() }
    }

    /** Fixed two-key syllables; no whole-buffer letter combinations. */
    fun doubleGraph(buffer: String, table: DoublePinyin.Table, inventory: Set<String>): Array<List<Edge>> {
        val out = Array(buffer.length + 1) { emptyList<Edge>() }
        val lastKey = buffer.indexOfLast { it != '\'' } + 1
        var from = 0
        while (from < buffer.length) {
            var start = from
            while (start < buffer.length && buffer[start] == '\'') start++
            if (start + 1 >= buffer.length || buffer[start + 1] == '\'') break
            val edges = mutableListOf<Edge>()
            for (a in letters(buffer[start])) for (b in letters(buffer[start + 1])) {
                val reading = DoublePinyin.translate("$a$b", table, inventory)
                if(reading in inventory) for((variant,cost) in PinyinFuzzy.costed(reading,inventory)) {
                    edges.add(Edge(if(start+2>=lastKey) buffer.length else start+2,variant,false,cost))
                }
            }
            out[from] = edges.sortedByDescending { it.cost }.distinctBy { it.to to it.reading }
            from = start + 2
        }
        return out
    }

    private val DIGRAPHS = setOf("zh", "ch", "sh")
}
