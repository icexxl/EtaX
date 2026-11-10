package io.github.icexxl.etax.channel.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VScreenPolicyTest {
    @Test fun backgroundDisplayRequiresTrustedOwnFocusAndNoTopFocusSteal() {
        val trustedOwnFocus = (1 shl 10) or (1 shl 14)
        assertTrue(VScreenPolicy.supportsBackgroundFocus(trustedOwnFocus or (1 shl 16)))
        assertFalse(VScreenPolicy.supportsBackgroundFocus(trustedOwnFocus))
        assertFalse(VScreenPolicy.supportsBackgroundFocus((1 shl 10) or (1 shl 16)))
        assertFalse(VScreenPolicy.supportsBackgroundFocus((1 shl 14) or (1 shl 16)))
    }

    @Test fun takebackRequiresExactHandedTaskToBeFocusedOnMain() {
        assertTrue(VScreenPolicy.canTakeBack(12, 0, 0, 12))
        assertFalse(VScreenPolicy.canTakeBack(12, 0, 0, 13))
        assertFalse(VScreenPolicy.canTakeBack(12, 9, 0, 12))
        assertFalse(VScreenPolicy.canTakeBack(12, 0, 9, 12))
        assertFalse(VScreenPolicy.canTakeBack(-1, 0, 0, -1))
    }

    @Test fun moveRequiresOneOwnedDisplayAndMain() {
        assertTrue(VScreenPolicy.allowsMove(0, 8, setOf(8)))
        assertTrue(VScreenPolicy.allowsMove(8, 0, setOf(8)))
        assertFalse(VScreenPolicy.allowsMove(0, 9, setOf(8)))
        assertFalse(VScreenPolicy.allowsMove(8, 9, setOf(8, 9)))
        assertFalse(VScreenPolicy.allowsMove(0, 0, setOf(0)))
    }

    @Test fun adoptionPrefersOwnNamedScreenAndRefusesAmbiguity() {
        fun screen(id: Int, name: String?) = VScreenBackend.Existing(id, name, 1080, 2400, 420, 0)
        assertEquals(7, VScreenPolicy.pickAdoptable(listOf(screen(3, "other"), screen(7, "etax")), "etax")?.displayId)
        // A daemon without names has at most one screen, so adopting it is safe.
        assertEquals(9, VScreenPolicy.pickAdoptable(listOf(screen(9, null)), "etax")?.displayId)
        // Several unnamed screens: never guess which one belongs to us.
        assertNull(VScreenPolicy.pickAdoptable(listOf(screen(9, null), screen(10, null)), "etax"))
        assertEquals(null, VScreenPolicy.pickAdoptable(emptyList(), "etax"))
    }

    @Test fun launchRequiresVisibleAppOnRequestedDisplay() {
        val app = VScreenBackend.DisplayTask(1, "example.app/.Main", true, 1, null)
        assertTrue(VScreenPolicy.hasVisiblePackage(listOf(app), "example.app"))
        assertFalse(VScreenPolicy.hasVisiblePackage(listOf(app.copy(visible = false)), "example.app"))
        assertFalse(VScreenPolicy.hasVisiblePackage(listOf(app.copy(activityType = 2)), "example.app"))
        assertFalse(VScreenPolicy.hasVisiblePackage(listOf(app), "other.app"))
    }
}
