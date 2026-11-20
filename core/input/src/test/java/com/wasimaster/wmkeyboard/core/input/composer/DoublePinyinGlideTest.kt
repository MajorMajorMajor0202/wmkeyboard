package com.wasimaster.wmkeyboard.core.input.composer

import org.junit.Assert.*
import org.junit.Test

class DoublePinyinGlideTest {
    private val table = DoublePinyin.tableFor(DoublePinyinScheme.XIAOHE)!!
    private val inventory = setOf("you", "yao", "guan", "xi", "tou", "hua", "xue", "yong", "chi", "ni", "hao", "xia")
    private val dict = ConversionDictionary.parse(sequenceOf(
        "you\t有\t923", "yao\t要\t902", "guan\t关\t800", "xi\t系\t700",
        "guanxi\t关系\t664", "youyongchi\t游泳池\t501", "huaxue\t化学\t800",
        "ni\t你\t1000", "hao\t好\t900", "nihao\t你好\t1500", "xia\t下\t900",
    ))
    private fun keys(compact: Boolean): List<DoublePinyinGlide.Key> {
        if (compact) return listOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m").mapIndexed { i, letters ->
            when {
                i < 5 -> DoublePinyinGlide.Key(letters, 1.0 + i * 2, 0.5)
                i < 10 -> DoublePinyinGlide.Key(letters, 1.5 + (i - 5) * 2, 1.5)
                else -> DoublePinyinGlide.Key(letters, 2.5 + (i - 10) * 2, 2.5)
            }
        }
        return listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").flatMapIndexed { r, letters -> letters.mapIndexed { c, letter -> DoublePinyinGlide.Key(letter.toString(), c + r * 0.5, r.toDouble()) } }
    }
    private fun points(code: String, keys: List<DoublePinyinGlide.Key>): List<DoublePinyinGlide.Point> = buildList {
        val anchors = code.map { c -> keys.first { c in it.letters } }
        for (i in 1 until anchors.size) for (j in 0..12) {
            val f = j / 12.0; val a = anchors[i - 1]; val b = anchors[i]
            add(DoublePinyinGlide.Point(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
        }
    }
    @Test fun `returning across adjacent compact keys keeps both syllables of dagai`() {
        val active = setOf("da", "gai", "dai", "den", "fa", "sang", "gang", "geng", "hang", "hai", "hen", "ha")
        val dictionary = ConversionDictionary.parse(sequenceOf(
            "da\t大\t852", "gai\t概\t805", "dagai\t大概\t644", "dai\t带\t892", "fa\t发\t840",
            "sang\t桑\t400", "gang\t刚\t860", "geng\t更\t900", "hang\t行\t830", "hai\t还\t916", "hen\t很\t940",
        ))
        for (pitch in listOf(1.0, 2.0)) {
            val keys = listOf("as", "df", "gh").mapIndexed { index, letters ->
                DoublePinyinGlide.Key(letters, (index - 1) * pitch, 0.0, pitch)
            }
            val paths = listOf(
                points("dagd", keys),
                listOf(0.0 to 0.0, -0.45 to -0.11, -0.65 to 0.08, -0.45 to 0.30,
                    0.0 to 0.44, 0.45 to 0.34, 0.65 to 0.12, 0.45 to -0.04, 0.0 to 0.0)
                    .map { (x, y) -> DoublePinyinGlide.Point(x * pitch, y) },
            )
            for (path in paths) {
                assertEquals("$pitch $path", listOf(1, 0, 2, 1), DoublePinyinGlide.intentKeys(path, keys))
                for (live in listOf(false, true)) {
                    val results = DoublePinyinGlide.decode(path, keys, table, active, true, dictionary, CjkNgrams.EMPTY, live = live)
                    assertEquals("$pitch $live ${results.take(4)}", "dagai", results.firstOrNull()?.reading)
                }
            }
        }
    }

    @Test fun `rounded turns and an early lift keep ni de on merged keys`() {
        val dictionary = ConversionDictionary.parse(sequenceOf(
            "ni\t你\t1000", "bi\t比\t800", "de\t的\t1000", "shai\t晒\t800", "tai\t太\t900",
            "ban\t班\t900", "nan\t难\t800", "fen\t分\t900", "ning\t宁\t400",
        ))
        val ngrams = CjkNgrams.parse(sequenceOf("你\t5000", "的\t9000", "你\t的\t3000"))
        val active = setOf("ni", "bi", "de", "shai", "tai", "ban", "nan", "fen", "ning")
        val keys = listOf(
            DoublePinyinGlide.Key("er", 3.0, 0.0), DoublePinyinGlide.Key("ty", 5.0, 0.0),
            DoublePinyinGlide.Key("ui", 7.0, 0.0), DoublePinyinGlide.Key("df", 3.0, 1.15),
            DoublePinyinGlide.Key("gh", 5.0, 1.15), DoublePinyinGlide.Key("jk", 7.0, 1.15),
            DoublePinyinGlide.Key("bn", 7.0, 2.30),
        )
        fun point(c: Char) = keys.first { c in it.letters }.let { DoublePinyinGlide.Point(it.x, it.y) }
        fun toward(a: DoublePinyinGlide.Point, b: DoublePinyinGlide.Point, fraction: Double) =
            DoublePinyinGlide.Point(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)

        val (n, i, d, e) = "nide".map(::point)
        for ((cut, lift) in listOf(0.28 to 0.43, 0.25 to 0.55)) {
            val turns = listOf(n, toward(i, n, cut), toward(i, d, cut),
                toward(d, i, cut * 0.45), toward(d, e, cut), toward(e, d, lift))
            val path = turns.zipWithNext().flatMap { (a, b) -> (0..5).map { toward(a, b, it / 5.0) } }
                .mapIndexed { index, point -> point.copy(t = index * 8L) }
            for (live in listOf(false, true)) {
                val results = DoublePinyinGlide.decode(path, keys, table, active, true, dictionary, ngrams, live = live)
                assertEquals("$cut $lift $live ${results.take(4)}", "nide", results.firstOrNull()?.reading)
                val buffer = DoublePinyinGlide.bufferFor(results.first(), table, active, true)!!
                assertEquals(listOf("ni", "de"), DoublePinyinGlide.syllableBuffers(buffer, table, active, true).map { it.first })
            }
        }
    }

    @Test fun `unlisted phrase survives both Compact and ordinary double Pinyin search`() {
        for (compact in listOf(true, false)) {
            val keys = keys(compact)
            val result = DoublePinyinGlide.decode(points("yzgrxi", keys), keys, table, inventory, compact, dict, CjkNgrams.EMPTY)
            assertTrue("$compact $result", result.isNotEmpty())
            assertEquals("$compact $result", "youguanxi", result.first().reading)
        }
    }
    @Test fun `adjacent final keys remain distinct and common whole words stay available`() {
        for (compact in listOf(true, false)) for ((code, reading) in listOf("ycgrxi" to "yaoguanxi", "nihc" to "nihao")) {
            val keys = keys(compact)
            val result = DoublePinyinGlide.decode(points(code, keys), keys, table, inventory, compact, dict, CjkNgrams.EMPTY)
            assertEquals("$compact $code $result", reading, result.first().reading)
        }
    }
    @Test fun `sampling density does not change the decoded phrase`() {
        val keys = keys(true); val path = points("yzgrxi", keys)
        val a = DoublePinyinGlide.decode(path, keys, table, inventory, true, dict, CjkNgrams.EMPTY)
        val b = DoublePinyinGlide.decode(path.flatMap { listOf(it,it,it) }, keys, table, inventory, true, dict, CjkNgrams.EMPTY)
        assertEquals(a,b)
    }
    @Test fun `same physical key can spell both halves of a syllable`() {
        val keys = keys(true); val path = points("nixx", keys)
        val result = DoublePinyinGlide.decode(path, keys, table, inventory, true, dict, CjkNgrams.EMPTY)
        assertTrue(result.toString(), result.any { it.reading == "nixia" })
    }
    @Test fun `all built in schemes retain unlisted phrases on both boards`() {
        for (scheme in DoublePinyinScheme.entries.filter { it != DoublePinyinScheme.OFF && it != DoublePinyinScheme.CUSTOM }) {
            val active = DoublePinyin.tableFor(scheme)!!
            val codes = DoublePinyin.keysOf(active, inventory)
            val code = listOf("you", "guan", "xi").joinToString("") { codes.getValue(it) }
            for (compact in listOf(true, false)) {
                val keys = keys(compact)
                val result = DoublePinyinGlide.decode(points(code, keys), keys, active, inventory, compact, dict, CjkNgrams.EMPTY)
                // Pinyin++ on the merged board can draw you-xi and you-guan-xi on the same line.
                // Both must survive; geometry alone cannot choose how many syllables were intended.
                if (scheme == DoublePinyinScheme.PINYINPP) assertTrue(result.toString(), result.any { it.reading == "youguanxi" })
                else assertEquals("$scheme $compact $result", "youguanxi", result.first().reading)
            }
        }
    }
    @Test fun `Chinese learned phrases participate in selecting a physical path`() {
        val keys = keys(true)
        val dictionary = ConversionDictionary.parse(sequenceOf("you\t有\t923", "tou\t头\t1500", "guanxi\t关系\t664"))
        val history = CjkUserHistory(null)
        val path = points("yzgrxi", keys)
        val before = DoublePinyinGlide.decode(path, keys, table, inventory, true, dictionary, CjkNgrams.EMPTY)
        repeat(50) { history.learn("pinyin", "youguanxi", "有关系") }
        val after = DoublePinyinGlide.decode(path, keys, table, inventory, true, dictionary, CjkNgrams.EMPTY, history.userData("pinyin"))
        assertEquals("youguanxi", after.first().reading)
        assertTrue(after.first().score > before.first().score)
    }
    @Test fun `moderate corner cutting preserves the missing phrase`() {
        val keys = keys(true)
        val path = points("yzgrxi", keys).mapIndexed { i,p -> DoublePinyinGlide.Point(p.x + if (i % 2 == 0) 0.10 else -0.10, p.y + 0.08) }
        val result = DoublePinyinGlide.decode(path, keys, table, inventory, true, dict, CjkNgrams.EMPTY)
        assertEquals(result.toString(), "youguanxi", result.first().reading)
    }
    @Test fun `uniform slowing preserves ranking while a pause marks an intentional key`() {
        val keys=keys(false)
        var clock=0L
        val path=points("yzgrxi",keys).map { p -> clock+=20; DoublePinyinGlide.Point(p.x,p.y,clock) }
        val slow=path.map { it.copy(t=it.t*10) }
        val a=DoublePinyinGlide.decode(path,keys,table,inventory,false,dict,CjkNgrams.EMPTY)
        val b=DoublePinyinGlide.decode(slow,keys,table,inventory,false,dict,CjkNgrams.EMPTY)
        assertEquals(a.first().reading,b.first().reading)
        val g=keys.first { "g" in it.letters }
        val start=keys.first { "y" in it.letters }; val end=keys.first { "x" in it.letters }
        val deliberate=listOf(
            DoublePinyinGlide.Point(start.x,start.y,0),DoublePinyinGlide.Point(g.x,g.y,100),
            DoublePinyinGlide.Point(g.x,g.y,500),DoublePinyinGlide.Point(end.x,end.y,600))
        val active=DoublePinyinGlide.Search(deliberate,keys,DoublePinyin.keysOf(table,inventory).values,2.0)
        val disabled=DoublePinyinGlide.Search(deliberate,keys,DoublePinyin.keysOf(table,inventory).values,0.0)
        assertTrue(active.cost("yx") > disabled.cost("yx"))
    }
    @Test fun `a large dictionary does not turn one same-key syllable into repeated syllables`() {
        val keys=listOf(DoublePinyinGlide.Key("m",0.0,0.0))
        val dictionary=ConversionDictionary.parse(sequenceOf("mian\t面\t1000", "mianmian\t绵绵\t50", "unrelated\t无关\t100000000"))
        val loop=List(24) { i -> val angle=i*2*Math.PI/23; DoublePinyinGlide.Point(0.2*kotlin.math.cos(angle),0.2*kotlin.math.sin(angle)) }
        val result=DoublePinyinGlide.decode(loop,keys,table,setOf("mian"),true,dictionary,CjkNgrams.EMPTY)
        assertEquals(result.toString(),"mian",result.first().reading)
        assertTrue(result.any { it.reading == "mianmian" })
    }
    @Test fun `offset is measured along the alignment in physical key widths`() {
        val keys=listOf(DoublePinyinGlide.Key("a",0.0,0.0),DoublePinyinGlide.Key("b",4.0,0.0))
        val path=List(20) { DoublePinyinGlide.Point(it*4.0/19,0.25) }
        val search=DoublePinyinGlide.Search(path,keys,listOf("ab"))
        assertEquals(0.25,search.deviation("ab").rms,0.001)
        assertEquals(0.25,search.deviation("ab").maximum,0.001)
        val wide=keys.map { it.copy(width=2.0) }
        assertEquals(0.125,DoublePinyinGlide.Search(path,wide,listOf("ab")).deviation("ab").rms,0.001)
    }
    @Test fun `nearby start key survives as a lower ranked correction`() {
        val keys=listOf(DoublePinyinGlide.Key("a",0.0,0.0),DoublePinyinGlide.Key("b",2.0,0.0),DoublePinyinGlide.Key("c",4.0,0.0))
        val path=List(20) { DoublePinyinGlide.Point(it*4.0/19,0.0) }
        val scheme=DoublePinyin.Table(emptyMap(),emptyMap(),codes=mapOf("ac" to "ni","bc" to "bi"))
        val dictionary=ConversionDictionary.parse(sequenceOf("ni\t你\t100", "bi\t鼻\t100"))
        val result=DoublePinyinGlide.decode(path,keys,scheme,setOf("ni","bi"),false,dictionary,CjkNgrams.EMPTY)
        assertEquals(result.toString(),"ni",result.first().reading)
        val corrected=result.first { it.reading=="bi" }
        assertTrue(corrected.neighbour)
        assertTrue(corrected.score<result.first().score)
        assertTrue(corrected.deviation>result.first().deviation)
        assertTrue(result.size<=80)

        for (correction in listOf(null, PinyinCorrection.Tuning(confidence = 10.0))) {
            val strict = DoublePinyinGlide.decode(
                path, keys, scheme, setOf("ni", "bi"), false, dictionary, CjkNgrams.EMPTY, correction = correction,
            )
            assertEquals("ni", strict.first().reading)
            assertTrue(strict.none { it.neighbour })
        }
    }
    @Test fun `an explicit full reading constrains all ambiguous syllables`() {
        val active=inventory+"bi"
        val dictionary=ConversionDictionary.parse(sequenceOf("ni\t你\t100", "bi\t鼻\t100", "hao\t好\t100", "nihao\t你好\t1000", "bihao\t鼻好\t100"))
        val keys=keys(true)
        val results=DoublePinyinGlide.decode(points("nihc",keys),keys,table,active,true,dictionary,CjkNgrams.EMPTY)
        assertTrue(results.toString(),results.any { it.reading=="bihao" })
        CjkConfig.doublePinyin=DoublePinyinScheme.XIAOHE
        PinyinSyllables.valid=active;CjkDictionaries.pinyin=dictionary
        try {
            val result=results.first { it.reading=="bihao" }
            val buffer=DoublePinyinGlide.bufferFor(result,table,active,true)!!
            assertEquals("鼻好",CompactPinyinComposer.candidates(buffer).first())
            assertEquals("bihao",CompactPinyinComposer.composeBuffer(buffer))
        } finally {
            CjkConfig.doublePinyin=DoublePinyinScheme.OFF
            PinyinSyllables.valid=emptySet();CjkDictionaries.pinyin=ConversionDictionary.EMPTY
        }
    }
    @Test fun `reported offset uses visible centers while ranking may use learned centers`() {
        val visible=listOf(DoublePinyinGlide.Key("a",0.0,0.0),DoublePinyinGlide.Key("b",4.0,0.0))
        val learned=visible.map { it.copy(y=0.25) }
        val path=List(20) { DoublePinyinGlide.Point(it*4.0/19,0.25) }
        val scheme=DoublePinyin.Table(emptyMap(),emptyMap(),codes=mapOf("ab" to "ni"))
        val dictionary=ConversionDictionary.parse(sequenceOf("ni\t你\t100"))
        val normal=DoublePinyinGlide.decode(path,learned,scheme,setOf("ni"),false,dictionary,CjkNgrams.EMPTY).first()
        val measured=DoublePinyinGlide.decode(path,learned,scheme,setOf("ni"),false,dictionary,CjkNgrams.EMPTY,referenceKeys=visible).first()
        assertEquals(normal.score,measured.score,0.0001)
        assertEquals(0.0,normal.deviation,0.001)
        assertEquals(0.25,measured.deviation,0.001)
    }
    @Test fun `a whole dictionary word does not hide a composable reading on the same keys`() {
        val active=setOf("bu","ni","xi","huan")
        val dictionary=ConversionDictionary.parse(sequenceOf(
            "buxihuan\t不喜欢\t1000", "ni\t你\t1000", "xihuan\t喜欢\t1000", "unrelated\t无关\t100000000"))
        val keys=keys(true)
        val results=DoublePinyinGlide.decode(points("nixihr",keys),keys,table,active,true,dictionary,CjkNgrams.EMPTY)
        assertTrue(results.toString(),results.any { it.reading=="buxihuan" })
        assertTrue(results.toString(),results.any { it.reading=="nixihuan" })
        CjkConfig.doublePinyin=DoublePinyinScheme.XIAOHE
        PinyinSyllables.valid=active;CjkDictionaries.pinyin=dictionary
        try {
            val choice=results.first { it.reading=="nixihuan" }
            val buffer=DoublePinyinGlide.bufferFor(choice,table,active,true)!!
            assertEquals("你喜欢",CompactPinyinComposer.candidates(buffer).first())
        } finally {
            CjkConfig.doublePinyin=DoublePinyinScheme.OFF
            PinyinSyllables.valid=emptySet();CjkDictionaries.pinyin=ConversionDictionary.EMPTY
        }
    }
    @Test fun `empty and invalid input is bounded and produces no candidates`() {
        val keys = keys(true)
        assertTrue(DoublePinyinGlide.decode(emptyList(), keys, table, inventory, true, dict, CjkNgrams.EMPTY).isEmpty())
        assertTrue(DoublePinyinGlide.decode(listOf(DoublePinyinGlide.Point(Double.NaN,0.0)), keys, table, inventory, true, dict, CjkNgrams.EMPTY).isEmpty())
        val paths = DoublePinyinGlide.Search(points("yzgrxiyzgrxi",keys), keys, DoublePinyin.keysOf(table,inventory).values).paths()
        assertTrue(paths.size <= 64)
        assertTrue(paths.all { it.code.length <= 24 && it.code.length % 2 == 0 })
    }
}
