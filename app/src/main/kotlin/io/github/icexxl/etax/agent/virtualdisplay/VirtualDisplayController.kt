package io.github.icexxl.etax.agent.virtualdisplay

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import io.github.icexxl.etax.agent.accessibility.AgentAccessibilityService
import io.github.icexxl.etax.channel.vscreen.VScreenBackend
import io.github.icexxl.etax.channel.vscreen.VScreenPolicy
import io.github.icexxl.etax.agent.device.BoundedRootCommandExecutor
import io.github.icexxl.etax.agent.device.DeviceToolContract
import io.github.icexxl.etax.agent.device.RootAccess
import io.github.icexxl.etax.agent.terminal.ShellProcessSupervisor
import io.github.icexxl.etax.core.AndroidAgentLogger
import io.github.icexxl.etax.core.safeLogType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 虚拟屏会话与生命周期中枢。
 *
 * 画面通过常驻 [ImageReader] 接收：
 * - 收起态（仅胶囊微缩取景器可见）按 ~6fps 节流更新，兼顾真实动感与低功耗；
 * - 展开态（画廊装裱视窗 / 用户手动接管）提升至 ~30fps 实时更新；
 * - Agent 调用 observe_screen(include_screenshot=true) 时直接提取最新原尺寸 Bitmap，无需等待无障碍截图冷却。
 */
internal object VirtualDisplayController {

    private val lock = Any()
    private val creationGate = Any()
    private val sessionState = VirtualDisplaySessionState()
    private val mutableSnapshots = MutableStateFlow(VirtualDisplaySnapshot.Inactive)
    val snapshots: StateFlow<VirtualDisplaySnapshot> = mutableSnapshots.asStateFlow()

    private val frameSequenceGenerator = AtomicLong(0L)
    private val touchSequenceGenerator = AtomicLong(0L)

    @Volatile
    private var highFrequencyPreview: Boolean = false

    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var inProcessVirtualDisplay: VirtualDisplay? = null
    private var rootBridgeProcess: Process? = null
    /** Shizuku daemon owns this display independently of the app preview. */
    private var shellDisplayId: Int = Display.INVALID_DISPLAY
    /** 接管时的确切任务，禁止按主屏最上层任务猜测回程目标。 */
    private var handedOverTaskId: Int = -1
    private var handedOverDisplayId: Int = Display.INVALID_DISPLAY
    /** 建屏时记下的 Application context：注入回调里没有 context 参数。 */
    @Volatile
    private var appContextOrNull: Context? = null
    private var processSupervisor: ShellProcessSupervisor? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var sessionToken: VirtualDisplaySessionState.Token? = null

    private var activeDisplayId: Int = Display.DEFAULT_DISPLAY
    private var displayWidth: Int = 1080
    private var displayHeight: Int = 2400
    private var displayDensityDpi: Int = 420
    private var latestFullBitmap: Bitmap? = null
    private var lastUiFrameAtElapsedMs: Long = 0L
    private val mutedPackages = linkedSetOf<String>()

    fun isBackendAvailable(
        rootAvailable: Boolean,
        lsposedAvailable: Boolean,
        shellAvailable: Boolean = false,
    ): Boolean = rootAvailable || lsposedAvailable || shellAvailable

    fun setStageExpanded(expanded: Boolean) {
        highFrequencyPreview = expanded
    }

    fun setUserControlling(controlling: Boolean) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.userControlling == controlling) return
            mutableSnapshots.value = current.copy(userControlling = controlling)
            if (controlling) highFrequencyPreview = true
        }
    }

    fun currentDisplayId(): Int = synchronized(lock) {
        sessionState.active?.displayId ?: Display.DEFAULT_DISPLAY
    }

    fun isShellDisplay(displayId: Int): Boolean = synchronized(lock) {
        CanvasRoutingPolicy.isVirtualDisplay(displayId) && shellDisplayId == displayId &&
            mutableSnapshots.value.active
    }

    private fun shellDisplayAlive(context: Context, displayId: Int): Boolean =
        shellDisplayId != displayId || (VScreenBackend.isReady() &&
            VScreenBackend.list().getOrNull()?.any { it.displayId == displayId } == true)

    private fun isActiveDisplayValid(displayId: Int): Boolean {
        val active = synchronized(lock) { sessionState.active?.displayId == displayId && mutableSnapshots.value.active }
        if (!active || !CanvasRoutingPolicy.isVirtualDisplay(displayId)) return false
        val context = appContextOrNull ?: return false
        val manager = context.getSystemService(DisplayManager::class.java) ?: return false
        val usable = isDisplayUsable(manager, displayId) && shellDisplayAlive(context, displayId)
        if (!usable) {
            val token = synchronized(lock) { sessionToken.takeIf { sessionState.active?.displayId == displayId } }
            if (token != null) releaseSession(context, { RootAccess.isGranted }, token)
        }
        return usable
    }

    /** Never direct a virtual-screen key to the physical display. */
    fun injectShellKey(displayId: Int, keyCode: Int): Boolean {
        if (!isShellDisplay(displayId) || !isActiveDisplayValid(displayId)) return false
        VScreenBackend.touch(displayId)
        return VScreenBackend.injectKey(displayId, keyCode, false).getOrDefault(false)
    }

    fun injectBackendTap(displayId: Int, x: Int, y: Int): Boolean =
        isActiveDisplayValid(displayId) && injectViaVScreen(displayId, x, y, false)

    fun injectBackendSwipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, duration: Int): Boolean =
        isActiveDisplayValid(displayId) && injectSwipeViaVScreen(displayId, x1, y1, x2, y2, duration)

    /** A successful am command alone does not establish the display routing outcome. */
    private fun awaitPackageOnDisplay(displayId: Int, packageName: String): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        do {
            if (!isShellDisplay(displayId)) return false
            if (VScreenBackend.listTasks(displayId).getOrNull()?.any { it.pkg == packageName } == true) return true
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            val id = synchronized(lock) { shellDisplayId }
            if (id == Display.INVALID_DISPLAY) return
            // Binder call outside the lock: a busy daemon must not freeze UI readers.
            runCatching { VScreenBackend.touch(id) }
            synchronized(lock) {
                if (shellDisplayId == id) frameHandler?.postDelayed(this, 10_000L)
            }
        }
    }

    /**
     * 确保受信任虚拟屏已就绪；失败返回 null，调用方不得将虚拟屏动作回退到物理主屏。
     */
    /** Serializes screen creation; the state lock stays free for UI readers. */
    fun ensureVirtualDisplay(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): Int? {
        val appContext = context.applicationContext
        appContextOrNull = appContext
        val (width, height, densityDpi) = resolvePhysicalDisplayMetrics(appContext)
        synchronized(creationGate) {
            val manager = appContext.getSystemService(DisplayManager::class.java) ?: return null
            val current = synchronized(lock) { sessionState.active }
            if (current != null && shellDisplayAlive(appContext, current.displayId) &&
                isDisplayUsable(manager, current.displayId)
            ) return current.displayId
            if (current != null) releaseSession(appContext, rootAvailable, current.token)

            val token = synchronized(lock) {
                sessionState.begin().also { sessionToken = it }
            }
            val thread = HandlerThread("eta-virtual-display-frame").apply { start() }
            val handler = Handler(thread.looper)
            val reader = try {
                ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            } catch (error: Exception) {
                thread.quitSafely()
                synchronized(lock) { sessionState.invalidate(token); sessionToken = null }
                return null
            }
            reader.setOnImageAvailableListener(::onFrameAvailable, handler)
            synchronized(lock) {
                appContextOrNull = appContext
                frameThread = thread
                frameHandler = handler
                imageReader = reader
                displayWidth = width
                displayHeight = height
                displayDensityDpi = densityDpi
            }

            var accepted = false
            var registeredListener: DisplayManager.DisplayListener? = null
            try {
                var id: Int? = null
                if (VScreenBackend.isReady()) {
                    id = attachDaemonSession(appContext, rootAvailable, token)
                } else {
                    val created = tryCreateInProcessVirtualDisplay(appContext, reader.surface, width, height, densityDpi)
                    if (created != null) {
                        val createdId = created.display?.displayId ?: Display.INVALID_DISPLAY
                        if (CanvasRoutingPolicy.isVirtualDisplay(createdId)) {
                            synchronized(lock) { inProcessVirtualDisplay = created }
                            id = createdId
                        } else created.release()
                    }
                    if (id == null && rootAvailable()) {
                        id = tryCreateRootBridgedVirtualDisplay(appContext, reader.surface, width, height, densityDpi, rootAvailable)
                    }
                }
                if (id == null || !CanvasRoutingPolicy.isVirtualDisplay(id) || !isDisplayUsable(manager, id)) return null
                val listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(displayId: Int) = Unit
                    override fun onDisplayChanged(displayId: Int) {
                        if (displayId == id && !isDisplayUsable(manager, id)) releaseSession(appContext, rootAvailable, token)
                    }
                    override fun onDisplayRemoved(displayId: Int) {
                        if (displayId == id) releaseSession(appContext, rootAvailable, token)
                    }
                }
                manager.registerDisplayListener(listener, handler)
                registeredListener = listener
                val packageName = if (isShellDisplay(id)) {
                    VScreenBackend.listTasks(id).getOrNull()?.firstOrNull { it.visible }?.pkg.orEmpty()
                } else ""
                synchronized(lock) {
                    if (!sessionState.activate(token, id)) return null
                    displayListener = listener
                    activeDisplayId = id
                    mutableSnapshots.value = VirtualDisplaySnapshot(
                        active = true, displayId = id, width = displayWidth, height = displayHeight,
                        packageName = packageName,
                    )
                    frameHandler?.post(heartbeat)
                    accepted = true
                }
                AndroidAgentLogger.info("Virtual display ready: displayId=$id size=${displayWidth}x$displayHeight")
                return id
            } catch (exception: Exception) {
                AndroidAgentLogger.warn("Virtual display creation failed: type=${exception.safeLogType()}")
                return null
            } finally {
                if (!accepted) {
                    registeredListener?.let { runCatching { manager.unregisterDisplayListener(it) } }
                    releaseSession(appContext, rootAvailable, token)
                }
            }
        }
    }

    private fun isDisplayUsable(manager: DisplayManager, displayId: Int): Boolean = try {
        val display = manager.getDisplay(displayId)
        display != null && display.isValid && (
            if (shellDisplayId == displayId) {
                VScreenBackend.list().getOrNull()?.firstOrNull { it.displayId == displayId }
                    ?.let { VScreenPolicy.supportsBackgroundFocus(it.flags) } == true
            } else CanvasRoutingPolicy.hasRequiredDisplayFlags(display.flags)
        )
    } catch (exception: Exception) {
        AndroidAgentLogger.warnThrottled("virtual_display_validation_failed") {
            "Virtual display validation failed: type=${exception.safeLogType()}"
        }
        false
    }

    /** Adopts or creates the daemon screen. Returns null (and resets state) on any rejection. */
    private fun attachDaemonSession(
        appContext: Context,
        rootAvailable: () -> Boolean,
        token: VirtualDisplaySessionState.Token,
    ): Int? {
        val readerAtEntry = synchronized(lock) { imageReader } ?: return null
        val remoteDisplays = VScreenBackend.list().getOrNull()
            ?: return null
        val existing = VScreenPolicy.pickAdoptable(remoteDisplays, CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME)
        val daemonId = if (existing != null) {
            val needsResize = existing.width != displayWidth || existing.height != displayHeight
            val target = if (needsResize) {
                ImageReader.newInstance(existing.width, existing.height, PixelFormat.RGBA_8888, 3)
                    .also { it.setOnImageAvailableListener(::onFrameAvailable, frameHandler) }
                    .also { resized -> synchronized(lock) {
                        if (!sessionState.isCurrent(token)) {
                            resized.close()
                            return null
                        }
                        imageReader = resized
                        displayWidth = existing.width
                        displayHeight = existing.height
                        displayDensityDpi = existing.dpi
                    } }
            } else readerAtEntry
            VScreenBackend.setSurface(existing.displayId, target.surface).getOrNull()?.let {
                if (needsResize) readerAtEntry.close()
                existing.displayId
            }
        } else {
            VScreenBackend.create(
                CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
                displayWidth, displayHeight, displayDensityDpi, readerAtEntry.surface,
            ).getOrNull()?.displayId
        }
        if (daemonId == null || !CanvasRoutingPolicy.isVirtualDisplay(daemonId)) {
            // A rejected daemon operation must not silently change backends or move tasks.
            return null
        }
        val packageName = VScreenBackend.listTasks(daemonId).getOrNull()
            ?.firstOrNull { it.visible }?.pkg.orEmpty()
        val committed = synchronized(lock) {
            if (imageReader == null || !sessionState.isCurrent(token)) {
                false
            } else {
                shellDisplayId = daemonId
                activeDisplayId = daemonId
                mutableSnapshots.value = VirtualDisplaySnapshot(
                    active = true, displayId = daemonId,
                    width = displayWidth, height = displayHeight, packageName = packageName,
                )
                frameHandler?.post(heartbeat)
                true
            }
        }
        if (!committed) {
            // The session was detached while we were attaching; do not leak the daemon screen.
            // This may be an adopted daemon display owned by another app lifetime; stale attach
            // must not explicitly destroy it. Parking is harmless and lets the daemon TTL apply.
            thread(name = "etax-vscreen-park-stale", isDaemon = true) {
                runCatching { VScreenBackend.park(daemonId) }
            }
            return null
        }
        return daemonId
    }

    /**
     * 在虚拟屏上启动指定应用，并对其施加后台音频焦点静默保护。
     */
    fun launchAppOnVirtualDisplay(
        context: Context,
        packageName: String,
        appName: String,
        launchIntent: Intent,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        checkCancellation: () -> Unit = {},
    ): Int? {
        checkCancellation()
        val displayId = ensureVirtualDisplay(context, rootAvailable) ?: return null
        checkCancellation()
        val appContext = context.applicationContext
        if (!isShellDisplay(displayId)) {
            mutePackageAudioFocusIfPossible(appContext, packageName, rootAvailable)
        }

        if (!isActiveDisplayValid(displayId)) return null
        val startedInProcess = shellDisplayId != displayId && runCatching {
            val intent = Intent(launchIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            appContext.startActivity(intent, options.toBundle())
            true
        }.getOrElse { throwable ->
            AndroidAgentLogger.debug {
                "Virtual display startActivity in-process fallback: type=${throwable.safeLogType()}"
            }
            false
        }

        val startedViaShell = if (!startedInProcess && shellDisplayId == displayId) {
            VScreenBackend.touch(displayId)
            VScreenBackend.launch(displayId, packageName, launchIntent.component?.className).isSuccess &&
                awaitPackageOnDisplay(displayId, packageName)
        } else false
        if (shellDisplayId == displayId && !startedViaShell) return null
        if (shellDisplayId != displayId && (!startedInProcess || rootAvailable())) {
            // Root 下补发一次指定 displayId 的 am start，确保后台启动限制或跨屏栈迁移生效
            if (rootAvailable()) {
                val userId = DeviceToolContract.appUserId(appContext)
                val component = launchIntent.component?.flattenToShortString() ?: return null
                val cmd = "am start --user $userId --display $displayId -n ${DeviceToolContract.quote(component)}"
                if (!startedInProcess) {
                    val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                    try {
                        val res = exec.execute(cmd, timeoutMillis = 6_000L)
                        if (res.exitCode != 0 && !startedInProcess) return null
                    } finally {
                        exec.close()
                    }
                }
            } else if (!startedInProcess && !startedViaShell) {
                return null
            }
        }

        synchronized(lock) {
            val current = mutableSnapshots.value
            if (current.active && current.displayId == displayId) {
                mutableSnapshots.value = current.copy(
                    appName = appName,
                    packageName = packageName,
                    materializingToMain = false,
                )
            }
        }
        return displayId
    }

    /**
     * 在虚拟屏上打开外部 URI。
     */
    fun openUriOnVirtualDisplay(
        context: Context,
        intent: Intent,
        targetLabel: String,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
        checkCancellation: () -> Unit = {},
    ): Int? {
        checkCancellation()
        val displayId = ensureVirtualDisplay(context, rootAvailable) ?: return null
        checkCancellation()
        val appContext = context.applicationContext
        if (!isActiveDisplayValid(displayId)) return null
        if (shellDisplayId == displayId) {
            VScreenBackend.touch(displayId)
            val packageName = intent.`package` ?: intent.component?.packageName
                ?: appContext.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    ?.activityInfo?.packageName
                ?: return null
            if (VScreenBackend.launch(displayId, packageName, intent.component?.className, intent.dataString).isFailure ||
                !awaitPackageOnDisplay(displayId, packageName)
            ) return null
            synchronized(lock) {
                mutableSnapshots.value = mutableSnapshots.value.copy(appName = targetLabel, packageName = packageName)
            }
            return displayId
        }
        val started = runCatching {
            val launch = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            appContext.startActivity(launch, options.toBundle())
            true
        }.getOrDefault(false)

        if (!started && rootAvailable()) {
            val uriString = intent.dataString ?: return null
            val userId = DeviceToolContract.appUserId(appContext)
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            val ok = try {
                exec.execute(
                    "am start --user $userId --display $displayId -a android.intent.action.VIEW -d ${DeviceToolContract.quote(uriString)}",
                    timeoutMillis = 6_000L,
                ).exitCode == 0
            } finally {
                exec.close()
            }
            if (!ok) return null
        } else if (!started) {
            return null
        }

        synchronized(lock) {
            val current = mutableSnapshots.value
            if (current.active && current.displayId == displayId) {
                mutableSnapshots.value = current.copy(
                    appName = targetLabel.ifBlank { current.appName },
                    materializingToMain = false,
                )
            }
        }
        return displayId
    }

    /**
     * 将虚拟屏中正在运行的应用无缝破壁移交到物理主屏（DEFAULT_DISPLAY）。
     * 先触发 UI 层的 260ms 全屏放大贴合过渡，同时在底层原位迁移 RootTask。
     */
    fun onTopPackageObserved(
        context: Context,
        observedPackage: String,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        val pkg = observedPackage.trim()
        if (pkg.isBlank() || pkg == "android" || pkg == "com.android.systemui" || pkg == context.packageName) return
        val snapshot = synchronized(lock) { mutableSnapshots.value }
        if (!snapshot.active || snapshot.packageName == pkg) return
        val appContext = context.applicationContext
        val appName = runCatching {
            val info = appContext.packageManager.getApplicationInfo(pkg, 0)
            appContext.packageManager.getApplicationLabel(info).toString().trim().ifBlank { pkg }
        }.getOrDefault(pkg)
        if (!isShellDisplay(snapshot.displayId)) mutePackageAudioFocusIfPossible(appContext, pkg, rootAvailable)
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (current.active && current.displayId == snapshot.displayId) {
                mutableSnapshots.value = current.copy(packageName = pkg, appName = appName)
            }
        }
    }

    fun handoffToMainDisplay(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): Boolean {
        val snapshot = synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || !CanvasRoutingPolicy.isVirtualDisplay(current.displayId)) {
                return false
            }
            mutableSnapshots.value = current.copy(
                userControlling = false,
                materializingToMain = true,
            )
            highFrequencyPreview = true
            current
        }

        val appContext = context.applicationContext
        restoreMutedPackages(appContext, rootAvailable)

        val moved = if (isShellDisplay(snapshot.displayId)) {
            val taskId = VScreenBackend.topTaskId(snapshot.displayId).getOrNull()
            taskId != null && VScreenBackend.moveTask(taskId, Display.DEFAULT_DISPLAY).getOrDefault(false) &&
                VScreenBackend.displayOfTask(taskId).getOrNull() == Display.DEFAULT_DISPLAY
        } else moveVirtualTaskToMainDisplay(
            context = appContext,
            displayId = snapshot.displayId,
            packageName = snapshot.packageName,
            rootAvailable = rootAvailable,
        )

        if (!moved) {
            synchronized(lock) {
                if (mutableSnapshots.value.displayId == snapshot.displayId) {
                    mutableSnapshots.value = mutableSnapshots.value.copy(materializingToMain = false)
                }
            }
            return false
        }
        // 留出 260ms 让 VirtualDisplayMaterializeOverlay 完成从画板到物理全屏的弹簧贴合动画
        try {
            Thread.sleep(260L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        release(appContext, rootAvailable)
        return true
    }

    /**
     * 把虚拟屏最上面的任务交给用户在主屏处理（接管），但**保留虚拟屏**，
     * 这样用户处理完后还能挪回来继续。返回是否成功。
     *
     * 与 [handoffToMainDisplay] 的区别：那个是任务结束时的最终移交，会释放屏；
     * 这里是为“等用户操作”准备的临时移交。
     */
    fun handOverTopTaskToUser(context: Context): Boolean {
        val snapshot = synchronized(lock) { mutableSnapshots.value }
        if (!snapshot.active || !CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)) return false
        if (!isShellDisplay(snapshot.displayId)) return handoffToMainDisplay(context)
        if (!isActiveDisplayValid(snapshot.displayId)) return false
        VScreenBackend.touch(snapshot.displayId)
        val taskId = VScreenBackend.topTaskId(snapshot.displayId).getOrNull() ?: return false
        val moved = VScreenBackend.moveTask(taskId, Display.DEFAULT_DISPLAY).getOrDefault(false) &&
            VScreenBackend.displayOfTask(taskId).getOrNull() == Display.DEFAULT_DISPLAY
        if (moved) synchronized(lock) {
            handedOverTaskId = taskId
            handedOverDisplayId = snapshot.displayId
            mutableSnapshots.value = mutableSnapshots.value.copy(userControlling = true)
        }
        return moved
    }

    /** Return only the exact task handed to the user, never the current foreground task. */
    fun takeBackTaskFromUser(context: Context): Boolean {
        val snapshot = mutableSnapshots.value
        if (!isShellDisplay(snapshot.displayId)) return false
        val taskId = synchronized(lock) {
            handedOverTaskId.takeIf { it > 0 && handedOverDisplayId == snapshot.displayId }
        } ?: return false
        val taskDisplay = VScreenBackend.displayOfTask(taskId).getOrNull()
        if (taskDisplay != Display.DEFAULT_DISPLAY) return false
        VScreenBackend.touch(snapshot.displayId)
        val focused = VScreenBackend.focusedTask().getOrNull()
        if (!VScreenPolicy.canTakeBack(taskId, taskDisplay, focused?.displayId, focused?.taskId)) return false
        val moved = VScreenBackend.moveTask(taskId, snapshot.displayId).getOrDefault(false) &&
            VScreenBackend.displayOfTask(taskId).getOrNull() == snapshot.displayId
        if (moved) synchronized(lock) {
            if (handedOverTaskId == taskId && handedOverDisplayId == snapshot.displayId) {
                handedOverTaskId = -1
                handedOverDisplayId = Display.INVALID_DISPLAY
                mutableSnapshots.value = mutableSnapshots.value.copy(userControlling = false)
            }
        }
        return moved
    }

    /**
     * 提取虚拟屏最新一帧的完整 Bitmap 副本（供 observe_screen 使用，调用方负责 recycle）。
     */
    fun captureScreenshotBitmap(waitUpToMs: Long = 450L): Bitmap? {
        val deadline = SystemClock.elapsedRealtime() + waitUpToMs.coerceAtLeast(0L)
        while (true) {
            synchronized(lock) {
                val bmp = latestFullBitmap
                if (bmp != null && !bmp.isRecycled) {
                    return runCatching { bmp.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                }
                if (!CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId)) return null
            }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    fun emitTapEffect(x: Int, y: Int, longPress: Boolean = false) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.width <= 0 || current.height <= 0) return
            val nx = (x.toFloat() / current.width).coerceIn(0f, 1f)
            val ny = (y.toFloat() / current.height).coerceIn(0f, 1f)
            val effect = VirtualTouchEffect.Tap(
                sequence = touchSequenceGenerator.incrementAndGet(),
                createdAtMs = SystemClock.uptimeMillis(),
                normalizedX = nx,
                normalizedY = ny,
                longPress = longPress,
            )
            mutableSnapshots.value = current.copy(touchEffect = effect)
        }
    }

    fun emitSwipeEffect(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 420) {
        synchronized(lock) {
            val current = mutableSnapshots.value
            if (!current.active || current.width <= 0 || current.height <= 0) return
            val effect = VirtualTouchEffect.Swipe(
                sequence = touchSequenceGenerator.incrementAndGet(),
                createdAtMs = SystemClock.uptimeMillis(),
                startX = (x1.toFloat() / current.width).coerceIn(0f, 1f),
                startY = (y1.toFloat() / current.height).coerceIn(0f, 1f),
                endX = (x2.toFloat() / current.width).coerceIn(0f, 1f),
                endY = (y2.toFloat() / current.height).coerceIn(0f, 1f),
                durationMs = durationMs.coerceIn(160, 1_500),
            )
            mutableSnapshots.value = current.copy(touchEffect = effect)
        }
    }

    /**
     * 用户在画廊视窗画布上直接触摸时，将归一化坐标注入虚拟屏。
     */
    fun dispatchUserTapOnCanvas(
        normalizedX: Float,
        normalizedY: Float,
        longPress: Boolean = false,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        val snapshot = mutableSnapshots.value
        if (!snapshot.active || !CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)) return
        val x = (normalizedX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y = (normalizedY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        emitTapEffect(x, y, longPress = longPress)
        thread(name = "eta-vd-user-tap", isDaemon = true) {
            if (isShellDisplay(snapshot.displayId)) {
                injectViaVScreen(snapshot.displayId, x, y, longPress)
                return@thread
            }
            val duration = if (longPress) 650L else 55L
            val dispatched = AgentAccessibilityService.current()
                ?.gestureTap(x.toFloat(), y.toFloat(), durationMs = duration, displayId = snapshot.displayId)
                ?.ok == true
            // 免 Root：交回 shell 助手注入（长按用等长的一笔原地滑动）
            val viaShell = !dispatched && injectViaVScreen(
                displayId = snapshot.displayId,
                tapX = x,
                tapY = y,
                longPress = longPress,
            )
            if (!dispatched && !viaShell && rootAvailable()) {
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    val cmd = if (longPress) {
                        "input -d ${snapshot.displayId} swipe $x $y $x $y 650"
                    } else {
                        "input -d ${snapshot.displayId} tap $x $y"
                    }
                    exec.execute(cmd, timeoutMillis = 5_000L)
                } finally {
                    exec.close()
                }
            }
        }
    }

    fun dispatchUserSwipeOnCanvas(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Int = 320,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) {
        val snapshot = mutableSnapshots.value
        if (!snapshot.active || !CanvasRoutingPolicy.isVirtualDisplay(snapshot.displayId)) return
        val x1 = (startX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y1 = (startY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        val x2 = (endX.coerceIn(0f, 1f) * (snapshot.width - 1)).roundToInt()
        val y2 = (endY.coerceIn(0f, 1f) * (snapshot.height - 1)).roundToInt()
        val safeDuration = durationMs.coerceIn(140, 1_000)
        emitSwipeEffect(x1, y1, x2, y2, safeDuration)
        thread(name = "eta-vd-user-swipe", isDaemon = true) {
            if (isShellDisplay(snapshot.displayId)) {
                injectSwipeViaVScreen(snapshot.displayId, x1, y1, x2, y2, safeDuration)
                return@thread
            }
            val dispatched = AgentAccessibilityService.current()
                ?.gestureSwipe(
                    x1.toFloat(),
                    y1.toFloat(),
                    x2.toFloat(),
                    y2.toFloat(),
                    durationMs = safeDuration.toLong(),
                    displayId = snapshot.displayId,
                )?.ok == true
            val viaShell = !dispatched && injectSwipeViaVScreen(
                displayId = snapshot.displayId,
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                durationMs = safeDuration,
            )
            if (!dispatched && !viaShell && rootAvailable()) {
                val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
                try {
                    exec.execute(
                        "input -d ${snapshot.displayId} swipe $x1 $y1 $x2 $y2 $safeDuration",
                        timeoutMillis = 5_000L,
                    )
                } finally {
                    exec.close()
                }
            }
        }
    }

    fun release(
        context: Context? = null,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ) = releaseSession(context, rootAvailable)

    /** Park the daemon display before closing its frame consumer; daemon ownership survives detach. */
    fun detach(context: Context? = appContextOrNull) {
        val detached = synchronized(lock) {
            val token = sessionToken
            val id = shellDisplayId
            sessionState.invalidate(token)
            sessionToken = null
            mutableSnapshots.value = VirtualDisplaySnapshot.Inactive
            highFrequencyPreview = false
            activeDisplayId = Display.DEFAULT_DISPLAY
            shellDisplayId = Display.INVALID_DISPLAY
            handedOverTaskId = -1
            handedOverDisplayId = Display.INVALID_DISPLAY
            val resources = detachResourcesLocked()
            Triple(id, resources, token)
        }
        if (context != null) restoreMutedPackages(context.applicationContext, { RootAccess.isGranted })
        else synchronized(lock) { mutedPackages.clear() }
        val id = detached.first
        if (id != Display.INVALID_DISPLAY) {
            thread(name = "etax-vscreen-park", isDaemon = true) {
                runCatching { VScreenBackend.park(id) }
                detached.second?.close()
            }
        } else detached.second?.let { thread(name = "eta-vd-cleanup", isDaemon = true) { it.close() } }
    }

    private fun releaseSession(
        context: Context?,
        rootAvailable: () -> Boolean,
        expected: VirtualDisplaySessionState.Token? = null,
    ) {
        val detached = synchronized(lock) {
            if (expected != null && !sessionState.isCurrent(expected)) return
            sessionState.invalidate(expected ?: sessionToken)
            sessionToken = null
            activeDisplayId = Display.DEFAULT_DISPLAY
            val shellId = shellDisplayId.also { shellDisplayId = Display.INVALID_DISPLAY }
            highFrequencyPreview = false
            handedOverTaskId = -1
            handedOverDisplayId = Display.INVALID_DISPLAY
            mutableSnapshots.value = VirtualDisplaySnapshot.Inactive
            val muted = mutedPackages.toList().also { mutedPackages.clear() }
            Triple(shellId, detachResourcesLocked(), muted)
        }
        if (context != null) restoreMutedPackages(context.applicationContext, rootAvailable, detached.third)
        if (detached.first != Display.INVALID_DISPLAY) {
            thread(name = "etax-vscreen-release", isDaemon = true) {
                runCatching { VScreenBackend.release(detached.first) }
            }
        }
        detached.second?.let { thread(name = "eta-vd-cleanup", isDaemon = true) { it.close() } }
    }

    private fun detachResourcesLocked(): VirtualDisplaySessionResources? {
        val reader = imageReader
        val worker = frameThread
        val display = inProcessVirtualDisplay
        val bridgeProcess = rootBridgeProcess
        val supervisor = processSupervisor
        val manager = appContextOrNull?.getSystemService(DisplayManager::class.java)
        val listener = displayListener
        if (listener != null) runCatching { manager?.unregisterDisplayListener(listener) }
        imageReader = null
        frameThread = null
        frameHandler?.removeCallbacks(heartbeat)
        frameHandler = null
        inProcessVirtualDisplay = null
        rootBridgeProcess = null
        processSupervisor = null
        displayListener = null
        val bridge = if (bridgeProcess != null && supervisor != null) {
            // Resources owns RootVirtualDisplayBridge, but the current legacy bridge fields are
            // kept for compatibility; close the process on a worker after detaching state.
            thread(name = "eta-vd-bridge-cleanup", isDaemon = true) {
                runCatching { bridgeProcess.outputStream.write("release\n".toByteArray(Charsets.UTF_8)); bridgeProcess.outputStream.flush() }
                supervisor.beginClosing()
                if (bridgeProcess.isAlive) supervisor.terminateAndReap(bridgeProcess) else supervisor.reapProcess(bridgeProcess)
                supervisor.unregisterProcess(bridgeProcess)
            }
            null
        } else null
        latestFullBitmap?.let { if (!it.isRecycled) it.recycle() }
        latestFullBitmap = null
        lastUiFrameAtElapsedMs = 0L
        return VirtualDisplaySessionResources(reader, worker, display, bridge, manager, listener)
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        try {
            val now = SystemClock.elapsedRealtime()
            val minIntervalMs = if (highFrequencyPreview) 30L else 160L
            val shouldUpdateUi = now - lastUiFrameAtElapsedMs >= minIntervalMs || latestFullBitmap == null

            val bitmap = decodeImageToBitmap(image) ?: return
            var oldBitmap: Bitmap? = null
            synchronized(lock) {
                if (reader !== imageReader || !CanvasRoutingPolicy.isVirtualDisplay(activeDisplayId)) {
                    bitmap.recycle()
                    return
                }
                oldBitmap = latestFullBitmap
                latestFullBitmap = bitmap
                if (shouldUpdateUi) {
                    lastUiFrameAtElapsedMs = now
                    val previewBitmap = createUiPreviewBitmap(bitmap)
                    val current = mutableSnapshots.value
                    if (current.active) {
                        mutableSnapshots.value = current.copy(
                            frame = previewBitmap.asImageBitmap(),
                            frameSequence = frameSequenceGenerator.incrementAndGet(),
                        )
                    }
                }
            }
            oldBitmap?.let { old ->
                if (!old.isRecycled) old.recycle()
            }
        } catch (_: Throwable) {
            // 单帧解码异常不影响后续帧流
        } finally {
            runCatching { image.close() }
        }
    }

    private fun decodeImageToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        runCatching {
            image.hardwareBuffer?.use { hardwareBuffer ->
                val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB))
                if (hardwareBitmap != null) {
                    try {
                        return hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        if (!hardwareBitmap.isRecycled) hardwareBitmap.recycle()
                    }
                }
            }
        }
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer ?: return null
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        if (paddedWidth < width) return null

        val safeHeight = if (buffer.remaining() >= paddedWidth * height * 4) height
        else (buffer.remaining() / (paddedWidth * 4)).coerceAtMost(height)
        if (safeHeight <= 0) return null
        val rawBitmap = Bitmap.createBitmap(paddedWidth, safeHeight, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(buffer)
        return if (paddedWidth == width && safeHeight == height) {
            rawBitmap
        } else {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, safeHeight)
            rawBitmap.recycle()
            cropped
        }
    }

    private fun createUiPreviewBitmap(source: Bitmap): Bitmap {
        val maxTargetHeight = if (highFrequencyPreview) 1080 else 480
        if (source.height <= maxTargetHeight) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ratio = maxTargetHeight.toFloat() / source.height.toFloat()
        val targetW = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val targetH = maxTargetHeight
        return source.scale(targetW, targetH)
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun tryCreateInProcessVirtualDisplay(
        appContext: Context,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): VirtualDisplay? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/display/") }
        }
        val dm = appContext.getSystemService(DisplayManager::class.java) ?: return@runCatching null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = VirtualDisplayConfig.Builder(
                CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
            )
                .setSurface(surface)
                .setFlags(CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS)
                .build()
            dm.createVirtualDisplay(config)
        } else {
            dm.createVirtualDisplay(
                CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME,
                width,
                height,
                densityDpi,
                surface,
                CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS,
            )
        }
    }.getOrNull()

    private fun injectViaVScreen(
        displayId: Int, tapX: Int, tapY: Int, longPress: Boolean,
    ): Boolean {
        if (!isShellDisplay(displayId) || !isActiveDisplayValid(displayId)) return false
        VScreenBackend.touch(displayId)
        return if (longPress) {
            VScreenBackend.injectStroke(displayId, tapX, tapY, tapX, tapY, 0, 650).getOrDefault(false)
        } else VScreenBackend.injectTap(displayId, tapX, tapY).getOrDefault(false)
    }

    private fun injectSwipeViaVScreen(
        displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int,
    ): Boolean {
        if (!isShellDisplay(displayId) || !isActiveDisplayValid(displayId)) return false
        VScreenBackend.touch(displayId)
        return if (x1 == x2 && y1 == y2) {
            VScreenBackend.injectStroke(displayId, x1, y1, x2, y2, 0, durationMs).getOrDefault(false)
        } else VScreenBackend.injectStroke(displayId, x1, y1, x2, y2, durationMs).getOrDefault(false)
    }

    private fun tryCreateRootBridgedVirtualDisplay(
        appContext: Context,
        surface: android.view.Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        rootAvailable: () -> Boolean,
    ): Int? {
        val token = VirtualDisplaySurfaceRegistry.register(surface)
        val supervisor = ShellProcessSupervisor(rootAvailable = rootAvailable)
        val apkPath = appContext.applicationInfo.sourceDir
        val cmd = "CLASSPATH=${DeviceToolContract.quote(apkPath)} app_process /system/bin " +
            "io.github.icexxl.etax.agent.virtualdisplay.VirtualDisplayCommandMain"
        val process = supervisor.startShellProcess("root", cmd, mergeStderr = true)
        if (process == null) {
            VirtualDisplaySurfaceRegistry.unregister(token)
            return null
        }

        val readyLatch = CountDownLatch(1)
        var resolvedDisplayId: Int? = null
        val payload = JSONObject()
            .put("action", "hold_display")
            .put("user_id", DeviceToolContract.appUserId(appContext))
            .put("surface_token", token)
            .put("width", width)
            .put("height", height)
            .put("density_dpi", densityDpi)
            .toString() + "\n"

        thread(name = "eta-vd-bridge-stdout", isDaemon = true) {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith(VirtualDisplayCommandMain.READY_PREFIX)) {
                            resolvedDisplayId = line
                                .removePrefix(VirtualDisplayCommandMain.READY_PREFIX)
                                .trim()
                                .toIntOrNull()
                            readyLatch.countDown()
                        } else if (line.startsWith(VirtualDisplayCommandMain.RESULT_PREFIX)) {
                            readyLatch.countDown()
                        }
                    }
                }
            }
            readyLatch.countDown()
        }

        val written = runCatching {
            process.outputStream.write(payload.toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            true
        }.getOrDefault(false)

        if (!written) {
            VirtualDisplaySurfaceRegistry.unregister(token)
            supervisor.beginClosing()
            supervisor.terminateAndReap(process)
            supervisor.unregisterProcess(process)
            return null
        }

        val awaited = runCatching { readyLatch.await(4, TimeUnit.SECONDS) }.getOrDefault(false)
        val id = resolvedDisplayId
        if (!awaited || id == null || !CanvasRoutingPolicy.isVirtualDisplay(id)) {
            VirtualDisplaySurfaceRegistry.unregister(token)
            supervisor.beginClosing()
            if (process.isAlive) supervisor.terminateAndReap(process) else supervisor.reapProcess(process)
            supervisor.unregisterProcess(process)
            return null
        }

        rootBridgeProcess = process
        processSupervisor = supervisor
        return id
    }

    private fun moveVirtualTaskToMainDisplay(
        context: Context,
        displayId: Int,
        packageName: String,
        rootAvailable: () -> Boolean,
    ): Boolean {
        // 1. 尝试进程内通过 system_server Hook 放行的 moveRootTaskToDisplay 迁移
        val movedInProcess = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityTaskManager", "Landroid/app/IActivityTaskManager")
            }
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = atmClass.getMethod("getService").invoke(null) ?: return@runCatching false
            val getTasks = service.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterCount >= 1 }
            val tasks = when (getTasks?.parameterCount) {
                1 -> getTasks.invoke(service, 40)
                3 -> getTasks.invoke(service, 40, false, false)
                4 -> getTasks.invoke(service, 40, false, false, Display.INVALID_DISPLAY)
                else -> null
            } as? List<*> ?: emptyList<Any?>()
            var targetTaskId = -1
            for (item in tasks) {
                val info = item as? android.app.TaskInfo ?: continue
                val taskDisplay = runCatching {
                    android.app.TaskInfo::class.java.getField("displayId").getInt(info)
                }.getOrDefault(Display.INVALID_DISPLAY)
                val topPkg = info.topActivity?.packageName ?: info.baseActivity?.packageName
                if (taskDisplay == displayId || (packageName.isNotEmpty() && topPkg == packageName)) {
                    targetTaskId = info.taskId
                    if (taskDisplay == displayId) break
                }
            }
            if (targetTaskId > 0) {
                val moveMethod = service.javaClass.getMethod(
                    "moveRootTaskToDisplay",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
                moveMethod.invoke(service, targetTaskId, Display.DEFAULT_DISPLAY)
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (movedInProcess) return true

        // 2. Root 特权桥调用 moveRootTaskToDisplay
        if (rootAvailable()) {
            val apkPath = context.applicationInfo.sourceDir
            val cmd = "CLASSPATH=${DeviceToolContract.quote(apkPath)} app_process /system/bin " +
                "io.github.icexxl.etax.agent.virtualdisplay.VirtualDisplayCommandMain"
            val req = JSONObject()
                .put("action", "move_to_main")
                .put("display_id", displayId)
                .put("package_name", packageName)
                .toString() + "\n"
            val supervisor = ShellProcessSupervisor(rootAvailable = rootAvailable)
            val proc = supervisor.startShellProcess("root", cmd, mergeStderr = true)
            if (proc != null) {
                val movedByRoot = try {
                    proc.outputStream.write(req.toByteArray(Charsets.UTF_8))
                    proc.outputStream.flush()
                    proc.outputStream.close()
                    val out = proc.inputStream.bufferedReader().readText()
                    proc.waitFor(4, TimeUnit.SECONDS)
                    out.contains("\"ok\":true")
                } catch (_: Throwable) {
                    false
                } finally {
                    supervisor.beginClosing()
                    if (proc.isAlive) supervisor.terminateAndReap(proc) else supervisor.reapProcess(proc)
                    supervisor.unregisterProcess(proc)
                }
                if (movedByRoot) return true
            }
        }

        // 3. 兜底：以 SINGLE_TOP + NEW_TASK 在 DEFAULT_DISPLAY 唤起该应用
        if (packageName.isNotBlank()) {
            return runCatching {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    ?: return@runCatching false
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                context.startActivity(intent, options.toBundle())
                true
            }.getOrDefault(false)
        }
        return false
    }

    private fun mutePackageAudioFocusIfPossible(
        context: Context,
        packageName: String,
        rootAvailable: () -> Boolean,
    ) {
        if (!rootAvailable() || !DeviceToolContract.validPackageName(packageName)) return
        val added = synchronized(lock) { mutedPackages.add(packageName) }
        if (!added) return
        val userId = DeviceToolContract.appUserId(context)
        thread(name = "eta-vd-audio-shield", isDaemon = true) {
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            try {
                exec.execute(
                    "cmd appops set --user $userId ${DeviceToolContract.quote(packageName)} TAKE_AUDIO_FOCUS ignore",
                    timeoutMillis = 3_000L,
                )
            } catch (_: Throwable) {
                // 忽略部分 ROM appops 差异
            } finally {
                exec.close()
            }
        }
    }

    private fun restoreMutedPackages(
        context: Context,
        rootAvailable: () -> Boolean,
        packages: List<String>? = null,
    ) {
        val toRestore = packages ?: synchronized(lock) {
            val copy = mutedPackages.toList()
            mutedPackages.clear()
            copy
        }
        if (toRestore.isEmpty() || !rootAvailable()) return
        val userId = DeviceToolContract.appUserId(context)
        thread(name = "eta-vd-audio-restore", isDaemon = true) {
            val exec = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            try {
                for (pkg in toRestore) {
                    if (!DeviceToolContract.validPackageName(pkg)) continue
                    exec.execute(
                        "cmd appops set --user $userId ${DeviceToolContract.quote(pkg)} TAKE_AUDIO_FOCUS allow",
                        timeoutMillis = 3_000L,
                    )
                }
            } catch (_: Throwable) {
            } finally {
                exec.close()
            }
        }
    }

    private fun resolvePhysicalDisplayMetrics(context: Context): Triple<Int, Int, Int> {
        val dm = context.resources.displayMetrics
        var width = dm.widthPixels.coerceAtLeast(720)
        var height = dm.heightPixels.coerceAtLeast(1280)
        val density = dm.densityDpi.coerceIn(160, 640)
        runCatching {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val point = Point()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealSize(point)
            if (point.x > 0 && point.y > 0) {
                width = point.x
                height = point.y
            }
        }
        return Triple(width, height, density)
    }
}
