package com.sharpcad.ui.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Interface languages. English strings in code are the keys. */
enum class Language(val code: String, val label: String) {
    EN("en", "English"),
    UZ("uz", "O'zbekcha"),
    RU("ru", "Русский"),
}

/**
 * Interface translation. Code keeps English labels (they double as ids for choices and
 * actions); only what is drawn goes through [tr]. Unknown text is shown as is.
 */
object Tr {
    /** Compose state: changing it redraws every [tr] call site. */
    var language by mutableStateOf(Language.EN)

    fun tr(text: String): String {
        val lang = language
        if (lang == Language.EN || text.isEmpty()) return text
        exact(text, lang)?.let { return it }
        if (" · " in text) return text.split(" · ").joinToString(" · ") { part(it, lang) }
        return part(text, lang)
    }

    private fun exact(text: String, lang: Language): String? {
        val pair = Strings.table[text] ?: return null
        return if (lang == Language.UZ) pair.first else pair.second
    }

    /** One fragment: whole match, "word 12 mm", "12 words", or a known prefix. */
    private fun part(text: String, lang: Language): String {
        exact(text, lang)?.let { return it }
        WORD_NUMBER.matchEntire(text)?.let { m ->
            exact(m.groupValues[1], lang)?.let { return "$it ${m.groupValues[2]}" }
        }
        NUMBER_WORD.matchEntire(text)?.let { m ->
            exact(m.groupValues[2], lang)?.let { return "${m.groupValues[1]} $it" }
        }
        for (prefix in PREFIXES) {
            if (text.startsWith(prefix)) exact(prefix.trimEnd(), lang)?.let { return it + " " + text.removePrefix(prefix) }
        }
        return text
    }

    private val WORD_NUMBER = Regex("""^([^\d]+?) ([-−]?[\d].*)$""")
    private val NUMBER_WORD = Regex("""^([\d][\d,.]*) (.+)$""")
    private val PREFIXES = listOf("Open: ", "Export ", "Applies to ", "Drawing ", "Units: ")
}

/** Shorthand for [Tr.tr]. */
fun tr(text: String): String = Tr.tr(text)
