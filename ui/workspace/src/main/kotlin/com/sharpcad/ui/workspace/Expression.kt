package com.sharpcad.ui.workspace

/**
 * Evaluates a typed size such as `20+5*2`, `(40-4)/3`, `2 cm` or `1in`.
 * Supports + - * / parentheses, unary minus, a comma decimal separator and the unit
 * suffixes mm, cm, m and in (result in millimetres). Returns `null` when it does not parse.
 */
object Expression {
    fun evaluate(text: String): Double? = runCatching {
        Parser(text.replace(',', '.').lowercase().filterNot { it.isWhitespace() }).run()
    }.getOrNull()?.takeIf { it.isFinite() }

    private class Parser(val s: String) {
        var i = 0

        fun run(): Double {
            val v = sum()
            require(i == s.length)
            return v
        }

        fun sum(): Double {
            var v = product()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = product()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun product(): Double {
            var v = unary()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == 'x')) {
                val op = s[i++]
                val r = unary()
                v = if (op == '/') v / r else v * r
            }
            return v
        }

        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            val v = if (i < s.length && s[i] == '(') {
                i++
                val inner = sum()
                require(i < s.length && s[i] == ')')
                i++
                inner
            } else {
                val start = i
                while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                s.substring(start, i).toDouble()
            }
            return v * unit()
        }

        fun unit(): Double {
            for ((suffix, scale) in UNITS) {
                if (s.startsWith(suffix, i)) { i += suffix.length; return scale }
            }
            return 1.0
        }
    }

    private val UNITS = listOf("mm" to 1.0, "cm" to 10.0, "in" to 25.4, "m" to 1000.0)
}
