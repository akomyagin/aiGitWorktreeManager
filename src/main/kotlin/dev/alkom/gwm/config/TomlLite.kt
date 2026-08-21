package dev.alkom.gwm.config

/** Parse error carrying the 1-based line number where the offending token was seen. */
class TomlParseException(val line: Int, message: String) : Exception("строка $line: $message")

/**
 * Flat representation of the parsed TOML subset.
 *
 * Keys are stored FLAT and section-qualified: a `clean = "green"` under a `[colors]`
 * section is stored as `"colors.clean"`. Values are kept verbatim as strings (numbers /
 * booleans are not coerced here — the consumer interprets them); arrays are string lists.
 */
data class ParsedToml(
    val strings: Map<String, String>,
    val stringLists: Map<String, List<String>>,
) {
    fun string(key: String): String? = strings[key]
    fun stringList(key: String): List<String>? = stringLists[key]

    companion object {
        val EMPTY = ParsedToml(emptyMap(), emptyMap())
    }
}

/**
 * Hand-written parser for the MINIMAL TOML subset gwm needs — deliberately NOT a
 * general TOML implementation (see plan §Р2: keeping the tool dependency-free is the
 * whole point; the needed grammar is ~10 lines of config).
 *
 * Supported:
 *  - flat `key = value` where value is a double- or single-quoted string, or a bare
 *    token (number/bool) stored as-is;
 *  - single-line string arrays: `roots = ["/a", "/b"]` (and empty `[]`);
 *  - ONE section header `[name]` used as a prefix for the keys that follow
 *    (`[colors]` + `clean = "x"` → key `colors.clean`);
 *  - `#` comments and blank lines are skipped.
 *
 * Explicitly NOT supported — encountering any of these throws [TomlParseException]
 * with the line number rather than silently ignoring or crashing:
 *  - nested tables (`[a.b]`), arrays of tables (`[[x]]`);
 *  - dates/times, floats;
 *  - multiline strings (`"""..."""`), escape sequences inside strings;
 *  - inline tables (`{ a = 1 }`);
 *  - a duplicate key.
 */
object TomlLite {

    fun parse(text: String): ParsedToml {
        val strings = LinkedHashMap<String, String>()
        val stringLists = LinkedHashMap<String, List<String>>()
        var section: String? = null

        text.split("\n").forEachIndexed { idx, rawLine ->
            val lineNo = idx + 1
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) return@forEachIndexed

            // Section header: [name]. Reject nested / array-of-table forms explicitly.
            if (line.startsWith("[")) {
                if (line.startsWith("[[")) {
                    throw TomlParseException(lineNo, "массивы таблиц ([[...]]) не поддерживаются")
                }
                if (!line.endsWith("]")) {
                    throw TomlParseException(lineNo, "незакрытый заголовок секции: $line")
                }
                val name = line.substring(1, line.length - 1).trim()
                if (name.isEmpty()) throw TomlParseException(lineNo, "пустое имя секции")
                if ('.' in name) throw TomlParseException(lineNo, "вложенные таблицы ([a.b]) не поддерживаются")
                if ('[' in name || ']' in name) throw TomlParseException(lineNo, "некорректный заголовок секции: $line")
                section = name
                return@forEachIndexed
            }

            val eq = line.indexOf('=')
            if (eq < 0) throw TomlParseException(lineNo, "ожидалось 'ключ = значение', получено: $line")
            val key = line.substring(0, eq).trim()
            val rawValue = line.substring(eq + 1).trim()
            if (key.isEmpty()) throw TomlParseException(lineNo, "пустой ключ")
            if ('.' in key) throw TomlParseException(lineNo, "точечные ключи (a.b) не поддерживаются")

            val fullKey = if (section != null) "$section.$key" else key
            if (fullKey in strings || fullKey in stringLists) {
                throw TomlParseException(lineNo, "дубликат ключа: $fullKey")
            }

            when {
                rawValue.startsWith("[") -> {
                    stringLists[fullKey] = parseArray(rawValue, lineNo)
                }
                rawValue.startsWith("{") -> {
                    throw TomlParseException(lineNo, "inline-таблицы ({ ... }) не поддерживаются")
                }
                rawValue.startsWith("\"\"\"") || rawValue.startsWith("'''") -> {
                    throw TomlParseException(lineNo, "multiline-строки не поддерживаются")
                }
                else -> {
                    strings[fullKey] = unquote(rawValue, lineNo)
                }
            }
        }
        return ParsedToml(strings, stringLists)
    }

    /** Drops a trailing `# ...` comment, but not a `#` inside a quoted string. */
    private fun stripComment(line: String): String {
        var inSingle = false
        var inDouble = false
        for (i in line.indices) {
            when (line[i]) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble) return line.substring(0, i)
            }
        }
        return line
    }

    /** Parses a single-line string array `["a", "b"]`. */
    private fun parseArray(raw: String, lineNo: Int): List<String> {
        if (!raw.endsWith("]")) {
            throw TomlParseException(lineNo, "незакрытый или многострочный массив не поддерживается: $raw")
        }
        val inner = raw.substring(1, raw.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        return splitTopLevel(inner, lineNo).map { unquote(it.trim(), lineNo) }
    }

    /** Splits array elements on top-level commas, respecting quotes; rejects nesting. */
    private fun splitTopLevel(inner: String, lineNo: Int): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        for (c in inner) {
            when {
                c == '\'' && !inDouble -> { inSingle = !inSingle; cur.append(c) }
                c == '"' && !inSingle -> { inDouble = !inDouble; cur.append(c) }
                (c == '[' || c == '{') && !inSingle && !inDouble ->
                    throw TomlParseException(lineNo, "вложенные массивы/таблицы не поддерживаются")
                c == ',' && !inSingle && !inDouble -> { parts.add(cur.toString()); cur.setLength(0) }
                else -> cur.append(c)
            }
        }
        val tail = cur.toString().trim()
        // Allow a trailing comma (`["a",]`) — the tail is empty and dropped.
        if (tail.isNotEmpty()) parts.add(tail)
        return parts
    }

    /** Strips one layer of matching single/double quotes; a bare token is returned as-is. */
    private fun unquote(value: String, lineNo: Int): String {
        if (value.isEmpty()) throw TomlParseException(lineNo, "пустое значение")
        if (value.length >= 6 && (value.startsWith("\"\"\"") || value.startsWith("'''"))) {
            throw TomlParseException(lineNo, "multiline-строки не поддерживаются")
        }
        val isDouble = value.startsWith("\"") && value.endsWith("\"") && value.length >= 2
        val isSingle = value.startsWith("'") && value.endsWith("'") && value.length >= 2
        return when {
            isDouble || isSingle -> value.substring(1, value.length - 1)
            value.startsWith("\"") || value.startsWith("'") ->
                throw TomlParseException(lineNo, "незакрытая строка: $value")
            else -> value // bare token (number/bool) kept verbatim
        }
    }
}
