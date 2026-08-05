import tech.zpkg.intellij.analysis.ZedProjectAnalyzer
import java.nio.file.Files

fun main() {
    val root = Files.createTempDirectory("zed-intellij-smoke")
    Files.writeString(
        root.resolve(".zpkg.toml"),
        """
        [package]
        org = "zed-pkg"
        name = "smoke"
        version = "0.1.0"

        [dependencies]
        "zed-pkg/zed-interfaces" = "^0.1.0"
        """.trimIndent(),
    )
    Files.writeString(root.resolve(".zpkg.lock"), "version = 1\n")
    val result = ZedProjectAnalyzer().analyze(root)
    check(result.packages.size == 1)
    check(result.packages.single().identity.displayName == "zed-pkg/smoke")
    check(result.packages.single().diagnostics.any { it.code == "ZED038" })
    println("core smoke passed: ${result.packages.single().diagnostics.map { it.code }}")
}
