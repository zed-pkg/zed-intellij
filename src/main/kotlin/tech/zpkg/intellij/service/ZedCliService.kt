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

    fun probe(root: Path): ZedCliSnapshot = try {
        val output = execute(listOf("zed", "--version"), root, 5_000)
        if (output.exitCode == 0) {
            ZedCliSnapshot(
                available = true,
                versionText = output.stdout.trim().ifBlank { "zed (version unknown)" },
            )
        } else {
            ZedCliSnapshot(
                available = false,
                failure = output.stderr.trim().ifBlank { "zed exited with status ${output.exitCode}" },
            )
        }
    } catch (error: Exception) {
        logger.debug("Zed CLI probe failed", error)
        ZedCliSnapshot(available = false, failure = error.message ?: error.javaClass.simpleName)
    }

    fun execute(command: List<String>, root: Path, timeoutMillis: Int = 120_000): ProcessOutput {
        require(command.isNotEmpty()) { "command must not be empty" }
        val commandLine = GeneralCommandLine(command.first())
            .withParameters(command.drop(1))
            .withWorkDirectory(root.toFile())
            .withCharset(StandardCharsets.UTF_8)
        return CapturingProcessHandler(commandLine).runProcess(timeoutMillis)
    }
}
