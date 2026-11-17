package com.wasimaster.wmkeyboard.core.input.composer

import kotlin.math.ln

/** Chinese conversion over the existing Compact grid, sharing full Pinyin's learning space. */
object CompactPinyinComposer : Composer {
    override val glidesPinyinReadings: Boolean get() = true
    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingPinyin
    override val improvingPack: String? get() = CjkDictionaries.missingPinyinNgrams

    override fun bufferKey(text: String, letters: String?): String = CompactPinyin.key(text, letters)

    override fun glideBuffer(reading: String): String = CompactPinyin.encode(reading)
    fun glideBuffer(reading: String, groups: List<String>): String = CompactPinyin.encode(reading, groups)

    override fun buffersChar(c: Char, composing: CharSequence): Boolean = c == '\'' && composing.isNotEmpty()

    private fun rawLetters(buffer: String): String = CompactReadingBuffer.parts(buffer)
        .joinToString("") { CompactPinyin.literal(it.raw) }

    /** Physical key alternatives, retaining ambiguous letters for English lookup. */
    fun englishKeyGroups(buffer: String): List<String> = CompactReadingBuffer.parts(buffer)
        .flatMap { part -> part.raw.filter { it != '\'' }.map(CompactPinyin::letters) }

    private fun showsRawLetters(): Boolean = CjkConfig.doublePinyinRawLetters &&
        DoublePinyin.tableFor(CjkConfig.doublePinyin) != null

    override fun composeBuffer(buffer: String): String {
        if (showsRawLetters()) return rawLetters(buffer)
        if (buffer.isEmpty() || buffer.length > MAX_KEYS * 10 || CompactReadingBuffer.parts(buffer).sumOf { it.raw.length } > MAX_KEYS || CjkDictionaries.pinyin.isEmpty) return CompactReadingBuffer.literal(buffer)
        val best = cache.peek(rankKey(buffer))?.firstOrNull()
        if (best != null) return best.reading + CompactReadingBuffer.literal(buffer.substring(best.consumed))
        // Preview runs on the UI thread: use only the small syllable graph,
        // leaving dictionary search and learning ranking to candidate work.
        val edges = graph(buffer)
        val readings = arrayOfNulls<String>(buffer.length + 1)
        val costs = DoubleArray(buffer.length + 1) { Double.POSITIVE_INFINITY }
        readings[0] = ""
        costs[0] = 0.0
        for (node in buffer.indices) {
            val prefix = readings[node] ?: continue
            for (edge in edges[node]) {
                val cost = costs[node] + 1.0 - edge.cost + if (edge.abbreviated) 10.0 else 0.0
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
        if (showsRawLetters()) return rawLetters(buffer)
        val candidate = cache.peek(rankKey(buffer))?.getOrNull(index) ?: return null
        return candidate.reading + CompactReadingBuffer.literal(buffer.substring(candidate.consumed))
    }

    fun unselectReadings(buffer: String): String = CompactReadingBuffer.parts(buffer).joinToString("") { it.raw }
    fun canUndoReading(buffer: String): Boolean = CompactReadingBuffer.parts(buffer).any { it.reading != null }
    fun undoReading(buffer: String): String? {
        val part=CompactReadingBuffer.parts(buffer).lastOrNull { it.reading != null } ?: return null
        return buffer.take(part.start)+part.raw+buffer.substring(part.end)
    }

    override fun backspaceBuffer(buffer: String): String =
        if (CjkConfig.doublePinyinSyllableDelete && DoublePinyin.tableFor(CjkConfig.doublePinyin) != null)
            CompactReadingBuffer.backspaceSyllable(buffer) else CompactReadingBuffer.backspace(buffer)

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
                Lattice.Opts(maxWordUnits = 16, limit = LOOKUP_LIMIT, maxAmbiguousSpan = Int.MAX_VALUE, coverageOrder = true, hanLevels = true),
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
            else CompactPinyin.doubleGraph(raw, table, PinyinSyllables.valid)
        }
    }

    private val graphCache = RankCache<Array<List<CompactPinyin.Edge>>>()
    private const val NAMESPACE = "pinyin"
    private const val LOOKUP_LIMIT = 128
    private const val MAX_KEYS = 128
    private val PARTIAL_PENALTY = ln(0.15)
    private val cache = RankCache<List<Candidate>>()
}
