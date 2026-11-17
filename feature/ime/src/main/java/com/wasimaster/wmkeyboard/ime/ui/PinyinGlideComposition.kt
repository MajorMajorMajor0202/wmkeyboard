package com.wasimaster.wmkeyboard.ime.ui

/** Only a continuing stroke may extend the text it already confirmed. */
internal class PinyinGlideComposition {
    private var stroke: Long? = null
    private var context: Any? = null
    private var expected = ""
    private var prefix = ""
    private var interrupted = false
    private var retiredStroke: Long? = null

    fun update(id: Long, owner: Any, buffer: String, confirmed: String): String? {
        // Production ids are monotonic touch-down times. Zero is retained
        // for callers which do not provide an id (including older tests).
        if (id != 0L && (retiredStroke?.let { id <= it } == true || stroke?.let { id < it } == true)) return null
        if (stroke != id) {
            stroke = id
            context = owner
            expected = buffer
            prefix = ""
            interrupted = false
        }
        if (interrupted || context != owner || expected != buffer || !confirmed.startsWith(prefix)) {
            interrupted = true
            return null
        }
        val addition = confirmed.substring(prefix.length)
        prefix = confirmed
        expected = buffer + addition
        return addition
    }

    /** The service can insert at a reading caret rather than at the buffer's end. */
    fun recordBuffer(buffer: String) { expected = buffer }

    fun reset() {
        stroke?.takeIf { it != 0L }?.let { retiredStroke = maxOf(retiredStroke ?: it, it) }
        stroke = null
        context = null
        expected = ""
        prefix = ""
        interrupted = false
    }
}
