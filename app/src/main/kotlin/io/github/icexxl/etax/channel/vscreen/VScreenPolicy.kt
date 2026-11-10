package io.github.icexxl.etax.channel.vscreen

internal object VScreenPolicy {
    fun hasVisiblePackage(tasks: List<VScreenBackend.DisplayTask>, pkg: String): Boolean =
        tasks.any { it.visible && it.activityType !in 2..5 &&
            (it.pkg == pkg || it.component?.substringBefore('/') == pkg) }

    /** Prefers our own named screen; a daemon without names has at most one, so that one is safe. */
    fun pickAdoptable(displays: List<VScreenBackend.Existing>, name: String): VScreenBackend.Existing? =
        displays.firstOrNull { it.name == name } ?: displays.singleOrNull()

    fun allowsMove(source: Int, target: Int, owned: Set<Int>): Boolean =
        (target == 0 && source > 0 && source in owned) ||
            (source == 0 && target > 0 && target in owned)
}
