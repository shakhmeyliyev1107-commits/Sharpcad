package com.sharpcad.ui.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Length units for display and typing. The model itself is always in millimetres. */
enum class LengthUnit(val label: String, val millimetres: Double) {
    MM("mm", 1.0),
    CM("cm", 10.0),
    IN("in", 25.4),
}

object Units {
    /** Compose state: size fields and readouts follow it. */
    var current by mutableStateOf(LengthUnit.MM)

    /** [mm] in the current unit, up to three decimals, without the unit. */
    fun number(mm: Double): String {
        val v = Math.round(mm / current.millimetres * 1000.0) / 1000.0
        return if (v == Math.floor(v)) v.toLong().toString() else v.toString()
    }

    /** [mm] in the current unit with its label, e.g. "1.25 cm". */
    fun format(mm: Double): String = "${number(mm)} ${current.label}"
}
