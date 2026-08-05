package tech.zpkg.intellij.analysis

/**
 * Small, dependency-free TOML scanner for the stable Zed fields the plugin
 * needs. It deliberately does not attempt to become a general TOML parser.
 * Unknown sections and values are ignored; syntax that affects package,
 * dependency, or lock identity becomes a diagnostic in ZedProjectAnalyzer.
 */
internal object ZedTomlScanner {
    data class Assignment(
        val section: String,
        val key: String,
        val rawValue: String,
        val line: Int,
        val arrayTable: Boolean,
        val tableInstance: Int,
    )

    data class ScanResult(
        val assignments: List<Assignment>,
        val structuralErrors: List<Pair<Int, String>>,
    )

    fun scan(text: String): ScanResult {
        val assignments = mutableListOf<Assignment>()
        val errors = mutableListOf<Pair<Int, String>>()
        var section = ""
        var arrayTable = false
        var tableInstance = 0

        text.lineSequence().forEachIndexed { index, original ->
            val lineNumber = index + 1
            val line = stripComment(original).trim()
            if (line.isEmpty()) return@forEachIndexed

            if (line.startsWith("[[")) {
                if (!line.endsWith("]]")) {
                    errors += lineNumber to "Unclosed array-table header"
                    return@forEachIndexed
                }
                section = line.removePrefix("[[").removeSuffix("]]").trim()
                tableInstance += 1
                arrayTable = true
                if (section.isEmpty()) errors += lineNumber to "Empty array-table header"
                return@forEachIndexed
            }

            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    errors += lineNumber to "Unclosed table header"
                    return@forEachIndexed
                }
                section = line.removePrefix("[").removeSuffix("]").trim()
                tableInstance += 1
                arrayTable = false
                if (section.isEmpty()) errors += lineNumber to "Empty table header"
                return@forEachIndexed
            }

            val equals = findUnquoted(line, '=')
            if (equals < 1) {
                if (section == "package" || section == "dependencies") {
                    errors += lineNumber to "Expected a key/value assignment"
                }
                return@forEachIndexed
            }

            val key = unquote(line.substring(0, equals).trim())
            val value = line.substring(equals + 1).trim()
            if (key.isEmpty()) {
                errors += lineNumber to "Empty key"
                return@forEachIndexed
            }
            assignments += Assignment(section, key, value, lineNumber, arrayTable, tableInstance)
        }

        return ScanResult(assignments, errors)
    }

    fun stringValue(raw: String): String? {
        val value = raw.trim()
        if (value.length < 2) return null
        val quote = value.first()
        if ((quote != '"' && quote != '\'') || value.last() != quote) return null
        val body = value.substring(1, value.length - 1)
        return if (quote == '\'') body else unescapeBasic(body)
    }

    fun longValue(raw: String): Long? = raw.trim().replace("_", "").toLongOrNull()

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        line.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\' && quote == '"') {
                escaped = true
                return@forEachIndexed
            }
            if (char == '"' || char == '\'') {
                quote = if (quote == null) char else if (quote == char) null else quote
            } else if (char == '#' && quote == null) {
                return line.substring(0, index)
            }
        }
        return line
    }

    private fun findUnquoted(value: String, target: Char): Int {
        var quote: Char? = null
        var escaped = false
        value.forEachIndexed { index, char ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (char == '\\' && quote == '"') {
                escaped = true
                return@forEachIndexed
            }
            if (char == '"' || char == '\'') {
                quote = if (quote == null) char else if (quote == char) null else quote
            } else if (char == target && quote == null) {
                return index
            }
        }
        return -1
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        return stringValue(trimmed) ?: trimmed
    }

    private fun unescapeBasic(value: String): String = buildString(value.length) {
        var escaped = false
        value.forEach { char ->
            if (!escaped) {
                if (char == '\\') escaped = true else append(char)
            } else {
                append(
                    when (char) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        '"' -> '"'
                        '\\' -> '\\'
                        else -> char
                    },
                )
                escaped = false
            }
        }
        if (escaped) append('\\')
    }
}
