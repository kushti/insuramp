package p2pgate.e2e

/** Renders the summary map as compact JSON (strings, numbers, nested maps/lists). */
fun jsonOf(value: Any?): String = when (value) {
    null -> "null"
    is String -> jsonEscape(value)
    is Int, is Long, is Double -> value.toString()
    is Boolean -> value.toString()
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> "${jsonEscape(k.toString())}:${jsonOf(v)}" }
    is List<*> -> value.joinToString(",", "[", "]") { jsonOf(it) }
    else -> jsonEscape(value.toString())
}
