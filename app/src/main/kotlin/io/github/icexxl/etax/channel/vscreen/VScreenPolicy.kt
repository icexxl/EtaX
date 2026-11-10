package io.github.icexxl.etax.channel.vscreen

internal object VScreenPolicy {
    fun hasVisiblePackage(tasks: List<VScreenBackend.DisplayTask>, pkg: String): Boolean =
        tasks.any { it.visible && it.activityType !in 2..5 &&
            (it.pkg == pkg || it.component?.substringBefore('/') == pkg) }

    /** Prefers our own named screen; a daemon without names has at most one, so that one is safe. */
    fun pickAdoptable(displays: List<VScreenBackend.Existing>, name: String): VScreenBackend.Existing? =
        displays.firstOrNull { it.name == name } ?: displays.singleOrNull()

    fun supportsBackgroundFocus(flags: Int): Boolean =
        flags and (1 shl 10) != 0 && flags and (1 shl 14) != 0 && flags and (1 shl 16) != 0

    fun canTakeBack(taskId: Int, taskDisplayId: Int?, focusedDisplayId: Int?, focusedTaskId: Int?): Boolean =
        taskId > 0 && taskDisplayId == 0 && focusedDisplayId == 0 && focusedTaskId == taskId

    fun allowsMove(source: Int, target: Int, owned: Set<Int>): Boolean =
        (target == 0 && source > 0 && source in owned) ||
            (source == 0 && target > 0 && target in owned)
}
