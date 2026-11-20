package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.abs
import kotlin.math.ln

/** Raw offsets are kept separate from displayed spellings, including Compact's locked readings. */
object PinyinCorrection {
    data class Option(val reading: String, val replacement: String, val cost: Double)
    data class Slot(val start: Int, val end: Int, val reading: String, val options: List<Option>, val locked: Boolean, val full: Boolean, val complete: Boolean = true) {
        fun replaceIn(buffer: String, option: Option): String {
            val left = if (full && start > 0 && buffer[start-1] != '\'') "'" else ""
            val right = if (full && end < buffer.length && buffer[end] != '\'') "'" else ""
            return buffer.replaceRange(start, end, left + option.replacement + right)
        }
    }
    data class Word(
        val text: String, val slot: Int, val option: Option, val score: Double,
        val next: ReadingChoice? = null,
    ) {
        fun replaceIn(buffer: String, slots: List<Slot>): String {
            val tail = next?.let { slots[it.slot].replaceIn(buffer, it.option) } ?: buffer
            return slots[slot].replaceIn(tail, option)
        }
    }

    /** Maps the existing confidence slider to the geometry scales used by each decoder. */
    data class Tuning(val confidence: Double = 4.0, val adaptiveMultiplier: Double = 1.0) {
        private val effectiveConfidence = (confidence * adaptiveMultiplier).coerceIn(1.0, 10.0)

        val wordCostLimit: Double = 6.0 - (effectiveConfidence - 4.0) * 0.6
        val glideReach: Double = 2.2 - (effectiveConfidence - 4.0) * 0.1
        val glidePenalty: Double = 2.0 + ln(effectiveConfidence / 4.0)
    }

    private val runs = Regex("[^']+")

    private data class Scheme(val table: DoublePinyin.Table, val valid: Set<String>, val codes: Map<String, List<String>>)
    @Volatile private var cachedScheme: Scheme? = null

    private fun spellings(table: DoublePinyin.Table, valid: Set<String>): Map<String, List<String>> {
        cachedScheme?.let { if (it.table === table && it.valid === valid) return it.codes }
        val firstKeys = table.initials.keys + table.zeroLeads + table.codes.keys.map { it.first() }
        val codes = firstKeys.flatMap { DoublePinyin.firstKeyCodes(it, table, valid).entries }
            .groupBy({ it.value }, { it.key })
        cachedScheme = Scheme(table, valid, codes)
        return codes
    }

    fun slots(
        buffer: String,
        table: DoublePinyin.Table?,
        compact: Boolean,
        allOptions: Boolean = true,
        maxCost: Double = Tuning().wordCostLimit,
        keyCost: (Int, String, Char) -> Double,
    ): List<Slot> {
        if (!compact && buffer.any { c ->
                c != '\'' && c.lowercaseChar() !in 'a'..'z' && table?.finals?.containsKey(c) != true &&
                    table?.codes?.keys?.any { c in it } != true
            }) return emptyList()
        val valid = PinyinSyllables.valid
        if (valid.isEmpty() || buffer.isEmpty() || '`' in buffer) return emptyList()
        val spellings = table?.let { spellings(it, valid) }
        val ranked = if (compact) CompactPinyinComposer.rankedSyllables(buffer) else emptyMap()
        val result = mutableListOf<Slot>()
        fun add(start: Int, end: Int, raw: String, reading: String, locked: Boolean, complete: Boolean = true) {
            val groups = raw.map { if (compact) CompactPinyin.letters(it) else it.lowercaseChar().toString() }
            val fuzzy = PinyinFuzzy.costed(reading, valid).toMap()
            val soundAlikes = if (table != null && complete && !locked) {
                PinyinFuzzy.expand(reading, valid, CjkConfig.fuzzyPinyinPairs)
            } else emptySet()
            val costs = Array(groups.size) { i -> DoubleArray(26) { letter ->
                keyCost(start + i, groups[i], 'a' + letter)
            } }
            fun distance(code: String): Double {
                if (table != null && groups.size == 2 && code.length == 2 && code[0] in groups[1] && code[1] in groups[0] &&
                    (code[0] !in groups[0] || code[1] !in groups[1])
                ) return 2.5
                var cost = abs(code.length - groups.size) * 4.0
                for (i in 0 until minOf(code.length, costs.size)) {
                    cost += costs[i].getOrElse(code[i] - 'a') { keyCost(start + i, groups[i], code[i]) }
                }
                return cost
            }
            val options = if (locked && !allOptions) emptyList() else valid.mapNotNull { syllable ->
                val code = if (table == null) syllable else
                    spellings?.get(syllable)?.minByOrNull(::distance) ?: return@mapNotNull null
                val geometry = distance(code)
                val soundCost = when {
                    syllable in fuzzy -> -fuzzy.getValue(syllable)
                    syllable in soundAlikes -> 4.0
                    else -> Double.POSITIVE_INFINITY
                }
                val cost = if (syllable == reading) 0.0 else minOf(geometry, soundCost)
                if (!allOptions && cost > maxCost) return@mapNotNull null
                val replacement = if (compact) {
                    CompactReadingBuffer.selected(if (syllable == reading && complete) raw else code, syllable)
                } else code
                Option(syllable, replacement, cost)
            }.sortedWith(compareBy<Option> { it.cost }.thenBy { it.reading })
            result += Slot(start, end, reading, options, locked, full = table == null && !compact, complete = complete)
        }
        for (part in CompactReadingBuffer.parts(buffer)) {
            if (part.reading != null) {
                add(part.start, part.end, part.raw, part.reading, true)
                continue
            }
            for (run in runs.findAll(part.raw)) {
                val raw = run.value
                var at = part.start + run.range.first
                if (table != null) {
                    for (pair in raw.chunked(2).takeWhile { it.length == 2 }) {
                        val readings = CompactPinyin.letters(pair[0]).flatMap { a ->
                            CompactPinyin.letters(pair[1]).map { b -> DoublePinyin.translate("$a$b", table, valid) }
                        }
                        add(at, at + 2, pair, ranked[at] ?: readings.firstOrNull { it in valid } ?: readings.first(), false)
                        at += 2
                    }
                    if (allOptions && raw.length % 2 != 0) {
                        val first = raw.takeLast(1)
                        add(at, at + 1, first, CompactPinyin.letters(first[0]).first().toString(),
                            locked = false, complete = false)
                    }
                } else if (compact) {
                    val graph = CompactPinyin.graph(raw, valid, CjkConfig.jianpin)
                    var offset = 0
                    while (offset < raw.length) {
                        val edges = graph[offset]
                        val edge = edges.firstOrNull { it.reading == ranked[at + offset] }
                            ?: edges.filterNot { it.abbreviated }.maxByOrNull { it.to }
                            ?: edges.firstOrNull()
                            ?: break
                        add(at + offset, at + edge.to, raw.substring(offset, edge.to), edge.reading,
                            locked = false, complete = !edge.abbreviated)
                        offset = edge.to
                    }
                    if (offset < raw.length && allOptions) {
                        val tail = raw.substring(offset)
                        add(at + offset, at + raw.length, tail, CompactPinyin.literal(tail),
                            locked = false, complete = false)
                    }
                } else if (raw.all { it.isLetter() }) {
                    val end = at + raw.length
                    for (seg in PinyinSyllables.segment(raw)) {
                        add(at, at + seg.inputLen, seg.syllable, seg.syllable, false)
                        at += seg.inputLen
                    }
                    if (at < end) {
                        val tail = buffer.substring(at, end)
                        add(at, end, tail, tail, false)
                    }
                }
            }
        }
        return result
    }

    fun coversBuffer(buffer: String, slots: List<Slot>): Boolean {
        var end = 0
        for (slot in slots) {
            if (!slot.complete || buffer.substring(end, slot.start).any { it != '\'' }) return false
            end = slot.end
        }
        return slots.isNotEmpty() && buffer.substring(end).all { it == '\'' }
    }

    data class ReadingChoice(val slot: Int, val option: Option)

    /** Suggest plausible substitutes, using phrase and syllable frequencies as bounded evidence. */
    fun readingChoices(
        slots: List<Slot>,
        dictionary: ConversionDictionary,
        words: List<Word>,
        caret: Int,
    ): List<ReadingChoice> {
        val wordEvidence = buildMap {
            for (word in words) {
                val choices = listOfNotNull(ReadingChoice(word.slot, word.option), word.next)
                for ((slot, option) in choices) {
                    val key = slot to option.reading
                    put(key, maxOf(get(key) ?: 0.0, word.score + option.cost))
                }
            }
        }
        val syllableEvidence = mutableMapOf<String, Double>()
        return slots.flatMapIndexed { index, slot ->
            if (caret >= 0 && caret !in slot.start until slot.end) return@flatMapIndexed emptyList()
            slot.options.asSequence().filter { (!slot.complete || it.reading != slot.reading) && it.cost <= 8.0 }
                .map { option ->
                    val syllable = syllableEvidence.getOrPut(option.reading) {
                        (ln(1.0 + dictionary.bestFrequency(option.reading, 1)) * 0.07).coerceAtMost(0.8)
                    }
                    val phrase = wordEvidence[index to option.reading] ?: 0.0
                    val score = syllable + (phrase * 0.22).coerceIn(0.0, 2.5) - option.cost
                    ReadingChoice(index, option) to score
                }
                .filter { (_, score) -> score >= -5.0 }
                .toList()
        }.sortedByDescending { it.second }.map { it.first }
    }

    /** Whole dictionary words only. Compare nearby substitutions across positions before taking the limit. */
    fun words(
        buffer: String,
        slots: List<Slot>,
        dictionary: ConversionDictionary,
        context: String? = null,
        maxCost: Double = Tuning().wordCostLimit,
        doublePinyin: Boolean = false,
    ): List<Word> {
        if (slots.size !in 1..16 || dictionary.isEmpty) return emptyList()
        if (!coversBuffer(buffer, slots)) return emptyList()
        val results = mutableListOf<Word>()
        val options = slots.map { slot ->
            if (slot.locked) emptyList() else slot.options.filter { it.reading != slot.reading && it.cost <= maxCost }
                .take(if (doublePinyin) 24 else 12)
        }
        fun search(index: Int, prefix: String, rows: IntRange, cost: Double, first: ReadingChoice?, second: ReadingChoice?) {
            if (index == slots.size) {
                // A different reading of the same physical keys belongs to the normal ranking.
                if (first == null || cost <= 0.0) return
                for (row in dictionary.rowsFor(prefix).take(3)) {
                    val word = dictionary.word(row)
                    if (word.codePointCount(0, word.length) != slots.size) continue
                    val frequency = ln(1.0 + dictionary.frequency(row))
                    val bigram = context?.let { CjkDictionaries.pinyinNgrams.bigram(it, word) } ?: 0
                    val score = frequency + 0.5 * ln(1.0 + bigram) - cost
                    results += Word(HanVariant.localize(word), first.slot, first.option, score, second)
                }
                return
            }
            val exact = prefix + slots[index].reading
            val exactRows = dictionary.prefixRange(exact, rows)
            if (!exactRows.isEmpty()) search(index + 1, exact, exactRows, cost, first, second)
            if (second != null || (first != null && !doublePinyin)) return
            for (option in options[index]) {
                val total = cost + option.cost
                val limit = if (first == null) maxCost else maxCost * 1.4
                if (total > limit) continue
                val corrected = prefix + option.reading
                val correctedRows = dictionary.prefixRange(corrected, rows)
                if (correctedRows.isEmpty()) continue
                val choice = ReadingChoice(index, option)
                search(index + 1, corrected, correctedRows, total, first ?: choice, choice.takeIf { first != null })
            }
        }
        search(0, "", 0 until dictionary.size, 0.0, null, null)
        return results.sortedByDescending { it.score }.distinctBy { it.text }
    }
}
