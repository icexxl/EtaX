package io.github.icexxl.etax.config

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrefsLegacyToolMigrationTest {
    private lateinit var local: SharedPreferences
    private lateinit var remote: SharedPreferences
    private var savedLocal: Any? = null
    private val localField = Prefs::class.java.getDeclaredField("localAgent").apply { isAccessible = true }

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        savedLocal = localField.get(Prefs)
        local = context.getSharedPreferences("test_tool_migration_local", Context.MODE_PRIVATE)
        remote = context.getSharedPreferences("test_tool_migration_remote", Context.MODE_PRIVATE)
        local.edit().clear().commit()
        remote.edit().clear().commit()
        localField.set(Prefs, local)
    }

    @After fun tearDown() { localField.set(Prefs, savedLocal) }

    @Test fun remoteOnlyLegacyRestrictionsSurviveUnrelatedToolToggle() {
        remote.edit()
            .putBoolean(Prefs.Keys.AGENT_BROWSER_TOOLS, false)
            .putBoolean(Prefs.Keys.AGENT_TERMINAL_TOOLS, false)
            .commit()
        Prefs.reconcileAgentPreferences(remote)
        assertFalse(Prefs.isToolEnabled("web_search", local))
        assertFalse(Prefs.isToolEnabled("read_image", local))
        assertTrue(local.getBoolean(Prefs.Keys.AGENT_BROWSER_TOOLS, false))
        assertFalse(Prefs.isToolEnabled("browser_read", remote))
        assertTrue(Prefs.setToolEnabled("set_alarm", true))
        assertFalse(Prefs.isToolEnabled("web_search", local))
        assertFalse(Prefs.isToolEnabled("read_image", local))
    }

    @Test fun importedLegacyGroupsAreUnionedWithExistingIndividualChoices() {
        local.edit().putString(Prefs.Keys.AGENT_DISABLED_TOOLS_JSON, "[\"set_alarm\"]").commit()
        remote.edit().putBoolean(Prefs.Keys.AGENT_BROWSER_TOOLS, false).commit()
        Prefs.reconcileAgentPreferences(remote)
        assertFalse(Prefs.isToolEnabled("set_alarm", local))
        assertFalse(Prefs.isToolEnabled("web_search", local))
        assertTrue(Prefs.setToolEnabled("web_search", true))
        Prefs.reconcileAgentPreferences(remote)
        assertTrue(Prefs.isToolEnabled("web_search", local))
        assertFalse(Prefs.isToolEnabled("fetch_url", local))
        assertFalse(Prefs.isToolEnabled("set_alarm", local))
    }

    @Test fun lateLocalLegacyRestrictionIsMigratedBeforeFirstToolEdit() {
        local.edit().putBoolean(Prefs.Keys.AGENT_BROWSER_TOOLS, false).commit()
        assertTrue(Prefs.setToolEnabled("set_alarm", true))
        assertFalse(Prefs.isToolEnabled("web_search", local))
        assertFalse(Prefs.isToolEnabled("browser_screenshot", local))
    }
}
