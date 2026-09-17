package p2pgate.e2e

/**
 * Minimal hand-rolled JSON reader for the e2e harness — the same shape as
 * `:apps:core:ergo`'s internal `Json` (which cannot be reused across the
 * module boundary): just enough of RFC-8259 for the explorer API's response
 * shapes, no schema classes.
 */
sealed interface J {
    fun obj(field: String): Obj? = (this as? Obj)?.get(field) as? Obj
    fun arr(field: String): Arr? = (this as? Obj)?.get(field) as? Arr
    fun str(field: String): String? = (this as? Obj)?.get(field)?.let { (it as? Str)?.value }
    fun long(field: String): Long? = (this as? Obj)?.get(field)?.let { (it as? Num)?.value?.toLong() }
    fun int(field: String): Int? = long(field)?.toInt()

    class Obj(val entries: Map<String, J>) : J {
        fun get(field: String): J? = entries[field]
    }

    class Arr(val items: List<J>) : J
    class Str(val value: String) : J
    class Num(val value: Double) : J
    class Bool(val value: Boolean) : J
    data object Null : J

    companion object {
        fun parse(text: String): J {
            val p = Parser(text)
            val v = p.value()
            p.skipWs()
            if (!p.atEnd()) fail("trailing content")
            return v
        }

        private fun fail(msg: String): Nothing = throw JsonParseException("invalid JSON: $msg")

        class JsonParseException(message: String) : IllegalArgumentException(message)

        private class Parser(private val s: String) {
            private var i = 0

            fun atEnd(): Boolean = i >= s.length

            fun skipWs() {
                while (i < s.length && s[i] in " \t\r\n") i++
            }

            fun value(): J {
                skipWs()
                if (atEnd()) fail("unexpected end of input")
                return when (s[i]) {
                    '{' -> obj()
                    '[' -> arr()
                    '"' -> Str(string())
                    't' -> lit("true", Bool(true))
                    'f' -> lit("false", Bool(false))
                    'n' -> lit("null", Null)
                    '-', in '0'..'9' -> num()
                    else -> fail("unexpected character '${s[i]}'")
                }
            }

            private fun lit(word: String, v: J): J {
                if (!s.startsWith(word, i)) fail("expected '$word'")
                i += word.length
                return v
            }

            private fun obj(): J {
                i++ // '{'
                val entries = linkedMapOf<String, J>()
                skipWs()
                if (i < s.length && s[i] == '}') { i++; return Obj(entries) }
                while (true) {
                    skipWs()
                    val key = string()
                    skipWs()
                    if (i >= s.length || s[i] != ':') fail("expected ':'")
                    i++
                    entries[key] = value()
                    skipWs()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == '}') { i++; return Obj(entries) }
                    fail("expected ',' or '}'")
                }
            }

            private fun arr(): J {
                i++ // '['
                val items = mutableListOf<J>()
                skipWs()
                if (i < s.length && s[i] == ']') { i++; return Arr(items) }
                while (true) {
                    items += value()
                    skipWs()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == ']') { i++; return Arr(items) }
                    fail("expected ',' or ']'")
                }
            }

            private fun string(): String {
                if (i >= s.length || s[i] != '"') fail("expected string")
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    when (val c = s[i++]) {
                        '"' -> return sb.toString()
                        '\\' -> {
                            if (i >= s.length) fail("bad escape")
                            when (val e = s[i++]) {
                                '"' -> sb.append('"')
                                '\\' -> sb.append('\\')
                                '/' -> sb.append('/')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'u' -> {
                                    if (i + 4 > s.length) fail("bad \\u escape")
                                    sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                    i += 4
                                }
                                else -> fail("bad escape '\\$e'")
                            }
                        }
                        else -> sb.append(c)
                    }
                }
                fail("unterminated string")
            }

            private fun num(): J {
                val start = i
                if (i < s.length && s[i] == '-') i++
                while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-" && (s[i] != '+' && s[i] != '-' || i > start && (s[i - 1] == 'e' || s[i - 1] == 'E')))) i++
                val text = s.substring(start, i)
                if (text.isEmpty() || text == "-") fail("bad number")
                return Num(text.toDouble())
            }
        }
    }
}

/** Escapes a string for embedding in a JSON document. */
fun jsonEscape(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}
