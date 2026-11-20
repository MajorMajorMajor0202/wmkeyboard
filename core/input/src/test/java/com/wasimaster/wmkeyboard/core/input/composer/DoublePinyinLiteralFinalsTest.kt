package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.*
import org.junit.Test

class DoublePinyinLiteralFinalsTest {
    private val literal = listOf("an", "en", "ao", "ai", "ei")
    private val inventory = literal.toSet() + setOf("o", "yin", "chi", "chen", "mian", "ni")

    @Test fun `one switch enables all literal syllables and preserves other scheme codes`() {
        val saved = CjkConfig.doublePinyinLiteralFinals
        try {
            for (scheme in DoublePinyinScheme.entries.filter { it != DoublePinyinScheme.OFF && it != DoublePinyinScheme.CUSTOM }) {
                CjkConfig.doublePinyinLiteralFinals = false
                val base = DoublePinyin.tableFor(scheme)!!
                assertEquals("o", DoublePinyin.translate("ao", base, inventory))
                val original = DoublePinyin.codesOf(base, inventory).filterNot { it in literal }
                    .associateWith { DoublePinyin.translate(it, base, inventory) }
                CjkConfig.doublePinyinLiteralFinals = true
                val compatible = DoublePinyin.tableFor(scheme)!!
                assertSame(compatible, DoublePinyin.tableFor(scheme))
                assertSame(base, DoublePinyin.tableFor(scheme, literalFinals = false))
                val compatibleCodes = DoublePinyin.codesOf(compatible, inventory)
                val inverse = DoublePinyin.keysOf(compatible, inventory)
                for (syllable in literal) {
                    assertEquals(syllable, DoublePinyin.translate(syllable, compatible, inventory))
                    assertEquals(syllable, inverse[syllable])
                    assertTrue(syllable in compatibleCodes)
                }
                for ((code, syllable) in original) {
                    assertEquals(syllable, DoublePinyin.translate(code, compatible, inventory))
                    assertTrue(code in compatibleCodes)
                }
                assertEquals(literal, DoublePinyin.segments(literal.joinToString(""), compatible, inventory).map { it.syllable })
                CjkConfig.doublePinyinLiteralFinals = false
                assertSame(base, DoublePinyin.tableFor(scheme))
            }
            assertNull(DoublePinyin.tableFor(DoublePinyinScheme.OFF))
        } finally { CjkConfig.doublePinyinLiteralFinals = saved }
    }

    @Test fun `literal finals are available from ordinary and compact glide keys`() {
        val saved = CjkConfig.doublePinyinLiteralFinals
        try {
            CjkConfig.doublePinyinLiteralFinals = true
            val table = DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
            val groups = listOf("as", "er", "op", "ui")
            for (syllable in listOf("ao", "ai", "ei")) {
                for (compact in listOf(false, true)) {
                    val (first, second) = syllable.map { letter ->
                        if (compact) groups.first { letter in it } else letter.toString()
                    }
                    val choices = DoublePinyinGlide.exactSyllables(first, second, table, inventory, compact)
                    val result = choices.single { it.reading == syllable }
                    val buffer = DoublePinyinGlide.bufferFor(result, table, inventory, compact)!!
                    assertEquals(listOf(syllable to buffer), DoublePinyinGlide.syllableBuffers(buffer, table, inventory, compact))
                }
            }
        } finally {
            CjkConfig.doublePinyinLiteralFinals = saved
        }
    }
}
