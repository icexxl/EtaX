package io.github.mangi.eta.ui.screens.browser

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentBrowserActivityTest {
    @Test
    fun browserWindowIsPrivateAndSharesTheSessionProcess() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context, AgentBrowserActivity::class.java)
        val info = context.packageManager.getActivityInfo(component, 0)
        assertFalse(info.exported)
        // Shizuku 的 provider AAR 带空 <application>，会让 Robolectric 的
        // applicationInfo.processName 为 null；这里比较"是否与宿主同进程"的意图：
        // 未声明 android:process 时归一到宿主包名。
        val expected = context.applicationInfo.processName ?: context.packageName
        val actual = info.processName ?: context.packageName
        assertEquals(expected, actual)
    }

    @Test
    fun explicitLaunchKeepsConversationIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val intent = AgentBrowserActivity.createIntent(context, "conversation-a")
        assertEquals(ComponentName(context, AgentBrowserActivity::class.java), intent.component)
        assertEquals("conversation-a", intent.getStringExtra("io.github.mangi.eta.extra.BROWSER_SESSION_KEY"))
    }

    @Test
    fun missingSessionDoesNotOpenAnUnrelatedPage() {
        val controller = Robolectric.buildActivity(AgentBrowserActivity::class.java, Intent()).create()
        try {
            assertTrue(controller.get().isFinishing)
        } finally {
            controller.destroy()
        }
    }
}
