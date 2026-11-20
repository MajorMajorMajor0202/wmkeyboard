// The optional host benchmark writes its report to the JUnit output.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.gesture.eval.GlideGrid
import com.wasimaster.wmkeyboard.core.gesture.eval.SwipeCorpus
import com.wasimaster.wmkeyboard.core.input.composer.CjkNgrams
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyin
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinGlide
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinScheme
import com.wasimaster.wmkeyboard.core.input.composer.PinyinSyllables
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Set WM_PINYIN_GLIDE_DATA to a directory with pinyin_v2.tsv and optionally
 * pinyin_ngrams.tsv. Run :app:testIntlDebugUnitTest --rerun --tests '*DoublePinyinGlideFrequencyTest'.
 *
 * The dictionary supplies both the frequency-ranked prompts and the decoder's
 * normal vocabulary. The scores measure synthetic gestures for known readings.
 */
class DoublePinyinGlideFrequencyTest {
    private data class Prompt(val word: String, val reading: String, val code: String)

    private val inventory = File("src/main/assets/dictionaries/pinyin_syllables.txt")
        .bufferedReader().useLines(PinyinSyllables::parse)
    private val scheme = checkNotNull(DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE))
    private val codes = DoublePinyin.keysOf(scheme, inventory)
    private val regressions = mapOf("大概" to listOf("da", "gai"), "你的" to listOf("ni", "de"))

    private fun prompts(dictionary: ConversionDictionary): List<Prompt> {
        val quotas = mapOf(2 to 150, 3 to 90, 4 to 60)
        // Select by word frequency within each length, and count homophones once.
        val rows = (0 until dictionary.size).filter { dictionary.wordLength(it) in quotas }
            .sortedByDescending(dictionary::frequency)
        val selected = quotas.mapValues { (length, quota) ->
            val readings = mutableSetOf<String>()
            rows.asSequence().filter { dictionary.wordLength(it) == length }.mapNotNull { row ->
                val word = dictionary.word(row)
                if (word.any { it !in '\u4e00'..'\u9fff' }) return@mapNotNull null
                val reading = dictionary.reading(row)
                val parts = PinyinSyllables.segment(reading, inventory)
                if (parts.size != length || parts.sumOf { it.inputLen } != reading.length) return@mapNotNull null
                val spelling = parts.map { codes[it.syllable] ?: return@mapNotNull null }.joinToString("")
                if (!readings.add(reading)) return@mapNotNull null
                Prompt(word, reading, spelling)
            }.take(quota).toList().also {
                check(it.size == quota) { "Expected $quota $length-syllable readings, found ${it.size}" }
            }
        }.values.flatten()
        val regressionPrompts = regressions.map { (word, syllables) ->
            Prompt(word, syllables.joinToString(""), syllables.joinToString("") { codes.getValue(it) })
        }
        return (regressionPrompts + selected).distinctBy { it.reading }
    }

    @Test
    fun commonReadingsOnCompactLayouts() {
        val dataPath = System.getenv("WM_PINYIN_GLIDE_DATA")
        assumeTrue("Set WM_PINYIN_GLIDE_DATA to enable frequency replay", !dataPath.isNullOrBlank())
        val data = File(checkNotNull(dataPath))
        val dictionary = ConversionDictionary.open(File(data, "pinyin_v2.tsv"))
        val ngramFile = File(data, "pinyin_ngrams.tsv")
        val ngrams = if (ngramFile.isFile) CjkNgrams.open(ngramFile) else CjkNgrams.EMPTY
        val prompts = prompts(dictionary)
        val clean = SwipeCorpus.Noise.CLEAN.profile.copy(loopOnDoubles = 1f)
        val typical = SwipeCorpus.Noise.TYPICAL.profile.copy(loopOnDoubles = 1f)
        val profiles = mapOf("clean" to clean, "typical" to typical, "typical-pause" to typical.copy(letterDwell = 0.4f))
        println("Double Pinyin frequency replay: ${prompts.size} readings, ${dictionary.size} dictionary rows, ngrams=${!ngrams.isEmpty}")
        println("Reading sample: ${prompts.take(12).joinToString { "${it.word}=${it.reading}" }}")

        for (layout in listOf(BuiltInLayouts.PINYIN_13, BuiltInLayouts.COMPACT_PINYIN)) {
            val grid = GlideGrid.of(layout)
            val keys = grid.keys.filter { it.codePoint in 'a'.code..'z'.code }.distinctBy { it.codePoint }
                .groupBy { it.x to it.y }.map { (center, letters) ->
                    DoublePinyinGlide.Key(letters.joinToString("") { it.codePoint.toChar().toString() },
                        center.first.toDouble(), center.second.toDouble())
                }
            val physicalKey = keys.flatMap { key -> key.letters.map { it to key.letters.first() } }.toMap()
            check(physicalKey.size == 26)
            fun route(code: String) = code.map(physicalKey::getValue).joinToString("")

            for ((name, profile) in profiles) {
                // Canonical physical keys turn a pair such as d/f into a repeat
                // for the existing swipe generator, just like d/d.
                val swipes = SwipeCorpus(seed = 42L, keyWidth = 1f, grid = grid)
                val scores = Score()
                val regressionRanks = mutableListOf<String>()
                for (prompt in prompts) {
                    val intended = route(prompt.code)
                    val stroke = swipes.swipe(intended, profile)
                    if (stroke == null) {
                        scores.undrawable++
                        continue
                    }
                    val points = stroke.map { DoublePinyinGlide.Point(it.x.toDouble(), it.y.toDouble(), it.t) }
                    val expectedLoop = intended.zipWithNext().any { (a, b) -> a == b }
                    val foundLoop = DoublePinyinGlide.hasLoop(points, keys)
                    val results = DoublePinyinGlide.decode(points, keys, scheme, inventory, true, dictionary, ngrams)
                    val rank = results.indexOfFirst { it.reading == prompt.reading } + 1
                    val leader = results.firstOrNull()
                    scores.record(prompt, rank, leader, leader != null && route(leader.code) == intended, expectedLoop, foundLoop)
                    if (prompt.word in regressions) {
                        regressionRanks += "${prompt.reading}=${if (rank > 0) rank else "absent"} (first=${leader?.reading})"
                    }
                }
                check(scores.total > 0 && scores.loopCases > 0 && scores.loopCases < scores.total)
                println("${keys.size} keys / $name: $scores")
                println("  Regression ranks: ${regressionRanks.joinToString()}")
                println("  Example misses: ${scores.misses.joinToString()}")
            }
        }
    }

    private class Score {
        var undrawable = 0
        var total = 0
        var loopCases = 0
        private var top1 = 0
        private var top3 = 0
        private var top8 = 0
        private var noResult = 0
        private var sameKeyMiss = 0
        private var missedLoop = 0
        private var falseLoop = 0
        val misses = mutableListOf<String>()

        fun record(
            prompt: Prompt, rank: Int, leader: DoublePinyinGlide.Result?, sameKeys: Boolean,
            expectedLoop: Boolean, foundLoop: Boolean,
        ) {
            total++
            if (rank == 1) top1++
            if (rank in 1..3) top3++
            if (rank in 1..8) top8++
            if (leader == null) noResult++
            if (rank != 1 && sameKeys) sameKeyMiss++
            if (expectedLoop) {
                loopCases++
                if (!foundLoop) missedLoop++
            } else if (foundLoop) {
                falseLoop++
            }
            if (rank !in 1..8 && misses.size < 8) {
                misses += "${prompt.word}(${prompt.reading}) -> ${leader?.reading ?: "none"}"
            }
        }

        override fun toString(): String =
            "top1=$top1/$total top3=$top3/$total top8=$top8/$total same-key-miss=$sameKeyMiss " +
                "no-result=$noResult undrawable=$undrawable missed-loop=$missedLoop/$loopCases " +
                "false-loop=$falseLoop/${total - loopCases}"
    }
}
