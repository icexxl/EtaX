package io.github.icexxl.etax

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.icexxl.etax", "io.github.icexxl.etax"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.icexxl.etax:voice", "io.github.icexxl.etax"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.icexxl.etax:voice_session", "io.github.icexxl.etax"))
    }
}
