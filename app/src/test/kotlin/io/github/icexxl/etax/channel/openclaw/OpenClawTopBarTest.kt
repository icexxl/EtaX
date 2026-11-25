package io.github.icexxl.etax.channel.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawTopBarTest {
    @Test
    fun `model summary and selection matching handle provider and default flags`() {
        val primary = ModelOptionDto(
            model = "claude-sonnet-4-5",
            modelProvider = "anthropic",
            name = "Claude Sonnet 4.5",
            alias = "sonnet",
            contextTokens = 200_000,
            isDefault = true,
        )
        val secondary = ModelOptionDto(
            model = "gpt-5.4",
            modelProvider = "openai",
            contextTokens = 400_000,
            isDefault = false,
        )

        assertEquals("anthropic · 默认 · sonnet · 200K", buildModelSummary(primary))
        assertEquals("openai · 400K", buildModelSummary(secondary))
        assertTrue(isModelOptionSelected(secondary, "gpt-5.4", "openai"))
        assertFalse(isModelOptionSelected(secondary, "gpt-5.4", "anthropic"))
    }
}
