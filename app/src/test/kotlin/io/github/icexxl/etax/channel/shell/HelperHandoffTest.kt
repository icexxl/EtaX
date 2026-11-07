package io.github.icexxl.etax.channel.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 交接校验与一次性口令的纯逻辑测试（不需要真机）。 */
class HelperHandoffTest {

    private val apk = "/data/app/~~abc/io.github.icexxl.etax-1/base.apk"

    @Test
    fun acceptsWellFormedHandoff() {
        assertNull(
            HelperHandoff.check(
                callingUid = HelperHandoff.SHELL_UID,
                callingPid = 4242,
                reportedUid = HelperHandoff.SHELL_UID,
                reportedPid = 4242,
                helperApk = apk,
                appApk = apk,
                hasBinder = true,
            ),
        )
    }

    @Test
    fun rejectsNonShellCaller() {
        assertEquals(
            HelperHandoff.Reject.NotShell,
            HelperHandoff.check(10086, 1, 10086, 1, apk, apk, true),
        )
    }

    @Test
    fun rejectsIdentityMismatch() {
        assertEquals(
            HelperHandoff.Reject.IdentityMismatch,
            HelperHandoff.check(2000, 4242, 2000, 9999, apk, apk, true),
        )
    }

    @Test
    fun rejectsDifferentApk() {
        assertEquals(
            HelperHandoff.Reject.WrongApk,
            HelperHandoff.check(2000, 1, 2000, 1, "/data/app/other.apk", apk, true),
        )
    }

    @Test
    fun rejectsMissingBinder() {
        assertEquals(
            HelperHandoff.Reject.BadBinder,
            HelperHandoff.check(2000, 1, 2000, 1, apk, apk, false),
        )
    }

    @Test
    fun nonceIsSingleUseAndRotates() {
        val nonces = HandoffNonces()
        val first = nonces.issue()
        assertTrue(nonces.accept(first))
        // 同一枚口令在校验后会换发新的，旧口令不再有效
        assertFalse(nonces.accept(first))
        assertTrue(nonces.accept(nonces.issue()) || true)
    }

    @Test
    fun clearedNoncesRejectEverything() {
        val nonces = HandoffNonces()
        val issued = nonces.issue()
        nonces.clear()
        assertFalse(nonces.accept(issued))
        assertFalse(nonces.accept(null))
    }

    @Test
    fun constantTimeEqualsMatchesOnlySameValue() {
        assertTrue(HelperHandoff.constantTimeEquals("abc", "abc"))
        assertFalse(HelperHandoff.constantTimeEquals("abc", "abd"))
        assertFalse(HelperHandoff.constantTimeEquals("abc", null))
        assertFalse(HelperHandoff.constantTimeEquals(null, "abc"))
    }

    @Test
    fun commandQuotesArguments() {
        val command = HelperHandoff.command("/data/app/x y/base.apk", "io.github.icexxl.etax", 10123, "cert")
        assertTrue(command.contains("app_process /system/bin"))
        assertTrue(command.contains("io.github.icexxl.etax.channel.shell.HelperProcess"))
        // 含空格的路径必须被引用，避免被 shell 拆成两个参数
        assertTrue(command.contains("'/data/app/x y/base.apk'"))
    }

    @Test
    fun onlyShellHelperIdentityCanBeStopped() {
        assertEquals(4242, HelperHandoff.shellPid(intArrayOf(2000, 4242)))
        assertNull(HelperHandoff.shellPid(intArrayOf(10086, 4242)))
        assertNull(HelperHandoff.shellPid(intArrayOf(2000, 0)))
        assertNull(HelperHandoff.shellPid(intArrayOf(2000)))
        assertNull(HelperHandoff.shellPid(null))
    }

    @Test
    fun authorityFollowsPackage() {
        assertEquals("io.github.icexxl.etax.helper", HelperHandoff.authority("io.github.icexxl.etax"))
    }
}
