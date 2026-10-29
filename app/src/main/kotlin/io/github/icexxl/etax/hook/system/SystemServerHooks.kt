package io.github.icexxl.etax.hook.system

import io.github.icexxl.etax.hook.hyperos.HyperOsPowerHooks
import io.github.icexxl.etax.core.HookInstallation
import io.github.icexxl.etax.core.ModuleLogger

import io.github.libxposed.api.XposedModule

internal object SystemServerHooks {

    fun install(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation = HookInstallation.combine(
        group = "SystemServer",
        installations = listOf(
            AccessibilityProtectionHooks.install(module, logger, classLoader),
            ContextualSearchHooks.install(module, logger, classLoader),
            AssistantManager.install(module, logger, classLoader),
            HotwordSelfHealHooks.install(module, logger, classLoader),
            PowerHooks.install(module, logger, classLoader),
            HyperOsPowerHooks.install(module, logger, classLoader)
        )
    )
}
