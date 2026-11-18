package com.sharpcad.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sharpcad.ui.designsystem.Language
import com.sharpcad.ui.designsystem.SharpCadColors
import com.sharpcad.ui.designsystem.Tr
import com.sharpcad.ui.designsystem.LengthUnit
import com.sharpcad.ui.designsystem.Units
import com.sharpcad.ui.workspace.ChoiceField
import com.sharpcad.ui.workspace.PanelAction
import com.sharpcad.viewport.ViewportController

/** User interface preferences, kept in SharedPreferences and applied immediately. */
class AppSettings(context: Context, private val viewport: ViewportController) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var dark by mutableStateOf(prefs.getBoolean(KEY_DARK, true)); private set
    var accent by mutableStateOf(prefs.getInt(KEY_ACCENT, SharpCadColors.accents.first().second)); private set
    var toolsOnRight by mutableStateOf(prefs.getBoolean(KEY_RIGHT, false)); private set
    var showLabels by mutableStateOf(prefs.getBoolean(KEY_LABELS, false)); private set
    var open by mutableStateOf(false)

    init {
        // First run follows the device language when it is one we have.
        val device = java.util.Locale.getDefault().language
        val saved = prefs.getString(KEY_LANGUAGE, null) ?: Language.entries.firstOrNull { it.code == device }?.code ?: Language.EN.code
        Tr.language = Language.entries.firstOrNull { it.code == saved } ?: Language.EN
        Units.current = LengthUnit.entries.firstOrNull { it.name == prefs.getString(KEY_UNITS, null) } ?: LengthUnit.MM
        apply()
    }

    fun toggle() {
        open = !open
    }

    val choices: List<ChoiceField>
        get() = listOf(
            ChoiceField("Theme", THEMES, if (dark) THEMES[0] else THEMES[1]),
            ChoiceField("Accent", SharpCadColors.accents.map { it.first }, SharpCadColors.accents.firstOrNull { it.second == accent }?.first ?: ""),
            ChoiceField("Tools", SIDES, if (toolsOnRight) SIDES[1] else SIDES[0]),
            ChoiceField("Labels", LABELS, if (showLabels) LABELS[1] else LABELS[0]),
            ChoiceField("Language", Language.entries.map { it.label }, Tr.language.label),
        )

    val actions: List<PanelAction> get() = listOf(PanelAction("Done", accent = true) { open = false })

    /** Units rail button: mm → cm → in → mm, remembered. */
    fun cycleUnits(): String {
        Units.current = LengthUnit.entries[(Units.current.ordinal + 1) % LengthUnit.entries.size]
        prefs.edit().putString(KEY_UNITS, Units.current.name).apply()
        return Units.current.label
    }

    fun choose(index: Int, option: String) {
        when (index) {
            0 -> dark = option == THEMES[0]
            1 -> SharpCadColors.accents.firstOrNull { it.first == option }?.let { accent = it.second }
            2 -> toolsOnRight = option == SIDES[1]
            3 -> showLabels = option == LABELS[1]
            4 -> Language.entries.firstOrNull { it.label == option }?.let { Tr.language = it }
        }
        prefs.edit().putBoolean(KEY_DARK, dark).putInt(KEY_ACCENT, accent)
            .putBoolean(KEY_RIGHT, toolsOnRight).putBoolean(KEY_LABELS, showLabels).putString(KEY_LANGUAGE, Tr.language.code).apply()
        apply()
    }

    private fun apply() {
        SharpCadColors.apply(dark, accent)
        viewport.setBackground(if (dark) 0x1B1F26 else 0xDDE2E9)
    }

    private companion object {
        const val KEY_DARK = "dark"
        const val KEY_ACCENT = "accent"
        const val KEY_RIGHT = "toolsOnRight"
        const val KEY_LABELS = "labels"
        const val KEY_LANGUAGE = "language"
        const val KEY_UNITS = "units"
        val THEMES = listOf("Dark", "Light")
        val SIDES = listOf("Left", "Right")
        val LABELS = listOf("Hidden", "Shown")
    }
}
