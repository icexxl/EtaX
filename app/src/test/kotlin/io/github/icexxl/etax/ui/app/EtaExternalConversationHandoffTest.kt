package io.github.icexxl.etax.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.icexxl.etax.channel.openclaw.HomeMode

class EtaExternalConversationHandoffTest {
    @Test fun etaCompletionHandoffLeavesOpenClawBeforeShowingSelectedConversation() {
        var mode = HomeMode.OPENCLAW
        var persistedMode = mode
        var paneOpen = true
        var route = "settings"
        val selectedConversation = "eta-completion-target"
        var visibleConversation: String? = null
        revealEtaExternalConversation(
            selectEtaMode = { mode = HomeMode.ETA; persistedMode = mode },
            closeConversationPane = { paneOpen = false },
            popToHome = {
                assertEquals(HomeMode.ETA, mode)
                assertEquals(false, paneOpen)
                route = "home"
                visibleConversation = selectedConversation
            },
        )
        assertEquals(HomeMode.ETA, persistedMode)
        assertEquals("home", route)
        assertEquals("eta-completion-target", visibleConversation)
    }
}
