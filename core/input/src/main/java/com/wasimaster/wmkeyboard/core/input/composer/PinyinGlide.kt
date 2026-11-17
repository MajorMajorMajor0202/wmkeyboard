package com.wasimaster.wmkeyboard.core.input.composer

/**
 * What a swipe over the Pinyin board can spell (#580): every reading in the
 * pinyin table, and the initials of every reading of two syllables or more,
 * each with the frequency of the commonest word behind it.
 *
 * A swipe on a Latin board decodes into a word; here it decodes into a
 * *reading* — `nihao`, or `wbxh` for a run of initials — which goes into the
 * composing buffer exactly as if it had been typed, and the candidate strip
 * converts it the usual way. So the glide decoder needs no idea of Hanzi at
 * all, only of which letter sequences people spell, and how often.
 */
object PinyinGlide {

    /** Read local circles as two physical taps before asking the language model to rank them. */
    fun loopReadings(
        points: List<DoublePinyinGlide.Point>, keys: List<DoublePinyinGlide.Key>,
        inventory: Set<String>, dict: ConversionDictionary, ngrams: CjkNgrams,
    ): List<String> {
        if (!DoublePinyinGlide.hasLoop(points, keys) || dict.isEmpty) return emptyList()
        val visits = DoublePinyinGlide.intentKeys(points, keys)
        val raw = visits.joinToString("") { CompactPinyin.key(keys[it].letters.take(1), keys[it].letters) }
        if (raw.isEmpty()) return emptyList()
        val edges = CompactPinyin.graph(raw, inventory, false)
        val graph = Lattice.Graph(IntArray(edges.size) { it }, Array(edges.size) { i ->
            edges[i].filterNot { it.abbreviated }.map { Lattice.Arc(it.to, it.reading, it.cost) }
        })
        return Lattice.decode(graph, dict, ngrams, Lattice.Opts(limit = 8, maxAmbiguousSpan = Int.MAX_VALUE))
            .filter { it.consumed == raw.length }.map { it.reading }.distinct()
    }

    /**
     * The spellings and their weights, from [dict] read with [syllables] for
     * the initials. Readings of one syllable are kept: they are the commonest
     * thing typed, and `ni`, `wo` are swiped as often as tapped.
     * Under [doublePinyin], keep the active scheme's keys instead of readings;
     * only full Pinyin has optional [jianpin] spellings.
     */
    fun spellings(
        dict: ConversionDictionary,
        syllables: Set<String>,
        doublePinyin: DoublePinyin.Table? = null,
        jianpin: Boolean = true,
    ): List<Pair<String, Int>> {
        if (dict.isEmpty) return emptyList()
        val best = HashMap<String, Int>(dict.size)
        fun keep(spelling: String, freq: Int) {
            if (spelling.isEmpty() || !spelling.all { it in 'a'..'z' }) return
            if ((best[spelling] ?: -1) < freq) best[spelling] = freq
        }
        dict.forEachRow { reading, wordLength, frequency ->
            val freq = frequency.coerceAtLeast(1)
            if (doublePinyin != null) {
                val segments = PinyinSyllables.segment(reading, syllables)
                val codes = DoublePinyin.keysOf(doublePinyin, syllables)
                if (segments.isNotEmpty() && segments.sumOf { it.inputLen } == reading.length &&
                    segments.all { it.syllable in codes }
                ) keep(segments.joinToString("") { codes.getValue(it.syllable) }, freq)
                return@forEachRow
            }
            keep(reading, freq)
            if (!jianpin) return@forEachRow
            if (wordLength < 2 || syllables.isEmpty()) return@forEachRow
            val segs = PinyinSyllables.segment(reading, syllables)
            // Only a reading the inventory spells whole has initials to take.
            if (segs.size < 2 || segs.sumOf { it.inputLen } != reading.length) return@forEachRow
            keep(segs.joinToString("") { it.syllable.take(1) }, freq)
        }
        return best.entries.map { it.key to it.value }
    }
}
