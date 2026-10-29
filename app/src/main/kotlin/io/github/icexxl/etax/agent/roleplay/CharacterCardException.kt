package io.github.icexxl.etax.agent.roleplay

internal class CharacterCardException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
