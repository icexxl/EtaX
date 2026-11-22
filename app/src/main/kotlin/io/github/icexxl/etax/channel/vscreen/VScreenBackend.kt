package io.github.icexxl.etax.channel.vscreen

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.view.Surface
import io.github.icexxl.etax.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Blocking privileged display API. Call from a worker thread. */
internal object VScreenBackend {
    enum class State { NOT_INSTALLED, NOT_RUNNING, NEED_PERMISSION, READY }
    data class Created(val displayId: Int, val flags: Int)
    data class Existing(
        val displayId: Int,
        val name: String?,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val flags: Int,
    )
    data class DisplayTask(val taskId: Int, val component: String?, val visible: Boolean, val activityType: Int, val rootTop: String?) {
        val pkg: String? get() = (rootTop ?: component)?.substringBefore('/')
    }

    private val mutableReadiness = MutableStateFlow(State.NOT_INSTALLED)
    val readiness: StateFlow<State> = mutableReadiness.asStateFlow()
    @Volatile private var app: Application? = null
    @Volatile private var binder: IBinder? = null
    @Volatile private var latch: CountDownLatch? = null
    private val lock = Any()
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener { binder = null; refresh() }
    private val permission = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service?.takeIf { it.pingBinder() }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, VScreenDisplayService::class.java.name))
            .daemon(true).processNameSuffix("vscreen").debuggable(false)
            .version(BuildConfig.VERSION_CODE * 100 + VScreenDisplayService.PROTOCOL)
    }

    @Synchronized fun init(application: Application) {
        if (app != null) return
        app = application
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
        refresh()
    }
    fun refresh() {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        mutableReadiness.value = when {
            alive && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false) -> State.READY
            alive -> State.NEED_PERMISSION
            installedManager() != null -> State.NOT_RUNNING
            else -> State.NOT_INSTALLED
        }
        if (mutableReadiness.value != State.READY) binder = null
    }
    private fun installedManager(): String? = listOf("moe.shizuku.privileged.api", "frb.axeron.manager").firstOrNull {
        runCatching { app?.packageManager?.getPackageInfo(it, 0) != null }.getOrDefault(false)
    }
    fun isReady(): Boolean = readiness.value == State.READY
    fun requestPermission(): Boolean = runCatching {
        if (!Shizuku.pingBinder() || Shizuku.shouldShowRequestPermissionRationale()) false
        else { Shizuku.requestPermission(0xC1A4D); true }
    }.getOrDefault(false)
    fun openManager(context: Context) {
        val intent = installedManager()?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/RikkaApps/Shizuku/releases"))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    private fun service(): IBinder {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        check(isReady()) { "Shizuku not ready" }
        synchronized(lock) {
            binder?.takeIf { it.isBinderAlive }?.let { return it }
            val waiting = CountDownLatch(1); latch = waiting
            Shizuku.bindUserService(args, connection)
            check(waiting.await(10, TimeUnit.SECONDS)) { "Shizuku service connection timed out" }
            return binder ?: error("Shizuku service connection failed")
        }
    }
    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): Result<T> = runCatching {
        check(isReady()) { "Shizuku permission is unavailable" }
        val b = service(); val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR); write(data)
            check(b.transact(code, data, reply, 0)) { "Unsupported service transaction $code" }
            check(reply.readInt() == 0) { reply.readString() ?: "Service error" }
            read(reply)
        } finally { data.recycle(); reply.recycle() }
    }
    fun create(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Result<Created> =
        call(VScreenDisplayService.CREATE, { it.writeString(name); it.writeInt(width); it.writeInt(height); it.writeInt(dpi); it.writeInt(0); surface.writeToParcel(it, 0) }) { Created(it.readInt(), it.readInt()) }
    fun list(): Result<List<Existing>> = call(VScreenDisplayService.LIST, {}) { r ->
        List(r.readInt()) {
            val id = r.readInt()
            val name = r.readString()
            Existing(id, name, r.readInt(), r.readInt(), r.readInt(), r.readInt())
        }
    }
    fun setSurface(displayId: Int, surface: Surface): Result<Unit> = call(VScreenDisplayService.SET_SURFACE, { it.writeInt(displayId); surface.writeToParcel(it, 0) }) { }
    fun park(displayId: Int): Result<Unit> = call(VScreenDisplayService.PARK, { it.writeInt(displayId) }) { }
    fun release(displayId: Int): Result<Unit> = call(VScreenDisplayService.RELEASE, { it.writeInt(displayId) }) { }
    fun touch(displayId: Int) { if (binder?.isBinderAlive == true) call(VScreenDisplayService.TOUCH, { it.writeInt(displayId) }) { } }
    fun injectKey(displayId: Int, keyCode: Int, longPress: Boolean = false): Result<Boolean> = call(VScreenDisplayService.INJECT_KEY, { it.writeInt(displayId); it.writeInt(keyCode); it.writeInt(if (longPress) 1 else 0) }) { it.readInt() != 0 }
    fun injectTap(displayId: Int, x: Int, y: Int): Result<Boolean> = injectStroke(displayId, x, y, x, y, 0, 50)
    fun injectStroke(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int = 0): Result<Boolean> = call(VScreenDisplayService.INJECT_DRAG, {
        it.writeInt(displayId); it.writeInt(x1); it.writeInt(y1); it.writeInt(x2); it.writeInt(y2); it.writeInt(durationMs.coerceIn(0, 5000)); it.writeInt(holdMs.coerceIn(0, 5000))
    }) { it.readInt() != 0 }
    fun listTasks(displayId: Int): Result<List<DisplayTask>> = call(VScreenDisplayService.LIST_TASKS, { it.writeInt(displayId) }) { r ->
        List(r.readInt()) { DisplayTask(r.readInt(), r.readString(), r.readInt() != 0, r.readInt(), r.readString()) }
    }
    fun topTaskId(displayId: Int): Result<Int?> = listTasks(displayId).map { rows ->
        (rows.firstOrNull { it.visible && it.activityType !in 2..5 } ?: rows.firstOrNull { it.visible } ?: rows.firstOrNull())?.taskId
    }
    fun displayOfTask(taskId: Int): Result<Int?> = call(VScreenDisplayService.DISPLAY_OF_TASK, { it.writeInt(taskId) }) { it.readInt().takeIf { id -> id >= 0 } }
    data class FocusedTask(val displayId: Int, val taskId: Int?)
    fun focusedTask(): Result<FocusedTask> = call(VScreenDisplayService.FOCUSED, {}) { r ->
        FocusedTask(r.readInt(), r.readInt().takeIf { id -> id >= 0 })
    }
    fun focusTask(taskId: Int): Result<Boolean> =
        call(VScreenDisplayService.FOCUS_TASK, { it.writeInt(taskId) }) { it.readInt() != 0 }
    fun moveTask(taskId: Int, displayId: Int): Result<Boolean> = call(VScreenDisplayService.MOVE_TASK, { it.writeInt(taskId); it.writeInt(displayId) }) { r ->
        val ok = r.readInt() != 0; val detail = r.readString(); if (!ok) error(detail ?: "Move failed"); true
    }

    /** Launches through an argument-vector command, never a shell interpolation. */
    fun launch(displayId: Int, pkg: String, activity: String? = null, uri: String? = null): Result<Unit> = runCatching {
        require(displayId > 0 && list().getOrThrow().any { it.displayId == displayId }) { "Display is not owned by this service" }
        require(pkg.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Invalid package" }
        val target = when {
            uri != null -> listOf("-a", "android.intent.action.VIEW", "-d", uri, "-p", pkg)
            activity != null -> {
                require('/' !in activity || activity.substringBefore('/') == pkg) { "Component package does not match" }
                listOf("-n", if ('/' in activity) activity else "$pkg/$activity")
            }
            else -> {
                val component = app?.packageManager?.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
                    ?: error("No launcher activity for $pkg")
                listOf("-n", component)
            }
        }
        val argv = arrayOf("start", "-W", "--display", displayId.toString(), "-f", "0x10000000") + target.toTypedArray()
        // Preferred: the daemon already runs as uid 2000, so it can run `am` in-process.
        // A null answer means that daemon predates the transaction, so fall back once.
        val output = tryDaemonLaunch(displayId, argv) ?: runLegacyAmStart(argv)
        check(!output.contains("Error") && !output.contains("Status: error")) {
            "Launch failed: ${output.take(500)}"
        }
        // Verified below: the command's exit status alone does not prove the display routing.
        var verified = false
        repeat(10) {
            if (!verified) {
                Thread.sleep(300)
                val tasks = listTasks(displayId).getOrThrow()
                verified = VScreenPolicy.hasVisiblePackage(tasks, pkg)
            }
        }
        check(verified) { "Launch could not be verified on display $displayId; it may have been redirected" }
    }

    /** Runs `am` inside the Shizuku daemon (uid 2000); null when the daemon predates the transaction. */
    private fun tryDaemonLaunch(displayId: Int, argv: Array<String>): String? = try {
        call(VScreenDisplayService.LAUNCH, { p ->
            p.writeInt(displayId); p.writeInt(argv.size); argv.forEach(p::writeString)
        }) { it.readString().orEmpty() }.getOrThrow()
    } catch (unsupported: IllegalStateException) {
        if (unsupported.message?.contains("Unsupported") == true) null else throw unsupported
    }

    /** Fallback for daemons predating the LAUNCH transaction: Shizuku's hidden process API. */
    private fun runLegacyAmStart(argv: Array<String>): String {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        val process = method.invoke(null, arrayOf("am") + argv, null, null) as Process
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { output.appendLine(it) } } }
        }.apply { isDaemon = true; start() }
        val errors = StringBuilder()
        val err = Thread {
            runCatching { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { errors.appendLine(it) } } }
        }.apply { isDaemon = true; start() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        var exit: Int? = null
        while (exit == null && android.os.SystemClock.elapsedRealtime() < deadline) {
            exit = try { process.exitValue() } catch (_: IllegalThreadStateException) { null } catch (_: IllegalArgumentException) { null }
            if (exit == null) Thread.sleep(50)
        }
        if (exit == null) { process.destroy(); error("Launch timed out") }
        reader.join(1000); err.join(1000)
        return "$output $errors"
    }
}
