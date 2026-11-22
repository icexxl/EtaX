package io.github.icexxl.etax.channel.openclaw

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** 未读时间与归档过滤开关的本地持久化测试。 */
@RunWith(RobolectricTestRunner::class)
class OpenClawSettingsTest {

    private lateinit var context: Context
    private lateinit var tokenStore: FakeTokenStore

    private class FakeTokenStore : OpenClawTokenStore {
        var value: String? = null
        override fun token(): String? = value
        override fun setToken(token: String) { value = token }
        override fun clear() { value = null }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("openclaw_channel", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        tokenStore = FakeTokenStore()
    }

    @Test
    fun `last seen timestamps survive a new settings instance`() {
        val a = OpenClawSettings(context, tokenStore)
        val seen = mapOf(
            "agent:main:eta:direct:c_a" to 1_700_000_000_000L,
            "agent:helper:eta:direct:c_b" to 1_700_000_500_000L,
        )
        a.saveLastSeenAt(seen)

        val restored = OpenClawSettings(context, tokenStore).loadLastSeenAt()
        assertEquals(seen, restored)
    }

    @Test
    fun `last seen ignores malformed lines and large maps stay bounded in format`() {
        val settings = OpenClawSettings(context, tokenStore)
        context.getSharedPreferences("openclaw_channel", Context.MODE_PRIVATE)
            .edit()
            .putString("last_seen_at", "agent:main:eta:direct:c_a\t123\nbroken\n\t9\nx\tnot-a-number")
            .commit()

        assertEquals(mapOf("agent:main:eta:direct:c_a" to 123L), settings.loadLastSeenAt())

        settings.saveLastSeenAt(emptyMap())
        assertEquals(emptyMap<String, Long>(), settings.loadLastSeenAt())
    }

    @Test
    fun `changing gateway or token clears cursor and previous selection`() {
        val settings = OpenClawSettings(context, tokenStore)
        val first = OpenClawConfig("https://first.example", "one")
        settings.save(first)
        settings.saveLastSeq(42)
        settings.saveLastAgentId("agent-a")
        settings.saveLastSessionKey("agent:agent-a:eta:direct:old")
        settings.saveLastSeenAt(mapOf("agent:agent-a:eta:direct:old" to 100L))
        settings.save(first.copy(baseUrl = "https://first.example/"))
        assertEquals(42L, settings.lastSeq())

        settings.save(first.copy(token = "two"))
        assertEquals(0L, settings.lastSeq())
        assertEquals("", settings.lastAgentId())
        assertEquals("", settings.lastSessionKey())
        assertTrue(settings.loadLastSeenAt().isEmpty())

        settings.saveLastSeq(7)
        settings.save(first.copy(baseUrl = "https://second.example", token = "two"))
        assertEquals(0L, settings.lastSeq())
    }

    @Test
    fun `archive filter defaults off and survives a new settings instance`() {
        val a = OpenClawSettings(context, tokenStore)
        assertFalse(a.showArchivedSessions())

        a.saveShowArchivedSessions(true)
        assertTrue(OpenClawSettings(context, tokenStore).showArchivedSessions())
    }
}
