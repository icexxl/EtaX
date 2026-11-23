package io.github.icexxl.etax.agent.virtualdisplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建屏通道判定。
 *
 * 回归护栏：Stellar/Shizuku 后台服务曾经不被算作可用后端，
 * 导致授权已就绪时 launch_app 仍以 backend_unavailable 回落到物理主屏。
 */
class VirtualDisplayBackendAvailabilityTest {

    @Test
    fun `root alone is enough`() {
        assertTrue(
            VirtualDisplayController.isBackendAvailable(
                rootAvailable = true,
                lsposedAvailable = false,
                daemonAvailable = false,
            ),
        )
    }

    @Test
    fun `lsposed alone is enough`() {
        assertTrue(
            VirtualDisplayController.isBackendAvailable(
                rootAvailable = false,
                lsposedAvailable = true,
                daemonAvailable = false,
            ),
        )
    }

    @Test
    fun `stellar daemon alone is enough`() {
        assertTrue(
            VirtualDisplayController.isBackendAvailable(
                rootAvailable = false,
                lsposedAvailable = false,
                daemonAvailable = true,
            ),
        )
    }

    @Test
    fun `no channel means unavailable`() {
        assertFalse(
            VirtualDisplayController.isBackendAvailable(
                rootAvailable = false,
                lsposedAvailable = false,
                daemonAvailable = false,
            ),
        )
    }
}
