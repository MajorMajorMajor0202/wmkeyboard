package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.input.composer.CjkConfig
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyin
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinScheme
import com.wasimaster.wmkeyboard.core.input.composer.Jianpin
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
import com.wasimaster.wmkeyboard.core.settings.AutocorrectSettings
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
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
        CjkConfig.doublePinyinSyllableDelete = false
    }

    private fun keyboard(
        spec: LayoutSpec,
        scheme: DoublePinyinScheme,
        correction: AutocorrectSettings = AutocorrectSettings(),
        editor: RecordingEditor = RecordingEditor(),
    ): Pair<GlideKeyboard, RecordingEditor> {
        CjkConfig.doublePinyin = scheme
        val service = GlideKeyboard(editor)
        services += service
        plantPersonalStores(service)
        val settings = KeyboardSettings(
            learnFromTyping = false, haptics = HapticSettings(enabled = false), correction = correction,
        )
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

    @Test fun `global and language autocorrect switches retain direct readings and manual syllable choices`() {
        PinyinSyllables.valid = setOf("na", "ma")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("na\t\u90a3\t100", "ma\t\u5988\t100", "mama\t\u5988\u5988\t10"))
        val spec = BuiltInLayouts.COMPACT_PINYIN
        val settings = listOf(AutocorrectSettings(enabled = false), AutocorrectSettings(offLanguages = setOf(spec.language().id)))
        for (correction in settings) {
            val (service, editor) = keyboard(spec, DoublePinyinScheme.XIAOHE, correction)
            swipe(service, "na", "\u90a3")
            assertEquals("na", editor.text.toString())
            assertTrue(service.uiState.value.conversionReadingHints.values.none { it.neighbour })
            assertTrue("\u5988" !in service.uiState.value.suggestions)

            service.onCandidatesExpand()
            settle { service.uiState.value.pinyinReadingCorrections.isNotEmpty() }
            val alternate = service.uiState.value.pinyinReadingCorrections.entries.single { it.value.reading == "ma" }.key
            service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("ma", output = alternate, action = KeyAction.ReadingColumn))
            settle { service.uiState.value.suggestions.firstOrNull() == "\u5988" }
            assertEquals("ma", editor.text.toString())
        }
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
        service.onCandidatesExpand()
        settle { service.uiState.value.pinyinReadingCorrections.isNotEmpty() }
        val choices = service.uiState.value.pinyinReadingCorrections
        assertTrue(choices.values.any { it.reading == "ni" && it.slot == 0 })
        val alternate = choices.entries.single { it.value.reading == "bi" && it.value.slot == 0 }.key
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("bi", output = alternate, action = KeyAction.ReadingColumn))
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

    @Test fun `thirteen key preview composes in the field and confirms a repeated key once`() {
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
        assertEquals("mian",editor.text.toString())
        assertTrue(editor.commits.isEmpty())
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
            assertEquals(first.choices.first().reading, editor.text.toString())
            assertTrue(editor.commits.isEmpty())
            val transit=endpoints.take(1)+listOf(GesturePoint(50f,0f,150))+endpoints.takeLast(1)
            service.onGesture(transit,keys,GLIDE_KEY_WIDTH,com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Pinyin(preview=true))
            assertEquals(first.choices.map { Triple(it.reading,it.buffer,it.pairSlot) },service.uiState.value.pinyinGlidePreview!!.choices.map { Triple(it.reading,it.buffer,it.pairSlot) })
        }
    }

    @Test fun `endpoint previews keep their prefix through edits and invalid pairs`() {
        PinyinSyllables.valid = inventory + setOf("na", "nan")
        val writes = mutableListOf<String>()
        val editor = object : RecordingEditor(initial = "word ") {
            override fun beginBatchEdit(): Boolean = true
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                writes += text.toString()
                return super.setComposingText(text, newCursorPosition)
            }
        }
        val (service, _) = keyboard(BuiltInLayouts.PINYIN_13, DoublePinyinScheme.XIAOHE, editor = editor)
        val state = service.uiState.value
        seedState(service, state.copy(settings = state.settings.copy(cjk = state.settings.cjk.copy(pinyinGlideMode = 2))))
        val keys = listOf("bn", "ui", "as", "jk", "op").flatMapIndexed { index, group ->
            group.map { KeyCenter(it, index * GLIDE_KEY_WIDTH, 0f) }
        }
        fun preview(endpoint: Int, time: Long) {
            val points = listOf(GesturePoint(0f, 0f, 100), GesturePoint(endpoint * GLIDE_KEY_WIDTH, 0f, time))
            service.onGesture(points, keys, GLIDE_KEY_WIDTH, GlideVerdict.Pinyin(preview = true, strokeId = 100))
        }

        preview(1, 200)
        assertEquals("word ni", editor.text.toString())
        service.onUpdateSelection(5, 5, 7, 7, 5, 7)
        preview(4, 250)
        assertEquals("word ni", editor.text.toString())
        assertEquals("ni", service.uiState.value.pinyinGlidePreview?.reading)
        assertTrue(service.uiState.value.pinyinGlidePreview!!.words.contains("\u4f60"))
        assertTrue(service.uiState.value.pinyinGlidePreview!!.choices.isEmpty())
        preview(2, 300)
        assertEquals("word na", editor.text.toString())
        preview(3, 350)
        assertEquals("word nan", editor.text.toString())
        service.onUpdateSelection(7, 7, 7, 7, 5, 7)
        preview(2, 400)
        assertEquals("word na", editor.text.toString())
        val picked = service.uiState.value.pinyinGlidePreview!!.choices.first()
        service.onGesture(emptyList(), keys, GLIDE_KEY_WIDTH, GlideVerdict.Pinyin(picked = picked, strokeId = 100))
        assertEquals(listOf("ni", "a", "n", ""), writes)
        assertEquals("word na", editor.text.toString())
        assertTrue(editor.commits.isEmpty())
    }

    @Test fun `typing and ranking the same pinyin keep the already displayed prefix`() {
        PinyinSyllables.valid = inventory + setOf("na", "bi")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "ni\t\u4f60\t1000", "hao\t\u597d\t100", "nihao\t\u4f60\u597d\t2000",
            "na\t\u90a3\t500", "bi\t\u6bd4\t100",
        ))
        val writes = mutableListOf<String>()
        val editor = object : RecordingEditor(initial = "word ") {
            override fun beginBatchEdit(): Boolean = true
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                writes += text.toString()
                return super.setComposingText(text, newCursorPosition)
            }
        }
        val (service, _) = keyboard(BuiltInLayouts.COMPACT_PINYIN, DoublePinyinScheme.XIAOHE, editor = editor)
        fun type(letter: String) = service.onKey(com.wasimaster.wmkeyboard.core.layout.Key(label = letter))
        type("n")
        settle { "\u90a3" in service.uiState.value.suggestions }
        service.onUpdateSelection(5, 5, 6, 6, 5, 6)
        type("i")
        settle { "\u4f60" in service.uiState.value.suggestions && "\u90a3" !in service.uiState.value.suggestions }
        assertEquals("word ni", editor.text.toString())
        type("h")
        service.onUpdateSelection(6, 6, 7, 7, 5, 7)
        type("c")
        settle { "\u4f60\u597d" in service.uiState.value.suggestions }
        assertEquals("word nihao", editor.text.toString())
        assertEquals(listOf("n", "i", "h", "ao"), writes)
    }

    @Test fun `grouped taps only display the resolved spelling in full and raw modes`() {
        val savedRaw = CjkConfig.doublePinyinRawLetters
        try {
            PinyinSyllables.valid = inventory + setOf("bi")
            CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
                "ni\t\u4f60\t1000", "hao\t\u597d\t100", "nihao\t\u4f60\u597d\t2000", "bi\t\u6bd4\t100",
            ))
            for (raw in listOf(false, true)) {
                CjkConfig.doublePinyinRawLetters = raw
                val writes = mutableListOf<String>()
                val field = object : RecordingEditor() {
                    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                        writes += text.toString()
                        return super.setComposingText(text, newCursorPosition)
                    }
                }
                val (service, _) = keyboard(BuiltInLayouts.COMPACT_PINYIN, DoublePinyinScheme.XIAOHE, editor = field)
                val groups = service.uiState.value.layouts.letters.rows.flatten()
                fun press(group: String) = service.onKey(groups.first { it.letters == group })
                press("bn")
                press("ui")
                settle { service.uiState.value.composingPreview == "ni" }
                assertEquals(listOf("ni"), writes)
                press("gh")
                press("cv")
                val expected = if (raw) "nihc" else "nihao"
                settle { service.uiState.value.composingPreview == expected }
                assertEquals(listOf("ni", expected), writes)
                assertEquals(expected, field.text.toString())
            }
        } finally {
            CjkConfig.doublePinyinRawLetters = savedRaw
        }
    }

    @Test fun `a lone initial or vowel suggests Chinese before and after a complete syllable`() {
        val savedJianpin = CjkConfig.jianpin
        val savedIndex = Jianpin.index
        PinyinSyllables.valid = inventory + setOf("lai", "liu", "a", "an", "o", "ou", "e", "er")
        Jianpin.index = Jianpin.build(PinyinSyllables.valid)
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "ni\t\u4f60\t100", "lai\t\u6765\t1000",
            "nilai\t\u4f60\u6765\t2000", "liu\t\u516d\t100",
            "a\t\u554a\t100", "an\t\u5b89\t1000", "nian\t\u4f60\u5b89\t2000",
            "o\t\u54e6\t100", "ou\t\u6b27\t1000", "niou\t\u4f60\u6b27\t2000",
            "e\t\u989d\t100", "er\t\u513f\t1000", "nier\t\u4f60\u513f\t2000",
        ))
        val qwerty = BuiltInLayouts.QWERTY.copy(
            langId = "zh", composer = com.wasimaster.wmkeyboard.core.script.ComposerType.PINYIN,
        )
        try {
            for (jianpin in listOf(false, true)) {
                CjkConfig.jianpin = jianpin
                for (scheme in listOf(DoublePinyinScheme.OFF, DoublePinyinScheme.XIAOHE)) {
                    for (spec in listOf(qwerty, BuiltInLayouts.PINYIN_13, BuiltInLayouts.COMPACT_PINYIN)) {
                        val (service, editor) = keyboard(spec, scheme)
                        fun type(text: String) {
                            val keys = spec.compile(LayoutLayer.LETTERS).rows.flatten()
                            for (letter in text) service.onKey(keys.first { letter in it.letterSet() })
                        }
                        var committed = ""
                        fun check(keys: String, expected: String) {
                            type(keys)
                            settle { expected in service.uiState.value.suggestions }
                            val words = service.uiState.value.suggestions
                            val index = words.indexOf(expected)
                            assertTrue("${spec.id} / $scheme / $jianpin / $keys: $words", index >= 0)
                            service.onCandidateTapped(expected, index)
                            committed += expected
                            assertEquals(committed, editor.text.toString())
                        }
                        for ((key, word) in mapOf("l" to "\u6765", "a" to "\u5b89", "o" to "\u6b27", "e" to "\u513f")) {
                            check(key, word)
                            check("ni$key", "\u4f60$word")
                        }
                    }
                }
            }
        } finally {
            CjkConfig.jianpin = savedJianpin
            Jianpin.index = savedIndex
        }
    }

    @Test fun `backspace inside displayed double pinyin removes the syllable and keeps the insertion point`() {
        val spec = BuiltInLayouts.QWERTY.copy(
            langId = "zh", composer = com.wasimaster.wmkeyboard.core.script.ComposerType.PINYIN,
        )
        val (service, editor) = keyboard(spec, DoublePinyinScheme.XIAOHE)
        CjkConfig.doublePinyinSyllableDelete = true
        fun type(keys: String) = keys.forEach { service.onKey(com.wasimaster.wmkeyboard.core.layout.Key(label = it.toString())) }
        type("nihcni")
        assertEquals("nihaoni", editor.text.toString())
        service.onUpdateSelection(7, 7, 4, 4, 0, 7)
        service.onKey(com.wasimaster.wmkeyboard.core.layout.Key(label = "", action = KeyAction.Delete))
        assertEquals("nini", editor.text.toString())
        type("hc")
        assertEquals("nihaoni", editor.text.toString())
    }

    @Test fun `full pinyin previews the stroke before lift and clears it on cancellation`() {
        val spec = BuiltInLayouts.QWERTY.copy(
            langId = "zh", composer = com.wasimaster.wmkeyboard.core.script.ComposerType.PINYIN,
        )
        val (service, editor) = keyboard(spec, DoublePinyinScheme.OFF)
        val keys = listOf(
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('n', 0f, 0f),
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('i', GLIDE_KEY_WIDTH, 0f),
        )
        val points = listOf(GesturePoint(0f, 0f, 100), GesturePoint(GLIDE_KEY_WIDTH, 0f, 200))
        service.onGesturePreview(points.take(1), keys, GLIDE_KEY_WIDTH)
        assertEquals("", editor.text.toString())
        service.onGesturePreview(points, keys, GLIDE_KEY_WIDTH)
        assertEquals("ni", service.uiState.value.pinyinGlidePreview?.reading)
        assertEquals("ni", editor.text.toString())
        service.onGesture(points, keys, GLIDE_KEY_WIDTH, com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Cancel)
        assertEquals("", editor.text.toString())
        assertTrue(editor.commits.isEmpty())
    }

    @Test fun `double pinyin shows the ranked compact reading and keeps it on lift`() {
        val writes = mutableListOf<String>()
        val editor = object : RecordingEditor() {
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                writes += text.toString()
                return super.setComposingText(text, newCursorPosition)
            }
        }
        val (service, _) = keyboard(BuiltInLayouts.COMPACT_PINYIN, DoublePinyinScheme.XIAOHE, editor = editor)
        val keys = listOf(
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('b', 0f, 0f),
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('n', 0f, 0f),
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('u', GLIDE_KEY_WIDTH, 0f),
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('i', GLIDE_KEY_WIDTH, 0f),
            com.wasimaster.wmkeyboard.core.gesture.KeyCenter('h', 2 * GLIDE_KEY_WIDTH, 0f),
        )
        val points = listOf(GesturePoint(0f, 0f, 100), GesturePoint(GLIDE_KEY_WIDTH, 0f, 200))
        val lastVibration = WMKeyboardService::class.java.getDeclaredField("lastVibrateAt").apply { isAccessible = true }
        val beforePreview = lastVibration.getLong(service)
        service.onGesture(points, keys, GLIDE_KEY_WIDTH,
            com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Pinyin(preview = true, strokeId = 100))
        assertEquals(beforePreview, lastVibration.getLong(service))
        assertEquals("ni", service.uiState.value.pinyinGlidePreview?.reading)
        assertTrue(service.uiState.value.pinyinGlidePreview?.words.orEmpty().contains("\u4f60"))
        assertEquals("ni", editor.text.toString())
        service.onGesture(points + GesturePoint(2 * GLIDE_KEY_WIDTH, 0f, 210), keys, GLIDE_KEY_WIDTH,
            com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Pinyin(strokeId = 100))
        assertEquals("ni", editor.text.toString())
        assertTrue(service.uiState.value.pinyinGlidePreview!!.settling)
        assertTrue("\u4f60" in service.uiState.value.pinyinGlidePreview!!.words)
        settle { service.uiState.value.pinyinGlidePreview == null && "\u4f60" in service.uiState.value.suggestions }
        assertEquals(null, service.uiState.value.pinyinGlidePreview)
        assertEquals(listOf("ni"), writes)
    }

    @Test fun `candidate page advances one syllable at a time even when its spelling is unchanged`() {
        PinyinSyllables.valid = inventory + setOf("mi", "cang")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "ni\t你\t100", "mi\t米\t100", "hao\t好\t100", "nihao\t你好\t1000", "mihao\t米好\t1000",
            "micang\t米仓\t1000",
        ))
        val spec = BuiltInLayouts.QWERTY.copy(
            langId = "zh", composer = com.wasimaster.wmkeyboard.core.script.ComposerType.PINYIN,
        )
        var regionWrites = 0
        val editor = object : RecordingEditor() {
            override fun beginBatchEdit(): Boolean = true
            override fun endBatchEdit(): Boolean = true

            override fun setComposingRegion(start: Int, end: Int): Boolean {
                regionWrites++
                return super.setComposingRegion(start, end)
            }
        }
        val (service, _) = keyboard(spec, DoublePinyinScheme.XIAOHE, editor = editor)
        "nihc".forEach { service.onKey(com.wasimaster.wmkeyboard.core.layout.Key(label = it.toString())) }
        settle { service.uiState.value.suggestions.firstOrNull() == "你好" }
        service.onUpdateSelection(0, 0, 5, 5, 0, 5)
        service.onCandidatesExpand()
        fun pick(expectedSlot: Int, reading: String) {
            settle { service.uiState.value.pinyinReadingCorrections.values.any { it.slot == expectedSlot } }
            val choices = service.uiState.value.pinyinReadingCorrections
            assertEquals(setOf(expectedSlot), choices.values.map { it.slot }.toSet())
            val action = choices.entries.single { it.value.reading == reading }.key
            service.onKey(com.wasimaster.wmkeyboard.core.layout.Key(reading, output = action, action = KeyAction.ReadingColumn))
        }
        val writesBeforePicking = regionWrites
        pick(0, "mi")
        service.onCandidateTapped("hao", 0)
        assertTrue(editor.commits.isEmpty())
        pick(1, "hao")
        settle { service.uiState.value.pinyinCorrectionBuffer.isNotEmpty() && service.uiState.value.pinyinReadingCorrections.isEmpty() }
        assertTrue(service.uiState.value.pinyinReadingCorrections.isEmpty())
        assertEquals("mihao", editor.text.toString())
        assertEquals("米好", service.uiState.value.expandedCandidates.first())

        service.onCandidatesExpand()
        service.onCandidatesExpand()
        settle { service.uiState.value.pinyinCorrectionBuffer.isNotEmpty() }
        fun undo(expectedSlot: Int) {
            assertTrue(UndoReadingSelection in service.uiState.value.conversionReadings)
            service.onKey(com.wasimaster.wmkeyboard.core.layout.Key("", output = UndoReadingSelection, action = KeyAction.ReadingColumn))
            settle { service.uiState.value.pinyinReadingCorrections.values.any { it.slot == expectedSlot } }
            assertEquals(setOf(expectedSlot), service.uiState.value.pinyinReadingCorrections.values.map { it.slot }.toSet())
        }
        undo(1)
        assertEquals("mihao", editor.text.toString())
        service.onUpdateSelection(5, 5, 5, 5, 0, 5)
        pick(1, "cang")
        settle { service.uiState.value.expandedCandidates.firstOrNull() == "米仓" }
        assertEquals("micang", editor.text.toString())
        undo(1)
        assertEquals("mihao", editor.text.toString())
        undo(0)
        assertEquals("nihao", editor.text.toString())
        assertTrue(UndoReadingSelection !in service.uiState.value.conversionReadings)
        assertEquals(writesBeforePicking, regionWrites)
        assertTrue(editor.commits.isEmpty())
    }

    @Test fun `double Pinyin offers a frequent two-syllable repair early and commits it once`() {
        PinyinSyllables.valid = setOf("li", "ni", "cang", "hao")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "li\t李\t100", "cang\t仓\t100", "licang\t李沧\t300", "nihao\t你好\t9000",
        ))
        val spec = BuiltInLayouts.QWERTY.copy(langId = "zh", composer = com.wasimaster.wmkeyboard.core.script.ComposerType.PINYIN)
        val (service, editor) = keyboard(spec, DoublePinyinScheme.XIAOHE)
        "lich".forEach { service.onKey(com.wasimaster.wmkeyboard.core.layout.Key(label = it.toString())) }
        settle { "你好" in service.uiState.value.suggestions }
        val candidates = service.uiState.value.suggestions
        val index = candidates.indexOf("你好")
        assertTrue(candidates.toString(), index in 1..2)
        assertEquals("李沧", candidates.first())
        assertTrue(editor.commits.isEmpty())
        service.onCandidateTapped("你好", index)
        assertEquals(listOf("你好"), editor.commits)
        assertEquals("你好", editor.text.toString())
    }

    @Test fun `compact lift uses the last turn and correction can recover gai from df`() {
        PinyinSyllables.valid = setOf("da", "dai", "gai", "hai", "ha")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf(
            "da\t大\t850", "dai\t带\t900", "gai\t概\t800", "dagai\t大概\t640",
        ))
        for (spec in listOf(BuiltInLayouts.PINYIN_13, BuiltInLayouts.COMPACT_PINYIN)) {
            val (service, editor) = keyboard(spec, DoublePinyinScheme.XIAOHE)
            val keys = listOf("as", "df", "gh").flatMapIndexed { index, group ->
                group.map { com.wasimaster.wmkeyboard.core.gesture.KeyCenter(it, index * 2 * GLIDE_KEY_WIDTH, 0f) }
            }
            val points = listOf(2f, 0f, 4f, 2f).mapIndexed { index, x ->
                GesturePoint(x * GLIDE_KEY_WIDTH, 0f, 100L + index * 30L)
            }
            service.onGesture(points.take(2), keys, GLIDE_KEY_WIDTH,
                com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Pinyin(preview = true, strokeId = 100))
            assertEquals("da", editor.text.toString())
            service.onGesture(points, keys, GLIDE_KEY_WIDTH,
                com.wasimaster.wmkeyboard.ime.ui.GlideVerdict.Pinyin(strokeId = 100))
            assertEquals("dagai", editor.text.toString())
            settle { "大概" in service.uiState.value.suggestions }
            assertTrue("大概" in service.uiState.value.suggestions)

            val (correction, _) = keyboard(spec, DoublePinyinScheme.XIAOHE)
            val df = correction.uiState.value.layouts.letters.rows.flatten().first { it.letters == "df" }
            repeat(2) { correction.onKey(df) }
            correction.onCandidatesExpand()
            settle { correction.uiState.value.pinyinReadingCorrections.isNotEmpty() }
            val choices = correction.uiState.value.pinyinReadingCorrections.values.map { it.reading }
            assertTrue("${spec.id}: $choices", "gai" in choices)
        }
    }

}
