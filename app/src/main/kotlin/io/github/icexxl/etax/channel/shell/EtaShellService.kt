package io.github.icexxl.etax.channel.shell

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Binder
import android.os.Bundle
import android.os.Parcel
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import io.github.icexxl.etax.shell.IEtaShell
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 免 Root 虚拟屏的 shell 侧实现（uid 2000）。派生自 Mika 的 ShellService（Apache-2.0），
 * 只保留 EtaX 需要的能力：建屏/释放、把应用启动到指定屏、注入按键与触摸、任务在两块屏之间挪动、
 * 静音、任务结束后的收尾。
 *
 * 所有隐藏接口都用反射调用，失败时退回同等效果的 shell 命令。
 */
internal class EtaShellService(
    /** 只接受这个 uid（EtaX）的调用。 */
    private val allowedUid: Int,
) : IEtaShell.Stub() {

    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()
    // 静音记录放在 shell 身份能写的目录：助手重启后据此把声音还回去
    private val muteLedger = AudioMuteLedger(File("/data/local/tmp", "etax_audio_mute_$allowedUid"))

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (Binder.getCallingUid() != allowedUid) {
            throw SecurityException("Eta shell helper accepts only EtaX UID")
        }
        return super.onTransact(code, data, reply, flags)
    }

    /** 不能用 getSystemService：那样拿到的 DisplayManager 绑的是系统上下文，包名与 shell uid 对不上。 */
    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private val displayManager: android.hardware.display.DisplayManager =
        android.hardware.display.DisplayManager::class.java
            .getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context)

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    private val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "etax-display-awake").apply { isDaemon = true }
        }
        DisplayAwake(::pokeDisplay) { runnable ->
            val future = timer.scheduleWithFixedDelay(runnable, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { future.cancel(false) }
        }
    }

    @get:Suppress("PrivateApi")
    private val userActivity: Pair<Any, java.lang.reflect.Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    // ── 建屏 ────────────────────────────────────────────────────────────

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        // shell 没有 ADD_TRUSTED_DISPLAY 的系统上，去掉可信标志再建一次；
        // 代价是这块屏没有自己的焦点，主屏焦点可能被短暂影响。
        if (t !is SecurityException) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            Log.w(TAG, "createDisplay without trusted flags", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t2: Throwable) {
                Log.e(TAG, "createDisplay failed", t2)
                -1
            }
        }
    }

    @Suppress("WrongConstant")
    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay("etax-agent", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setFullscreenWindowingMode(id)
        setImePolicy(id)
        if (trusted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) awake.start(id)
        return id
    }

    override fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        val vd = synchronized(displays) { displays.remove(displayId) }
        vd?.release()
    }

    override fun releaseAll() {
        restoreMuted()
        val ids = synchronized(displays) { displays.keys.toList() }
        ids.forEach { id ->
            awake.stop(id)
            synchronized(displays) { displays.remove(id) }?.release()
        }
    }

    // ── 启动应用到指定屏 ────────────────────────────────────────────────

    override fun startApp(displayId: Int, packageName: String): Int = try {
        if (!ownDisplay(displayId)) return START_ERROR
        // 用户正在主屏用这个 App 时不要把它抽走
        if (visibleOnMainScreen(packageName)) return START_IN_USE_ON_MAIN
        val intent = launchIntent(packageName) ?: return START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        context.startActivity(intent, options)
        START_OK
    } catch (t: Throwable) {
        Log.e(TAG, "startApp failed", t)
        START_ERROR
    }

    override fun visibleOnMainScreen(packageName: String): Boolean = try {
        val tasks = tasksOn(Display.DEFAULT_DISPLAY)
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        tasks.withIndex().any { (index, task) ->
            packageInTask(task, packageName) && (visibleField?.getBoolean(task) ?: (index == 0))
        }
    } catch (t: Throwable) {
        // 看不清主屏状态时宁可当成在用，不去打扰用户
        Log.w(TAG, "visibleOnMainScreen failed", t)
        true
    }

    // ── 任务查询与挪动 ──────────────────────────────────────────────────

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.w(TAG, "topTaskId failed", t)
        -1
    }

    override fun displayOfTask(taskId: Int): Int = try {
        allTasks().firstOrNull { TASK_INFO.getField("taskId").getInt(it) == taskId }
            ?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.w(TAG, "displayOfTask failed", t)
        -1
    }

    override fun packageOfTask(taskId: Int): String? = try {
        allTasks().firstOrNull { TASK_INFO.getField("taskId").getInt(it) == taskId }?.let { packageOf(it) }
    } catch (t: Throwable) {
        Log.w(TAG, "packageOfTask failed", t)
        null
    }

    override fun moveTask(taskId: Int, displayId: Int): Boolean = try {
        taskManager.javaClass
            .getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
            .invoke(taskManager, taskId, displayId)
        true
    } catch (t: Throwable) {
        // 接口不在时退回等价命令
        Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", t)
        exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
    }

    // ── 注入 ────────────────────────────────────────────────────────────

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            InputDevice.SOURCE_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return sendPair(
            { inject(down, displayId) },
            { inject(up, displayId) },
        ) { exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0 }
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            sendPair({ inject(down, displayId) }, { inject(up, displayId) }) {
                exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n) return false
        val base = SystemClock.uptimeMillis()
        fun eventAt(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply {
                x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f
            }
            return MotionEvent.obtain(
                base, base + times[i], action, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = base + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = eventAt(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n - 1) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    // ── 静音 ────────────────────────────────────────────────────────────

    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = try {
        if (!VALID_PACKAGE.matches(packageName)) {
            false
        } else if (muted) {
            // 原始模式读不出来就不改，避免日后无法准确恢复。
            val prior = muteLedger[packageName]
                ?: audioMode(packageName)?.also { muteLedger.put(packageName, it) }
                ?: return false
            val ok = appops(packageName, "ignore")
            if (!ok && prior != "ignore") muteLedger.remove(packageName)
            ok
        } else {
            val prior = muteLedger[packageName] ?: return true
            val ok = appops(packageName, AudioMuteLedger.restoreMode(prior))
            if (ok) muteLedger.remove(packageName)
            ok
        }
    } catch (t: Throwable) {
        Log.w(TAG, "setAudioMuted failed", t)
        false
    }

    /** 上一次静音中被杀、没来得及恢复的包，启动时先把声音还回去。 */
    fun restoreMuted() {
        runCatching { muteLedger.restoreAll { pkg, mode -> appops(pkg, mode) } }
    }

    private fun audioMode(packageName: String): String? {
        val (code, out) = sh("cmd", "appops", "get", packageName, "PLAY_AUDIO")
        if (code != 0) return null
        return Regex("PLAY_AUDIO:\\s*([a-z]+)").find(out)?.groupValues?.getOrNull(1) ?: "default"
    }

    private fun appops(packageName: String, mode: String): Boolean =
        exec("cmd", "appops", "set", packageName, "PLAY_AUDIO", mode) == 0

    // ── 收尾 ────────────────────────────────────────────────────────────

    override fun finishDisplay(displayId: Int, keep: Array<String>?, close: Array<String>?): Bundle {
        val kept = ArrayList<String>()
        val closed = ArrayList<String>()
        var note: String? = null
        runCatching {
            val tasks = tasksOn(displayId)
            for (task in tasks) {
                val pkg = packageOf(task) ?: continue
                val taskId = TASK_INFO.getField("taskId").getInt(task)
                when {
                    close?.contains(pkg) == true -> {
                        if (removeTask(taskId)) {
                            closed += "$pkg:closed"
                        } else {
                            note = "close failed: $pkg"
                        }
                    }
                    keep?.contains(pkg) == true -> {
                        // 留到主屏但排在最后，用户不会被突然打断
                        if (moveTask(taskId, Display.DEFAULT_DISPLAY)) kept += pkg else note = "move failed: $pkg"
                    }
                }
            }
        }.onFailure {
            Log.w(TAG, "finishDisplay failed", it)
            note = it.message
        }
        releaseDisplay(displayId)
        return Bundle().apply {
            putStringArrayList(KEY_KEPT, kept)
            putStringArrayList(KEY_CLOSED, closed)
            note?.let { putString(KEY_NOTE, it) }
        }
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    // ── 内部工具 ────────────────────────────────────────────────────────

    /** 只往本服务自己建的屏上启动，调用方传错屏号（比如主屏）也不会打扰用户。 */
    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    private fun launchIntent(packageName: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(packageName)

    private fun packageOf(task: Any): String? {
        for (field in listOf("topActivity", "baseActivity")) {
            val name = runCatching { TASK_INFO.getField(field).get(task) as? ComponentName }.getOrNull()
            if (name?.packageName != null) return name.packageName
        }
        return null
    }

    private fun packageInTask(task: Any, packageName: String): Boolean = packageOf(task) == packageName

    private fun removeTask(taskId: Int): Boolean = try {
        taskManager.javaClass.getMethod("removeTask", Int::class.java).invoke(taskManager, taskId) as Boolean
    } catch (error: Throwable) {
        Log.w(TAG, "removeTask failed", error)
        exec("am", "stack", "remove", "$taskId") == 0
    }

    /** IActivityTaskManager.getTasks：shell 持有 REAL_GET_TASKS，能看到所有应用的任务。 */
    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun setFullscreenWindowingMode(displayId: Int) {
        // 有的 ROM 默认把新建虚拟屏上的 App 开成小窗
        exec("wm", "set-display-windowing-mode", "-d", "$displayId", "1")
    }

    /** IWindowManager.setDisplayImePolicy：输入法弹在虚拟屏上，不弹到用户主屏。 */
    @Suppress("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
                .invoke(wm, displayId, IME_POLICY_LOCAL)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    /** API 34 起注入入口在 InputManagerGlobal，更早在 InputManager。 */
    @Suppress("PrivateApi")
    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull()
        ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

    @Suppress("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, INJECT_MODE_ASYNC) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    /** 按下没送达才走 shell 命令兜底；按下成功后就不要再退，否则会多出一次完整点击。 */
    private inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean =
        if (!down()) fallback() else (up() || up())

    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    /** 跑一条命令，返回 (退出码, 输出)；超时返回 -1。输出在单独线程读，避免堵住 binder 线程。 */
    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val output = AtomicReference("")
        val reader = Thread {
            output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault(""))
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(READER_JOIN_MS)
        return p.exitValue() to output.get()
    }

    @Suppress("PrivateApi")
    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed, keep-awake off", t)
            false
        }
    }

    internal companion object {
        const val TAG = "EtaShell"
        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3

        const val KEY_KEPT = "kept"
        const val KEY_CLOSED = "closed"
        const val KEY_NOTE = "note"

        const val MAX_TASKS = 40
        const val TAP_MS = 60L
        const val STROKE_MAX_POINTS = 512
        const val CMD_TIMEOUT_S = 8L
        const val READER_JOIN_MS = 500L

        // WindowManager.DISPLAY_IME_POLICY_LOCAL
        const val IME_POLICY_LOCAL = 0
        // InputManager.INJECT_INPUT_EVENT_MODE_ASYNC
        const val INJECT_MODE_ASYNC = 0

        val VALID_PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        @Suppress("PrivateApi")
        val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_*（部分为隐藏常量）
        const val FLAG_PUBLIC = 1 shl 0
        const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAG_TRUSTED = 1 shl 10
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val FLAG_OWN_FOCUS = 1 shl 14
        const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        /**
         * 建屏标志。trusted=false 时去掉只对可信屏可用的几项：有的系统里 shell 没有
         * ADD_TRUSTED_DISPLAY，带上会被 SecurityException 拒绝。
         */
        @Suppress("WrongConstant")
        fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            // 只有 OWN_FOCUS 时虚拟屏会成为“最上层焦点屏”，主屏的焦点窗口随之清空；
            // STEAL_TOP_FOCUS_DISABLED 让它不抢主屏焦点
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }
    }
}
