package io.github.icexxl.etax.channel.openclaw

import android.content.Context

/** 主界面模式：Eta 自己的对话 / OpenClaw 渠道。 */
enum class HomeMode {
    ETA,
    OPENCLAW,
    ;

    companion object {
        fun fromName(raw: String?): HomeMode =
            entries.firstOrNull { it.name == raw } ?: ETA
    }
}

internal class HomeModeStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): HomeMode = HomeMode.fromName(prefs.getString(KEY_MODE, null))

    fun save(mode: HomeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "openclaw_home"
        const val KEY_MODE = "home_mode"
    }
}
