package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.*
import org.junit.Test

class DoublePinyinLiteralFinalsTest {
    @Test fun `literal an and en are optional and leave in untouched`() {
        val saved = CjkConfig.doublePinyinLiteralFinals
        val inventory = setOf("an", "en", "yin", "chi", "chen", "mian", "ni")
        try {
            for (scheme in DoublePinyinScheme.entries.filter { it != DoublePinyinScheme.OFF && it != DoublePinyinScheme.CUSTOM }) {
                CjkConfig.doublePinyinLiteralFinals = false
                val base = DoublePinyin.tableFor(scheme)!!
                val originalIn = DoublePinyin.translate("in", base, inventory)
                val original = DoublePinyin.keysOf(base, inventory)
                CjkConfig.doublePinyinLiteralFinals = true
                val compatible = DoublePinyin.tableFor(scheme)!!
                assertSame(compatible, DoublePinyin.tableFor(scheme))
                assertEquals("an", DoublePinyin.translate("an", compatible, inventory))
                assertEquals("en", DoublePinyin.translate("en", compatible, inventory))
                assertEquals(originalIn, DoublePinyin.translate("in", compatible, inventory))
                assertFalse(compatible.codes.containsKey("in") && !base.codes.containsKey("in"))
                for ((syllable, code) in original) if (code != "an" && code != "en") {
                    assertEquals(syllable, DoublePinyin.translate(code, compatible, inventory))
                    assertTrue(DoublePinyin.codesOf(compatible, inventory).contains(code))
                }
                assertEquals(listOf("an", "en"), DoublePinyin.segments("anen", compatible, inventory).map { it.syllable })
                CjkConfig.doublePinyinLiteralFinals = false
                assertSame(base, DoublePinyin.tableFor(scheme))
            }
            assertNull(DoublePinyin.tableFor(DoublePinyinScheme.OFF))
        } finally { CjkConfig.doublePinyinLiteralFinals = saved }
    }
}
