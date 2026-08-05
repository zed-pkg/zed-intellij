package tech.zpkg.intellij.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.diagnostic.Logger
import tech.zpkg.intellij.model.ZedCliSnapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal class ZedCliService {
    private val logger = Logger.getInstance(ZedCliService::class.java)

    fun probe(root: Path): ZedCliSnapshot {
        return try {
            val versionOutput = execute(listOf("zed", "--version"), root, 5_000)
            when {
                versionOutput.isTimeout -> ZedCliSnapshot(available = false, failure = "The Zed CLI version probe timed out.")
                versionOutput.exitCode != 0 -> ZedCliSnapshot(available = false, failure = failureText(versionOutput, "zed exited with status ${versionOutput.exitCode}"))
                else -> {
                    val helpOutput = execute(listOf("zed", "--help"), root, 5_000)
                    val helpText = helpOutput.stdout + "\n" + helpOutput.stderr
                    if (helpOutput.exitCode == 0 && !looksLikePackageManager(helpText)) {
                        ZedCliSnapshot(available = false, failure = "The executable named 'zed' does not identify itself as the universal package manager. Configure the zed-pkg CLI path instead of the unrelated editor launcher.")
                    } else {
                        ZedCliSnapshot(
                            available = true,
                            versionText = ZedOutputRedactor.redact(versionOutput.stdout.trim().ifBlank { versionOutput.stderr.trim() }).ifBlank { "zed (version unknown)" },
                        )
                    }
                }
            }
        } catch (error: Exception) {
            logger.debug("Zed CLI probe failed", error)
            ZedCliSnapshot(available = false, failure = ZedOutputRedactor.redact(error.message ?: error.javaClass.simpleName))
        }
    }

    fun execute(command: List<String>, root: Path, timeoutMillis: Int = 120_000): ProcessOutput {
        require(command.isNotEmpty()) { "command must not be empty" }
        val commandLine = GeneralCommandLine(command.first())
            .withParameters(command.drop(1))
            .withWorkDirectory(root.toFile())
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment("NO_COLOR", "1")
            .withEnvironment("CLICOLOR", "0")
            .withEnvironment("TERM", "dumb")
        return CapturingProcessHandler(commandLine).runProcess(timeoutMillis)
    }

    private fun failureText(output: ProcessOutput, fallback: String): String {
        val raw = (output.stderr.ifBlank { output.stdout }).trim().ifBlank { fallback }
        return ZedOutputRedactor.redact(raw)
    }

    companion object {
        internal fun looksLikePackageManager(helpText: String): Boolean =
            "universal package manager" in helpText.lowercase()
    }
}
