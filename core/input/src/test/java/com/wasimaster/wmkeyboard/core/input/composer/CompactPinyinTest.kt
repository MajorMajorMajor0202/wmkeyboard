package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactPinyinTest {
    @Test fun `double pinyin retains ambiguous letters within fixed syllable pairs`() {
        val table = DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
        val inventory = setOf("ni", "bi", "mian")
        val graph = CompactPinyin.doubleGraph(CompactPinyin.encode("ni"), table, inventory)
        assertEquals(setOf("ni", "bi"), graph[0].map { it.reading }.toSet())
        assertTrue(graph[0].all { it.to == 2 && !it.abbreviated })
        assertTrue(CompactPinyin.doubleGraph("m", table, inventory)[0].isEmpty())
        val phrase = CompactPinyin.doubleGraph("mm'mm'", table, inventory)
        assertEquals(2, phrase[0].single().to)
        assertEquals(6, phrase[2].single().to)
        val long = CompactPinyin.doubleGraph("mm".repeat(64), table, inventory)
        assertEquals(64, long.sumOf { it.size })
    }

    private val inventory = setOf("ni", "bi", "mi", "hao", "zhong", "guo", "xi", "an", "xian", "nin", "ne", "e", "shi", "jie")

    private fun edges(reading: String, jianpin: Boolean = false) =
        CompactPinyin.graph(CompactPinyin.encode(reading), inventory, jianpin)

    @Test fun `single key groups cover the alphabet exactly once`() {
        assertEquals(14, CompactPinyin.groups.size)
        assertEquals(('a'..'z').toList(), CompactPinyin.groups.joinToString("").toList().sorted())
        assertEquals(5, CompactPinyin.groups.take(5).size)
        assertEquals(5, CompactPinyin.groups.drop(5).take(5).size)
        assertEquals(4, CompactPinyin.groups.drop(10).size)
    }

    @Test fun `a tap admits either letter and an explicit popup letter stays exact`() {
        assertTrue(edges("ni")[0].any { it.reading == "ni" && it.to == 2 })
        assertTrue(edges("ni")[0].any { it.reading == "bi" && it.to == 2 })
        val exact = CompactPinyin.graph("ni", inventory, false)
        assertEquals(listOf("ni"), exact[0].filterNot { it.abbreviated }.map { it.reading })
        assertEquals("n", CompactPinyin.key("n", null))
        assertEquals(CompactPinyin.key("b", "bn"), CompactPinyin.key("N", "bn"))
    }

    @Test fun `whole words retain their keystroke boundaries`() {
        val graph = edges("nihao")
        assertTrue(graph[0].any { it.reading == "ni" && it.to == 2 })
        assertTrue(graph[2].any { it.reading == "hao" && it.to == 5 })
    }

    @Test fun `unfinished final syllables preserve their actual input length`() {
        assertTrue(edges("niha")[2].any { it.reading == "hao" && it.to == 4 && it.abbreviated })
        assertFalse(edges("niha'")[2].any { it.reading == "hao" })
    }

    @Test fun `jianpin and digraph initials are optional`() {
        assertTrue(edges("nh", true)[0].any { it.reading == "ni" && it.to == 1 && it.abbreviated })
        assertTrue(edges("nh", true)[1].any { it.reading == "hao" && it.to == 2 })
        assertFalse(edges("nh")[0].any { it.reading == "ni" })
        assertTrue(edges("zhg", true)[0].any { it.reading == "zhong" && it.to == 2 })
        assertTrue(edges("nih", true)[2].any { it.reading == "hao" && it.to == 3 })
    }

    @Test fun `deletion removes exactly one physical key`() {
        val buffer = CompactPinyin.encode("nihao")
        assertEquals(5, buffer.length)
        assertEquals(CompactPinyin.encode("niha"), buffer.dropLast(1))
        assertEquals("", CompactPinyin.encode("n").dropLast(1))
    }

    @Test fun `cross syllable ambiguity retains every split`() {
        val graph = edges("xian")
        assertTrue(graph[0].any { it.reading == "xian" && it.to == 4 })
        assertTrue(graph[0].any { it.reading == "xi" && it.to == 2 })
        assertTrue(graph[2].any { it.reading == "an" && it.to == 4 })
        val nine = edges("nine")
        assertTrue(nine[0].any { it.reading == "nin" && it.to == 3 })
        assertTrue(nine[0].any { it.reading == "ni" && it.to == 2 })
        assertTrue(nine[2].any { it.reading == "ne" && it.to == 4 })
    }

    @Test fun `apostrophes force boundaries and are consumed with their syllable`() {
        val graph = edges("xi'an")
        assertFalse(graph[0].any { it.reading == "xian" })
        assertTrue(graph[2].any { it.reading == "an" && it.to == 5 })
        assertTrue(edges("ni'")[0].any { it.reading == "ni" && it.to == 3 })
    }

    @Test fun `empty pack and invalid text degrade without exposing private symbols`() {
        assertTrue(CompactPinyin.graph(CompactPinyin.encode("ni"), emptySet(), true).all { it.isEmpty() })
        assertEquals("bu", CompactPinyin.literal(CompactPinyin.encode("ni")))
        assertTrue(CompactPinyin.graph("!", inventory, true).all { it.isEmpty() })
    }

    @Test fun `long ambiguous runs allocate only a linear number of graph edges`() {
        val buffer = CompactPinyin.encode("nihao".repeat(200))
        val graph = CompactPinyin.graph(buffer, inventory, true)
        assertEquals(buffer.length + 1, graph.size)
        assertTrue(graph.sumOf { it.size } <= buffer.length * inventory.size * 3)
    }
}
