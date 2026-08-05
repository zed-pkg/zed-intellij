package tech.zpkg.intellij.service

internal object ZedOutputRedactor {
    private val assignment = Regex("""(?i)(authorization|token|password|secret|api[_-]?key)\s*[:=]\s*([^\s,;]+)""")
    private val bearer = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+""")
    private val githubToken = Regex("""gh[pousr]_[A-Za-z0-9_]{20,}""")

    fun redact(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return value
            .replace(assignment, "$1=[REDACTED]")
            .replace(bearer, "Bearer [REDACTED]")
            .replace(githubToken, "[REDACTED]")
    }
}
