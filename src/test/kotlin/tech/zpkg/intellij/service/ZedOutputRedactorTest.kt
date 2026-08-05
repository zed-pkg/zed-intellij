package tech.zpkg.intellij.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZedOutputRedactorTest {
    @Test fun `redacts assignments bearer tokens and github tokens`() {
        val value = ZedOutputRedactor.redact("Authorization: Bearer abc.def token=secret ghp_abcdefghijklmnopqrstuvwxyz")
        assertFalse(value.contains("secret")); assertFalse(value.contains("ghp_")); assertTrue(value.contains("[REDACTED]"))
    }
    @Test fun `recognizes the package manager help marker`() {
        assertTrue(ZedCliService.looksLikePackageManager("Zed universal package manager"))
        assertFalse(ZedCliService.looksLikePackageManager("Zed collaborative code editor"))
    }
}
