package io.github.icexxl.etax.channel.shell

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 免 Root 虚拟屏自检：直接经助手建一块屏并等第一帧，把结果写成可读的结论。
 *
 * App 侧建 ImageReader，把 Surface 交给 shell 助手；能建出屏说明特权链路通，
 * 能收到帧说明画面链路也通。任何一步失败都把异常类型与消息带出来。
 */
internal object ShellDisplaySelfCheck {

    data class Result(
        val ok: Boolean,
        val message: String,
        val displayId: Int = Display.INVALID_DISPLAY,
        val gotFrame: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
    )

    suspend fun run(context: Context, width: Int, height: Int, dpi: Int): Result = withContext(Dispatchers.IO) {
        val shell = EtaShellConnection.shell(context)
            ?: return@withContext Result(false, "助手未连接：先完成配对并点「连接助手」")

        val thread = HandlerThread("etax-display-selfcheck").apply { start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val gotFrame = AtomicBoolean(false)
        val firstError = AtomicReference<String?>(null)
        reader.setOnImageAvailableListener({ r ->
            runCatching {
                r.acquireLatestImage()?.close()
                gotFrame.set(true)
            }.onFailure { firstError.compareAndSet(null, "${it.javaClass.simpleName}: ${it.message}") }
        }, Handler(thread.looper))

        var displayId = Display.INVALID_DISPLAY
        try {
            displayId = runCatching { shell.createDisplay(reader.surface, width, height, dpi) }
                .onFailure { return@withContext Result(false, "调用助手建屏抛异常：${describe(it)}") }
                .getOrThrow()

            if (displayId < 0 || displayId == Display.DEFAULT_DISPLAY) {
                return@withContext Result(
                    false,
                    "助手建屏返回 $displayId：系统拒绝了建屏（可能是该 ROM 不允许 shell 建受信任虚拟屏）",
                )
            }

            // 等第一帧：画面能不能出来才是真正的判据
            val deadline = System.currentTimeMillis() + FRAME_WAIT_MS
            while (System.currentTimeMillis() < deadline && !gotFrame.get()) {
                Thread.sleep(100)
            }
            return@withContext Result(
                ok = true,
                message = if (gotFrame.get()) {
                    "建屏成功且已收到画面：display=$displayId ${width}x$height"
                } else {
                    "建屏成功（display=$displayId），但 ${FRAME_WAIT_MS}ms 内没有画面。" +
                        "屏是空的属于正常——没有 App 在上面时本来就没帧；让它跑一次任务再看"
                },
                displayId = displayId,
                gotFrame = gotFrame.get(),
                width = width,
                height = height,
            )
        } catch (t: Throwable) {
            return@withContext Result(false, "自检失败：${describe(t)}")
        } finally {
            if (displayId > 0) runCatching { shell.releaseDisplay(displayId) }
            runCatching { reader.close() }
            runCatching { thread.quitSafely() }
        }
    }

    private fun describe(t: Throwable): String {
        val cause = generateSequence(t) { it.cause }.last()
        return "${cause.javaClass.simpleName}: ${cause.message ?: "无消息"}"
    }

    private const val FRAME_WAIT_MS = 2_500L
}
