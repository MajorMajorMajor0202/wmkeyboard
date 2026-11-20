package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.ln

/** Chinese conversion over the existing Compact grid, sharing full Pinyin's learning space. */
object CompactPinyinComposer : Composer {
    override val glidesPinyinReadings: Boolean get() = true
    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val editsReadingInPlace: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingPinyin
    override val improvingPack: String? get() = CjkDictionaries.missingPinyinNgrams

    override fun bufferKey(text: String, letters: String?): String = CompactPinyin.key(text, letters)

    override fun glideBuffer(reading: String): String = CompactPinyin.encode(reading)
    fun glideBuffer(reading: String, groups: List<String>): String = CompactPinyin.encode(reading, groups)

    /** Preserve the selected syllables when a full-Pinyin glide becomes composing text. */
    fun predictedGlideBuffer(reading: String): String {
        val syllables = PinyinSyllables.segment(reading)
        if (syllables.sumOf { it.inputLen } != reading.length) return glideBuffer(reading)
        return syllables.joinToString("") {
            CompactReadingBuffer.selected(glideBuffer(it.syllable), it.syllable)
        }
    }

    override fun buffersChar(c: Char, composing: CharSequence): Boolean = c == '\'' && composing.isNotEmpty()

    private fun rawLetters(buffer: String, candidate: Candidate?): String {
        val table = DoublePinyin.tableFor(CjkConfig.doublePinyin) ?: return CompactReadingBuffer.literal(buffer)
        val syllables = candidate?.let { candidateSyllables(buffer, it, completeOnly = true) }.orEmpty()
        return buildString {
            for (part in CompactReadingBuffer.parts(buffer)) {
                val raw = part.raw
                var at = 0
                while (at < raw.length) {
                    if (raw[at] == '\'' || at + 1 >= raw.length || raw[at + 1] == '\'') {
                        append(CompactPinyin.literal(raw[at++].toString()))
                        continue
                    }
                    val target = part.reading ?: syllables[part.start + at]
                    val code = target?.let {
                        CompactPinyin.doubleCode(raw[at], raw[at + 1], it, table, PinyinSyllables.valid)
                    }
                    append(code ?: CompactPinyin.literal(raw.substring(at, at + 2)))
                    at += 2
                }
            }
        }
    }

    /** Physical key alternatives, retaining ambiguous letters for English lookup. */
    fun englishKeyGroups(buffer: String): List<String> = CompactReadingBuffer.parts(buffer)
        .flatMap { part -> part.raw.filter { it != '\'' }.map(CompactPinyin::letters) }

    /** A tap in a multi-letter key can change the reading once the dictionary resolves it. */
    fun hasAmbiguousTaps(buffer: String): Boolean = CompactReadingBuffer.parts(buffer).any { part ->
        part.reading == null && part.raw.any { CompactPinyin.letters(it).length > 1 }
    }

    private fun showsRawLetters(): Boolean = CjkConfig.doublePinyinRawLetters &&
        DoublePinyin.tableFor(CjkConfig.doublePinyin) != null

    override fun composeBuffer(buffer: String): String {
        if (showsRawLetters()) {
            val best = cache.peek(rankKey(buffer))?.firstOrNull()
            return rawLetters(buffer, best)
        }
        if (buffer.isEmpty() || buffer.length > MAX_KEYS * 10 || CompactReadingBuffer.parts(buffer).sumOf { it.raw.length } > MAX_KEYS || CjkDictionaries.pinyin.isEmpty) return CompactReadingBuffer.literal(buffer)
        val best = cache.peek(rankKey(buffer))?.firstOrNull()
        if (best != null) return displayCandidate(buffer, best)
        // Preview runs on the UI thread: use only the small syllable graph,
        // leaving dictionary search and learning ranking to candidate work.
        val previous = buffer.dropLast(1)
        val known = cache.peek(rankKey(previous))?.firstOrNull()?.let {
            candidateSyllables(previous, it, completeOnly = true)
        }.orEmpty()
        val edges = graph(buffer)
        val readings = arrayOfNulls<String>(buffer.length + 1)
        val costs = DoubleArray(buffer.length + 1) { Double.POSITIVE_INFINITY }
        readings[0] = ""
        costs[0] = 0.0
        val doublePinyin = DoublePinyin.tableFor(CjkConfig.doublePinyin) != null
        for (node in buffer.indices) {
            val prefix = readings[node] ?: continue
            var start = node
            while (start < buffer.length && buffer[start] == '\'') start++
            val preferred = known[start]
            for (edge in edges[node]) {
                if (doublePinyin && edge.abbreviated) continue
                val change = if (preferred == null || preferred == edge.reading) 0.0 else 1.0
                val cost = costs[node] + 1.0 - edge.cost + change + if (edge.abbreviated) 10.0 else 0.0
                if (cost < costs[edge.to]) {
                    costs[edge.to] = cost
                    readings[edge.to] = prefix + edge.reading
                }
            }
        }
        val end = readings.indexOfLast { it != null }
        return readings[end].orEmpty() + CompactReadingBuffer.literal(buffer.substring(end))
    }

    override fun candidatePreview(buffer: String, index: Int): String? {
        val candidate = cache.peek(rankKey(buffer))?.getOrNull(index)
        if (showsRawLetters()) return rawLetters(buffer, candidate)
        candidate ?: return null
        return displayCandidate(buffer, candidate)
    }

    private fun displayCandidate(buffer: String, candidate: Candidate): String {
        // In double Pinyin the last unpaired key remains visible until a final
        // key is typed, even when the strip can already suggest the whole word.
        if (DoublePinyin.tableFor(CjkConfig.doublePinyin) != null && candidate.consumed == buffer.length) {
            val tail = CompactReadingBuffer.parts(buffer).lastOrNull()
            if (tail != null && tail.reading == null) {
                val key = DoublePinyin.lastKeyBeforeCaret(tail.raw, tail.raw.length)
                if (key >= 0 && DoublePinyin.syllableAt(tail.raw, key).first == key) {
                    val start = tail.start + key
                    val completed = candidateSyllables(buffer, candidate)[start]
                    if (completed != null) {
                        return candidate.reading.removeSuffix(completed) + CompactPinyin.literal(tail.raw.substring(key))
                    }
                }
            }
        }
        return candidate.reading + CompactReadingBuffer.literal(buffer.substring(candidate.consumed))
    }

    /** Recover the exact syllable boundaries of the displayed candidate, including ambiguous compact keys. */
    internal fun rankedSyllables(buffer: String): Map<Int, String> {
        val candidate = cache.peek(rankKey(buffer))?.firstOrNull() ?: return emptyMap()
        return candidateSyllables(buffer, candidate)
    }

    private fun candidateSyllables(buffer: String, candidate: Candidate, completeOnly: Boolean = false): Map<Int, String> {
        val edges = graph(buffer)
        val failed = mutableSetOf<Pair<Int, Int>>()
        val result = mutableMapOf<Int, String>()
        fun match(node: Int, offset: Int): Boolean {
            if (node == candidate.consumed) return offset == candidate.reading.length
            if (node > candidate.consumed || !failed.add(node to offset)) return false
            for (edge in edges[node]) {
                if (candidate.reading.startsWith(edge.reading, offset) && match(edge.to, offset + edge.reading.length)) {
                    var start = node
                    while (start < edge.to && buffer[start] == '\'') start++
                    if (!completeOnly || !edge.abbreviated) result[start] = edge.reading
                    return true
                }
            }
            return false
        }
        match(0, 0)
        return result
    }

    private fun readingStops(buffer: String): List<Int> = buildList {
        add(0)
        for (part in CompactReadingBuffer.parts(buffer)) {
            if (part.reading == null) addAll((part.start + 1)..part.end) else add(part.end)
        }
    }

    override fun moveReadingIndex(buffer: String, index: Int, delta: Int): Int {
        val stops = readingStops(buffer)
        val current = stops.indexOfLast { it <= index }.coerceAtLeast(0)
        return stops[(current + delta).coerceIn(stops.indices)]
    }

    override fun previewOffset(buffer: String, index: Int): Int {
        val stop = readingStops(buffer).last { it <= index.coerceIn(0, buffer.length) }
        return composeBuffer(buffer.substring(0, stop)).length
    }

    override fun bufferIndex(buffer: String, offset: Int): Int {
        val stop = readingStops(buffer).lastOrNull { previewOffset(buffer, it) <= offset } ?: 0
        if (deletesWholeSyllables()) {
            // A tap inside a locked reading targets that syllable on backspace.
            // Its opaque buffer stays editable only at its boundaries.
            val part = CompactReadingBuffer.parts(buffer).firstOrNull { it.start == stop && it.reading != null }
            if (part != null && offset > previewOffset(buffer, stop) && offset < previewOffset(buffer, part.end)) {
                return part.end
            }
        }
        return stop
    }

    fun unselectReadings(buffer: String): String = CompactReadingBuffer.parts(buffer).joinToString("") { it.raw }
    fun canUndoReading(buffer: String): Boolean = CompactReadingBuffer.parts(buffer).any { it.reading != null }
    fun undoReading(buffer: String): String? {
        val part=CompactReadingBuffer.parts(buffer).lastOrNull { it.reading != null } ?: return null
        return buffer.take(part.start)+part.raw+buffer.substring(part.end)
    }

    private fun deletesWholeSyllables(): Boolean =
        CjkConfig.doublePinyinSyllableDelete && DoublePinyin.tableFor(CjkConfig.doublePinyin) != null

    override fun backspaceBuffer(buffer: String): String =
        if (deletesWholeSyllables()) CompactReadingBuffer.backspaceSyllable(buffer)
        else CompactReadingBuffer.backspace(buffer)

    override fun backspaceReading(buffer: String, caret: Int): Pair<String, Int> {
        if (!deletesWholeSyllables()) return super.backspaceReading(buffer, caret)
        val key = DoublePinyin.lastKeyBeforeCaret(buffer, caret)
        val part = CompactReadingBuffer.parts(buffer).firstOrNull { key in it.start until it.end }
            ?: return super.backspaceReading(buffer, caret)
        val syllable = if (part.reading != null) part.start until part.end else {
            val local = DoublePinyin.syllableAt(part.raw, key - part.start)
            (part.start + local.first)..(part.start + local.last)
        }
        return DoublePinyin.removeSyllable(buffer, syllable)
    }

    override fun readings(buffer: String): List<String> {
        val next = CompactReadingBuffer.parts(buffer).firstOrNull { it.reading == null && it.raw.any { c -> c != '\'' } }
            ?: return emptyList()
        val start = next.start + next.raw.indexOfFirst { it != '\'' }
        val choices = graph(buffer)[start].map { it.reading }.distinct()
        val prefix = CompactReadingBuffer.parts(buffer).takeWhile { it.start < start }.sumOf { it.reading?.length ?: 0 }
        val best = ranked(buffer).firstOrNull()?.reading?.drop(prefix)
        val leading = choices.filter { best?.startsWith(it) == true }.maxByOrNull { it.length }
        return if (leading == null) choices else listOf(leading) + (choices - leading)
    }

    override fun pickReading(buffer: String, reading: String): String? {
        val next = CompactReadingBuffer.parts(buffer).firstOrNull { it.reading == null && it.raw.any { c -> c != '\'' } }
            ?: return null
        val start = next.start + next.raw.indexOfFirst { it != '\'' }
        val edge = graph(buffer)[start].filter { it.reading == reading }.maxByOrNull { it.to } ?: return null
        return buffer.take(start) + CompactReadingBuffer.selected(buffer.substring(start, edge.to), reading) + buffer.substring(edge.to)
    }

    override fun typedReading(buffer: String): String = composeBuffer(buffer).replace("'", "")
    override fun candidates(buffer: String): List<String> = candidates(buffer, 12)
    override fun candidates(buffer: String, limit: Int): List<String> = ranked(buffer).take(limit).map { it.text }
    override fun consumedFor(buffer: String, chosen: String): Int =
        ranked(buffer).firstOrNull { it.text == chosen }?.consumed ?: buffer.length
    override fun consumedForIndex(buffer: String, index: Int): Int =
        ranked(buffer).getOrNull(index)?.consumed ?: buffer.length

    /** Explicit picks stay together while their remaining keys or committed context continue. */
    private class LearningRun(val rest: String,val parts: List<String>,val reading: String,
        val store: CjkUserHistory?, val context: String)
    private var learningRun: LearningRun? = null

    override fun learnChoice(buffer: String, index: Int) {
        val candidate = ranked(buffer).getOrNull(index) ?: return
        val previous=learningRun?.takeIf { it.store===CjkLearning.store &&
            (if(it.rest.isNotEmpty()) buffer.startsWith(it.rest) else it.context.isNotEmpty() && it.context==CjkContext.before) }
        CjkLearning.learn(NAMESPACE,candidate.reading,candidate.source)
        (previous?.parts?.lastOrNull() ?: contextWord())?.let { CjkLearning.learnPair(NAMESPACE,it,candidate.source) }
        val rest=remainderAfterCommit(buffer.substring(candidate.consumed.coerceIn(0,buffer.length)))
        val parts=previous?.parts.orEmpty()+candidate.source
        val reading=previous?.reading.orEmpty()+candidate.reading
        val text=parts.joinToString("")
        val length=text.codePointCount(0,text.length)
        learningRun=if(length<=16)
            LearningRun(rest,parts,reading,CjkLearning.store,CjkContext.hanTail(CjkContext.before+candidate.source)) else null
        if(parts.size>=2) {
            val limit=if(parts.all { it.codePointCount(0,it.length)==1 }) 6 else 16
            if(length<=limit) CjkLearning.learn(NAMESPACE,reading,text)
        }
    }

    override val adjustsChoices: Boolean get() = true
    override fun adjustChoice(buffer: String, index: Int, adjustment: CandidateAdjustment) {
        val candidate = ranked(buffer).getOrNull(index) ?: return
        CjkLearning.adjust(NAMESPACE, candidate.reading, candidate.text, candidate.source, adjustment)
    }

    override fun predictions(context: String, limit: Int): List<String> = PinyinComposer.predictions(context, limit)
    override fun learnPrediction(context: String, picked: String) = PinyinComposer.learnPrediction(context, picked)

    private data class Candidate(val text: String, val consumed: Int, val reading: String, val source: String)

    private fun ranked(buffer: String): List<Candidate> {
        if (buffer.isEmpty() || buffer.length > MAX_KEYS * 10 || CompactReadingBuffer.parts(buffer).sumOf { it.raw.length } > MAX_KEYS) return emptyList()
        return cache.get(rankKey(buffer)) {
            val edges = graph(buffer)
            val graph = Lattice.Graph(
                IntArray(buffer.length + 1) { it },
                Array(edges.size) { node ->
                    edges[node].map { Lattice.Arc(it.to, it.reading, it.cost + if (it.abbreviated) PARTIAL_PENALTY else 0.0, guess=it.cost<0.0) }
                },
            )
            Lattice.decode(
                graph,
                CjkDictionaries.pinyin,
                CjkDictionaries.pinyinNgrams,
                Lattice.Opts(
                    maxWordUnits = 16,
                    limit = LOOKUP_LIMIT,
                    maxAmbiguousSpan = Int.MAX_VALUE,
                    coverageOrder = true,
                    hanLevels = true,
                    // Competing readings share every physical pair. Keep lower-frequency
                    // opening words until later syllables can supply their word-pair evidence.
                    beam = if (CjkConfig.doublePinyin != DoublePinyinScheme.OFF && hasAmbiguousTaps(buffer)) 16 else 8,
                ),
                CjkLearning.userData(NAMESPACE),
                Lattice.UserScope.ALL,
                contextWord(),
            )
                .flatMap { c -> HanVariant.traditionalForms(c.text).map { Candidate(it, c.consumed, c.reading, c.text) } }
                .distinctBy { it.text }
                .let { CjkLearning.rank(NAMESPACE, it, { c -> c.source }, { c -> c.reading }, { c -> c.text }, { c -> c.consumed }) }
        }
    }

    private fun rankKey(buffer: String): String = buffer + "\u0000" + CjkContext.before

    private fun contextWord(): String? {
        val pairs = CjkLearning.store?.pairsFor(NAMESPACE).orEmpty()
        val ngrams = CjkDictionaries.pinyinNgrams
        return CjkContext.previousWord(CjkContext.before) { it in pairs || ngrams.unigram(it) > 0 }
    }

    private fun graph(buffer: String): Array<List<CompactPinyin.Edge>> = graphCache.get(buffer) {
        val table = DoublePinyin.tableFor(CjkConfig.doublePinyin)
        CompactReadingBuffer.graph(buffer) { raw ->
            if (table == null) CompactPinyin.graph(raw, PinyinSyllables.valid, CjkConfig.jianpin)
            else CompactPinyin.doubleGraph(raw, table, PinyinSyllables.valid, completeTail = true)
        }
    }

    private val graphCache = RankCache<Array<List<CompactPinyin.Edge>>>()
    private const val NAMESPACE = "pinyin"
    private const val LOOKUP_LIMIT = 128
    private const val MAX_KEYS = 128
    private val PARTIAL_PENALTY = ln(0.15)
    private val cache = RankCache<List<Candidate>>()
}
