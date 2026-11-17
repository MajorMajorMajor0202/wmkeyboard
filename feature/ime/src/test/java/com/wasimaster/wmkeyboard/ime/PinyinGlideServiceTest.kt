package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.input.composer.CjkConfig
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyin
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinScheme
import com.wasimaster.wmkeyboard.core.input.composer.PinyinSyllables
import com.wasimaster.wmkeyboard.core.input.composer.resolvedComposer
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.letterSet
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real service readiness, native stroke decoding and conversion-buffer handoff. */
@RunWith(RobolectricTestRunner::class)
class PinyinGlideServiceTest {
    private val services = mutableListOf<GlideKeyboard>()
    private val inventory = setOf("ni", "hao")

    @Before fun load() {
        PinyinSyllables.valid = inventory
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("ni\t你\t100", "hao\t好\t100", "nihao\t你好\t1000"))
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
        CjkConfig.doublePinyinSyllableDelete = false
    }

    @After fun reset() {
        services.forEach { service ->
            (WMKeyboardService::class.java.getDeclaredField("serviceScope").apply { isAccessible = true }.get(service) as CoroutineScope).cancel()
        }
        PinyinSyllables.valid = emptySet()
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkConfig.doublePinyin = DoublePinyinScheme.OFF
    }

    private fun keyboard(spec: LayoutSpec, scheme: DoublePinyinScheme): Pair<GlideKeyboard, RecordingEditor> {
        CjkConfig.doublePinyin = scheme
        val editor = RecordingEditor()
        val service = GlideKeyboard(editor)
        services += service
        plantPersonalStores(service)
        val settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false))
        val layouts = LayoutSet(spec.compile(LayoutLayer.LETTERS), spec.compile(LayoutLayer.SYMBOLS), spec.compile(LayoutLayer.SYMBOLS_SHIFTED))
        seedState(service, glideReadyState(glideReady = false, fieldNoSuggestions = false,
            settings = settings.copy(cjk = settings.cjk.copy(pinyinDoublePinyin = scheme))).copy(
            layouts = layouts, composer = spec.resolvedComposer(), language = spec.language(), script = spec.script(), layoutId = spec.id,
        ))
        val engine = SuggestionEngine(Trie(), BengaliPhoneticIndex(emptyList()), UserLexicon(null)).apply { primaryLanguageId = "zh" }
        WMKeyboardService::class.java.getDeclaredField("suggestionEngine").apply { isAccessible = true }.set(service, engine)
        WMKeyboardService::class.java.getDeclaredMethod("startGlideReadinessWatcher").apply { isAccessible = true }.invoke(service)
        settle { service.uiState.value.glideReady }
        assertTrue("Pinyin glide did not become ready", service.uiState.value.glideReady)
        return service to editor
    }

    private fun swipe(service: GlideKeyboard, code: String, expected: String = "你好") {
        val layouts = service.uiState.value.layouts
        val anchors = mutableMapOf<Int, Pair<Float, Float>>()
        val letters = mutableMapOf<Char, Pair<Float, Float>>()
        for ((rowIndex, row) in layouts.letters.rows.withIndex()) {
            val width = row.sumOf { it.width.toDouble() }.toFloat()
            var x = (10f - width) / 2f
            for (key in row) {
                val at = ((x + key.width / 2f) * GLIDE_KEY_WIDTH) to ((rowIndex + 0.5f) * GLIDE_KEY_WIDTH)
                if (key.action == KeyAction.Text) {
                    val set = key.letterSet()
                    set.firstOrNull()?.let { anchors[it.code] = at }
                    set.forEach { letters[it] = at }
                }
                x += key.width
            }
        }
        val points = mutableListOf<GesturePoint>()
        for (segment in 0 until code.length - 1) {
            val from = letters.getValue(code[segment])
            val to = letters.getValue(code[segment + 1])
            for (step in 0..10) {
                val f = step / 10f
                points += GesturePoint(from.first + (to.first - from.first) * f, from.second + (to.second - from.second) * f, points.size * 20L)
            }
        }
        service.onGesture(points, layouts.glideKeys { anchors[it] }, GLIDE_KEY_WIDTH)
        settle { expected in service.uiState.value.suggestions }
        assertTrue(service.uiState.value.suggestions.toString(), expected in service.uiState.value.suggestions)
    }

    @Test fun `unlisted phrase glides as one stroke on Compact and ordinary double Pinyin`() {
        PinyinSyllables.valid = setOf("you", "yao", "guan", "xi", "tou", "yong", "chi")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "you\t有\t923", "yao\t要\t902", "guan\t关\t800", "xi\t系\t700",
            "guanxi\t关系\t664", "youyongchi\t游泳池\t501",
        ))
        for (compact in listOf(true, false)) {
            val spec = if (compact) BuiltInLayouts.COMPACT_PINYIN else BuiltInLayouts.QWERTY.copy(
                langId = "zh", composer = com.wasimaster.wmkeyboard.core.script.ComposerType.PINYIN)
            val (service, editor) = keyboard(spec, DoublePinyinScheme.XIAOHE)
            swipe(service, "yzgrxi", "有关系")
            assertEquals("有关系", service.uiState.value.suggestions.first())
            assertEquals("youguanxi", editor.text.toString())
            assertTrue(editor.commits.isEmpty())
        }
    }

    @Test fun `a whole glide alternative can be selected and stale alternatives cannot edit a changed buffer`() {
        PinyinSyllables.valid = setOf("you", "yao", "guan", "xi", "tou", "ni")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "you\t有\t923", "yao\t要\t902", "guanxi\t关系\t664", "ni\t你\t1000",
        ))
        val (service, editor) = keyboard(BuiltInLayouts.COMPACT_PINYIN, DoublePinyinScheme.XIAOHE)
        swipe(service, "ni", "你")
        swipe(service, "yzgrxi", "你有关系")
        settle { "yaoguanxi" in service.uiState.value.conversionReadings }
        assertTrue(service.uiState.value.conversionReadings.toString(), "yaoguanxi" in service.uiState.value.conversionReadings)
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("yaoguanxi", output = "yaoguanxi", action = KeyAction.ReadingColumn))
        settle { service.uiState.value.suggestions.firstOrNull() == "你要关系" && editor.text.toString() == "niyaoguanxi" }
        assertEquals("你要关系", service.uiState.value.suggestions.first())
        assertEquals("niyaoguanxi", editor.text.toString())
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("⌫", action = KeyAction.Delete))
        settle { "yaoguanxi" !in service.uiState.value.conversionReadings }
        val afterDelete = editor.text.toString()
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("youguanxi", output = "youguanxi", action = KeyAction.ReadingColumn))
        assertEquals(afterDelete, editor.text.toString())
        assertTrue(editor.commits.isEmpty())
    }

    @Test fun `weighted full reading choices constrain the candidate and clear after editing`() {
        PinyinSyllables.valid=inventory+"bi"
        CjkDictionaries.pinyin=ConversionDictionary.parse(sequenceOf(
            "ni\t你\t100", "bi\t鼻\t100", "hao\t好\t100", "nihao\t你好\t1000", "bihao\t鼻好\t100"))
        val (service,editor)=keyboard(BuiltInLayouts.COMPACT_PINYIN,DoublePinyinScheme.XIAOHE)
        swipe(service,"nihc")
        settle { "bihao" in service.uiState.value.conversionReadingHints }
        val hints=service.uiState.value.conversionReadingHints
        assertTrue(hints.toString(),"nihao" in hints && "bihao" in hints)
        assertTrue(hints.getValue("nihao").weight>hints.getValue("bihao").weight)
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("bihao",output="bihao",action=KeyAction.ReadingColumn))
        settle { service.uiState.value.suggestions.firstOrNull()=="鼻好" && editor.text.toString()=="bihao" }
        assertEquals("鼻好",service.uiState.value.suggestions.first())
        assertEquals("bihao",editor.text.toString())
        assertTrue(service.uiState.value.conversionReadingHints.getValue("bihao").selected)
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("",output=EditGlideReading,action=KeyAction.ReadingColumn))
        settle { service.uiState.value.conversionReadingHints.isEmpty() && "bi" in service.uiState.value.conversionReadings }
        assertTrue("bi" in service.uiState.value.conversionReadings)
        assertTrue(editor.commits.isEmpty())
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("⌫",action=KeyAction.Delete))
        settle { service.uiState.value.conversionReadingHints.isEmpty() }
        assertTrue(service.uiState.value.conversionReadingHints.isEmpty())
    }

    @Test fun `an adjacent physical key is a lower weight selectable correction`() {
        PinyinSyllables.valid=setOf("na","ma")
        CjkDictionaries.pinyin=ConversionDictionary.parse(sequenceOf("na\t哪\t100", "ma\t吗\t100", "mama\t妈妈\t10"))
        val (service,editor)=keyboard(BuiltInLayouts.COMPACT_PINYIN,DoublePinyinScheme.XIAOHE)
        swipe(service,"na","哪")
        settle { "ma" in service.uiState.value.conversionReadingHints }
        val hints=service.uiState.value.conversionReadingHints
        assertTrue(hints.toString(),hints.getValue("ma").neighbour)
        assertTrue(hints.getValue("ma").weight<hints.getValue("na").weight)
        assertTrue(hints.getValue("ma").deviation>hints.getValue("na").deviation)
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("ma",output="ma",action=KeyAction.ReadingColumn))
        settle { service.uiState.value.suggestions.firstOrNull()=="吗" && editor.text.toString()=="ma" }
        assertEquals("吗",service.uiState.value.suggestions.first())
        assertEquals("ma",editor.text.toString())
    }

    @Test fun `candidate ranking and highlight synchronize the field reading`() {
        PinyinSyllables.valid = inventory + "nin"
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "ni\t你\t1", "nin\t您\t100000", "nihao\t你好\t1000",
        ))
        val (service, editor) = keyboard(BuiltInLayouts.COMPACT_PINYIN, DoublePinyinScheme.OFF)
        val type = WMKeyboardService::class.java
        val buffer = type.getDeclaredField("composing").apply { isAccessible = true }.get(service) as StringBuilder
        buffer.append("ni")
        type.getDeclaredMethod("refreshSuggestions").apply { isAccessible = true }.invoke(service)
        settle { service.uiState.value.suggestions.firstOrNull() == "您" && editor.text.toString() == "nin" }
        assertEquals("nin", editor.text.toString())
        val target = service.uiState.value.suggestions.indexOf("你")
        assertTrue(target >= 0)
        type.getDeclaredMethod("moveCandidateCursor", Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(service, target)
        assertEquals("ni", editor.text.toString())
    }

    @Test fun `Compact double pinyin glide retains alternative syllable choices`() {
        PinyinSyllables.valid = inventory + "bi"
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "ni\t你\t100", "bi\t鼻\t100", "hao\t好\t100", "nihao\t你好\t1000", "bihao\t鼻好\t10",
        ))
        val (service, _) = keyboard(BuiltInLayouts.COMPACT_PINYIN, DoublePinyinScheme.XIAOHE)
        swipe(service, "nihc")
        assertTrue("ni" in service.uiState.value.conversionReadings)
        assertTrue("bi" in service.uiState.value.conversionReadings)
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("bi", output = "bi", action = KeyAction.ReadingColumn))
        settle { "鼻好" in service.uiState.value.suggestions && "你好" !in service.uiState.value.suggestions }
        assertTrue("鼻好" in service.uiState.value.suggestions)
        assertTrue("你好" !in service.uiState.value.suggestions)
    }

    private fun editorWithStrandedReading(reportCaret: Boolean = false): Pair<GlideKeyboard, android.view.inputmethod.BaseInputConnection> {
        val editor = object : android.view.inputmethod.BaseInputConnection(android.view.View(org.robolectric.RuntimeEnvironment.getApplication()), true) {
            override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest?, flags: Int): android.view.inputmethod.ExtractedText? {
                if (!reportCaret) return null
                return android.view.inputmethod.ExtractedText().apply {
                    text=editable.toString()
                    selectionStart=android.text.Selection.getSelectionStart(editable)
                    selectionEnd=android.text.Selection.getSelectionEnd(editable)
                    startOffset=0
                }
            }
        }
        editor.setComposingText("mian", 1)
        val service = GlideKeyboard(editor)
        services += service
        plantPersonalStores(service)
        seedState(service, glideReadyState(glideReady = false, fieldNoSuggestions = false).copy(
            composer = com.wasimaster.wmkeyboard.core.input.composer.CompactPinyinComposer,
        ))
        return service to editor
    }

    @Test fun `visible pinyin region lost by Firefox is restored before committing`() {
        val (service, editor)=editorWithStrandedReading()
        val type=WMKeyboardService::class.java
        val buffer=type.getDeclaredField("composing").apply { isAccessible=true }.get(service) as StringBuilder
        buffer.append("ka")
        editor.setComposingText("ka",1)
        editor.finishComposingText()
        service.onUpdateSelection(2,2,2,2,-1,-1)
        assertEquals(0,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.editable!!))
        assertEquals(2,android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(editor.editable!!))
        editor.commitText("卡",1)
        assertEquals("卡",editor.editable.toString())
        assertEquals("ka",buffer.toString())
    }

    @Test fun `queued selection echo cannot finish a newer pinyin region as Latin`() {
        val (service,editor)=editorWithStrandedReading(reportCaret=true)
        val buffer=WMKeyboardService::class.java.getDeclaredField("composing").apply { isAccessible=true }.get(service) as StringBuilder
        buffer.append("nihao")
        editor.setComposingText("nihao",1)
        service.onUpdateSelection(1,1,2,2,0,1)
        assertEquals("nihao",buffer.toString())
        assertEquals("nihao",editor.editable.toString())
        assertEquals(0,android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.editable!!))
        assertEquals(5,android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(editor.editable!!))
    }

    @Test fun `stale region with current caret keeps si f reading without extracted text`() {
        val (service, editor) = editorWithStrandedReading()
        val buffer = WMKeyboardService::class.java.getDeclaredField("composing")
            .apply { isAccessible = true }.get(service) as StringBuilder
        buffer.append("sif")
        editor.setComposingText("sif", 1)
        // A browser can report the current caret together with the previous
        // composing bounds. Extraction is unsupported, as in some editors.
        service.onUpdateSelection(2, 2, 3, 3, 0, 2)
        assertEquals("sif", WMKeyboardService::class.java.getDeclaredField("composing")
            .apply { isAccessible = true }.get(service).toString())
        assertEquals(0, android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.editable!!))
        assertEquals(3, android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(editor.editable!!))
        editor.commitText("司法", 1)
        assertEquals("司法", editor.editable.toString())
    }

    @Test fun `caret moved away from visible reading still releases composition`() {
        val (service, editor) = editorWithStrandedReading()
        val type = WMKeyboardService::class.java
        (type.getDeclaredField("composing").apply { isAccessible = true }
            .get(service) as StringBuilder).append("sif")
        editor.setComposingText("sif", 1)
        editor.setSelection(0, 0)
        service.onUpdateSelection(3, 3, 0, 0, 1, 3)
        assertEquals("", type.getDeclaredField("composing").apply { isAccessible = true }.get(service).toString())
        assertEquals(-1, android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.editable!!))
    }

    @Test fun `backspace clears an orphan editor span after returning to a field`() {
        val (service, editor) = editorWithStrandedReading()
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("⌫", action = KeyAction.Delete))
        assertEquals("mia", editor.editable.toString())
        repeat(3) { service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("⌫", action = KeyAction.Delete)) }
        assertEquals("", editor.editable.toString())
    }

    @Test fun `hide keeps pending letters and releases both editor region and buffer`() {
        val (service, editor) = editorWithStrandedReading()
        val type = WMKeyboardService::class.java
        (type.getDeclaredField("composing").apply { isAccessible = true }.get(service) as StringBuilder).append("mm")
        type.getDeclaredMethod("finishComposingForHide").apply { isAccessible = true }.invoke(service)
        assertEquals("mian", editor.editable.toString())
        assertEquals(-1, android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editor.editable!!))
        assertEquals("", type.getDeclaredField("composing").apply { isAccessible = true }.get(service).toString())
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("⌫", action = KeyAction.Delete))
        assertEquals("mia", editor.editable.toString())
    }

    @Test fun `Compact grouped-key swipe becomes a revisable Chinese reading`() {
        val (service, editor) = keyboard(BuiltInLayouts.COMPACT_PINYIN, DoublePinyinScheme.OFF)
        swipe(service, "nihao")
        assertEquals("nihao", editor.text.toString())
        assertTrue(editor.commits.isEmpty())
    }

    @Test fun `double Pinyin swipe hands actual scheme keys to the composer`() {
        val spec = BuiltInLayouts.QWERTY.copy(langId = "zh", composer = com.wasimaster.wmkeyboard.core.script.ComposerType.PINYIN)
        val (service, editor) = keyboard(spec, DoublePinyinScheme.MICROSOFT)
        val keys = DoublePinyin.keysOf(DoublePinyin.tableFor(DoublePinyinScheme.MICROSOFT)!!, inventory)
        val code = keys.getValue("ni") + keys.getValue("hao")
        swipe(service, code)
        assertEquals("nihao", editor.text.toString())
        val buffer = WMKeyboardService::class.java.getDeclaredField("composing").apply { isAccessible = true }.get(service).toString()
        assertEquals(code, buffer)
    }

    @Test fun `resize starts and cancels without disabling one handed mode`() {
        val (service, _) = keyboard(BuiltInLayouts.COMPACT_PINYIN, DoublePinyinScheme.OFF)
        seedState(service, service.uiState.value.copy(settings = service.uiState.value.settings.copy(oneHandedMode = OneHandedMode.RIGHT)))
        service.onResizeToggle()
        assertTrue(service.uiState.value.resize)
        assertEquals(OneHandedMode.RIGHT, service.uiState.value.settings.oneHandedMode)
        service.onSizingAction(SizingAction.ResizeCancel)
        assertEquals(OneHandedMode.RIGHT, service.uiState.value.settings.oneHandedMode)
    }

    @Test fun `thirteen key preview never edits field and confirmed repeated key retains selected reading`() {
        PinyinSyllables.valid=setOf("mian")
        CjkDictionaries.pinyin=ConversionDictionary.parse(sequenceOf("mian\t绵\t100","mianmian\t绵绵\t1000"))
        val (service,editor)=keyboard(BuiltInLayouts.PINYIN_13,DoublePinyinScheme.XIAOHE)
        val state=service.uiState.value
        seedState(service,state.copy(settings=state.settings.copy(cjk=state.settings.cjk.copy(pinyinGlideMode=2))))
        val keys=listOf(com.wasimaster.wmkeyboard.core.gesture.KeyCenter('l'.code,0f,0f),
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('m'.code,0f,0f))
        val points=listOf(GesturePoint(0f,0f,100),GesturePoint(0f,0f,500))
        service.onGesture(points,keys,GLIDE_KEY_WIDTH,com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Pinyin(preview=true))
        settle { service.uiState.value.pinyinGlidePreview!=null }
        assertEquals("",editor.text.toString())
        val choice=service.uiState.value.pinyinGlidePreview!!.choices.first()
        assertEquals("mian",choice.reading)
        service.onGesture(emptyList(),keys,GLIDE_KEY_WIDTH,
            com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Pinyin(prefix=listOf(choice)))
        settle { "绵" in service.uiState.value.suggestions }
        assertEquals("mian",editor.text.toString())
        assertTrue(editor.commits.isEmpty())
        assertTrue(UndoReadingSelection in service.uiState.value.conversionReadings)
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("",output=UndoReadingSelection,action=KeyAction.ReadingColumn))
        settle { UndoReadingSelection !in service.uiState.value.conversionReadings }
        assertTrue(editor.commits.isEmpty())
    }
    @Test fun `two key preview is immediate and transit keys never change endpoint readings`() {
        for(spec in listOf(BuiltInLayouts.PINYIN_13,BuiltInLayouts.COMPACT_PINYIN)) {
            val (service,editor)=keyboard(spec,DoublePinyinScheme.XIAOHE)
            val state=service.uiState.value
            seedState(service,state.copy(settings=state.settings.copy(cjk=state.settings.cjk.copy(pinyinGlideMode=2))))
            val keys=listOf(
                com.wasimaster.wmkeyboard.core.gesture.KeyCenter('b',0f,0f),
                com.wasimaster.wmkeyboard.core.gesture.KeyCenter('n',0f,0f),
                com.wasimaster.wmkeyboard.core.gesture.KeyCenter('u',100f,0f),
                com.wasimaster.wmkeyboard.core.gesture.KeyCenter('i',100f,0f),
                com.wasimaster.wmkeyboard.core.gesture.KeyCenter('h',50f,0f))
            val endpoints=listOf(GesturePoint(0f,0f,100),GesturePoint(100f,0f,200))
            service.onGesture(endpoints,keys,GLIDE_KEY_WIDTH,com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Pinyin(preview=true))
            // The bounded lookup must publish without pumping the main looper.
            val first=service.uiState.value.pinyinGlidePreview!!
            assertTrue(first.choices.toString(),first.choices.any { it.reading=="ni" })
            assertEquals("",editor.text.toString())
            val transit=endpoints.take(1)+listOf(GesturePoint(50f,0f,150))+endpoints.takeLast(1)
            service.onGesture(transit,keys,GLIDE_KEY_WIDTH,com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Pinyin(preview=true))
            assertEquals(first.choices.map { Triple(it.reading,it.buffer,it.pairSlot) },service.uiState.value.pinyinGlidePreview!!.choices.map { Triple(it.reading,it.buffer,it.pairSlot) })
        }
    }

}
