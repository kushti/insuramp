package p2pgate.ergo

/**
 * Minimal hand-rolled JSON reader for [ExplorerChainSource] — just enough of
 * RFC-8259 for the explorer API's flat response shapes (no schema classes, no
 * third-party parser on the app's dependency graph). Internal: not part of the
 * module's public surface.
 */
internal sealed interface Json {

    fun obj(field: String): Obj? = (this as? Obj)?.get(field) as? Obj
    fun arr(field: String): Arr? = (this as? Obj)?.get(field) as? Arr
    fun str(field: String): String? = (this as? Obj)?.get(field)?.let { (it as? Str)?.value }
    fun long(field: String): Long? = (this as? Obj)?.get(field)?.let { (it as? Num)?.value?.toLong() }
    fun int(field: String): Int? = long(field)?.toInt()
    fun bool(field: String): Boolean? = (this as? Obj)?.get(field)?.let { (it as? Bool)?.value }

    class Obj(val entries: Map<String, Json>) : Json {
        fun get(field: String): Json? = entries[field]
    }

    class Arr(val items: List<Json>) : Json
    class Str(val value: String) : Json
    class Num(val value: Double) : Json
    class Bool(val value: Boolean) : Json
    data object Null : Json

    companion object {
        fun parse(text: String): Json {
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

            fun value(): Json {
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

            private fun lit(word: String, v: Json): Json {
                if (!s.startsWith(word, i)) fail("expected '$word'")
                i += word.length
                return v
            }

            private fun obj(): Json {
                i++ // '{'
                val entries = linkedMapOf<String, Json>()
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
                    when {
                        i < s.length && s[i] == ',' -> i++
                        i < s.length && s[i] == '}' -> { i++; return Obj(entries) }
                        else -> fail("expected ',' or '}'")
                    }
                }
            }

            private fun arr(): Json {
                i++ // '['
                val items = mutableListOf<Json>()
                skipWs()
                if (i < s.length && s[i] == ']') { i++; return Arr(items) }
                while (true) {
                    items.add(value())
                    skipWs()
                    when {
                        i < s.length && s[i] == ',' -> i++
                        i < s.length && s[i] == ']' -> { i++; return Arr(items) }
                        else -> fail("expected ',' or ']'")
                    }
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
                                'f' -> sb.append('')
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

            private fun num(): Json {
                val start = i
                if (i < s.length && s[i] == '-') i++
                while (i < s.length && s[i] in "0123456789.eE+-") i++
                val raw = s.substring(start, i)
                return Num(raw.toDoubleOrNull() ?: fail("bad number '$raw'"))
            }
        }
    }
}
