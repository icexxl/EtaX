package io.github.icexxl.etax.agent.tool

internal sealed interface ToolExecutionDecision {
    data object Allow : ToolExecutionDecision

    data class Reject(
        val code: String,
        val message: String,
    ) : ToolExecutionDecision
}
