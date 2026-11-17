package com.wasimaster.wmkeyboard.core.input.composer

/** A derived reading index for Han words explicitly present in the personal dictionary. */
object PinyinPersonalWords {
    private var indexed: ConversionDictionary? = null
    private var characters: Map<Int,String> = emptyMap()

    /** Called off the UI thread; an unreadable word is omitted rather than assigned a partial reading. */
    @Synchronized
    fun entries(dictionary: ConversionDictionary, words: List<String>): Map<String,Map<String,Int>> {
        val han=words.filter { it.isNotEmpty() && it.codePointCount(0,it.length) in 2..32 && CjkContext.hanTail(it)==it }
        if(dictionary.isEmpty || han.isEmpty()) return emptyMap()
        if(indexed!==dictionary) {
            val readings=HashMap<Int,String>()
            val weights=HashMap<Int,Int>()
            for(row in 0 until dictionary.size) {
                val word=dictionary.word(row)
                if(word.isEmpty() || word.codePointCount(0,word.length)!=1) continue
                val code=word.codePointAt(0)
                val weight=dictionary.frequency(row)
                if(weight>(weights[code] ?: Int.MIN_VALUE)) {
                    val reading=dictionary.reading(row)
                    if(reading.isNotEmpty() && reading.all { it in 'a'..'z' }) {
                        readings[code]=reading
                        weights[code]=weight
                    }
                }
            }
            indexed=dictionary
            characters=readings
        }
        val entries=HashMap<String,MutableMap<String,Int>>()
        for(word in han) {
            val codes=word.codePoints().toArray()
            val parts=codes.map { characters[it] }
            if(parts.any { it==null }) continue
            entries.getOrPut(parts.joinToString("") { it!! }) { HashMap() }[word]=8
        }
        return entries
    }
}
