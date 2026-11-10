package io.github.icexxl.etax.agent.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReplyNotificationTest {
    @Test fun onlySuccessfulBackgroundChatRepliesNotify() {
        val source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE
        assertTrue(AgentReplyNotification.shouldNotify(false, true, AgentRuntimeWire.OP_CHAT, source, "answer"))
        assertFalse(AgentReplyNotification.shouldNotify(true, true, AgentRuntimeWire.OP_CHAT, source, "answer"))
        assertFalse(AgentReplyNotification.shouldNotify(false, false, AgentRuntimeWire.OP_CHAT, source, "answer"))
        assertFalse(AgentReplyNotification.shouldNotify(false, true, AgentRuntimeWire.OP_CHAT, source, "  "))
        assertFalse(AgentReplyNotification.shouldNotify(false, true, "rewrite", source, "answer"))
        assertFalse(AgentReplyNotification.shouldNotify(false, true, AgentRuntimeWire.OP_CHAT, "breeno", "answer"))
    }
}
