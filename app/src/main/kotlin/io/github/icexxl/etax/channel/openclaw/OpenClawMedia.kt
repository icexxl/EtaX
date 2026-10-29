package io.github.icexxl.etax.channel.openclaw

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** OpenClaw 附件的类型判断、下载与打开。 */
internal object OpenClawMedia {

    /** 能用 BitmapFactory 直接解码、可做内联预览的格式。 */
    private val IMAGE_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif",
    )

    private val MIME_BY_EXTENSION = mapOf(
        // 图片
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "heic" to "image/heic", "heif" to "image/heif", "tiff" to "image/tiff",
        "tif" to "image/tiff", "svg" to "image/svg+xml", "ico" to "image/x-icon",
        "avif" to "image/avif", "jfif" to "image/jpeg",
        // 办公文档
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "odt" to "application/vnd.oasis.opendocument.text",
        "ods" to "application/vnd.oasis.opendocument.spreadsheet",
        "odp" to "application/vnd.oasis.opendocument.presentation",
        "rtf" to "application/rtf",
        "pages" to "application/vnd.apple.pages",
        "numbers" to "application/vnd.apple.numbers",
        "key" to "application/vnd.apple.keynote",
        "epub" to "application/epub+zip",
        // 文本 / 结构化
        "txt" to "text/plain", "md" to "text/markdown", "log" to "text/plain",
        "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "json" to "application/json", "xml" to "application/xml",
        "yaml" to "application/yaml", "yml" to "application/yaml",
        "html" to "text/html", "htm" to "text/html",
        "sql" to "text/plain", "sh" to "text/x-shellscript",
        // 压缩包
        "zip" to "application/zip", "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed", "tar" to "application/x-tar",
        "gz" to "application/gzip", "tgz" to "application/gzip",
        "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
        "zst" to "application/zstd", "iso" to "application/x-iso9660-image",
        // 音频
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav",
        "flac" to "audio/flac", "ogg" to "audio/ogg", "opus" to "audio/opus",
        "aac" to "audio/aac", "amr" to "audio/amr",
        // 视频
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
        "m4v" to "video/x-m4v",
        // 其它
        "apk" to "application/vnd.android.package-archive",
    )

    fun displayName(mediaUrl: String?, mediaName: String?): String {
        mediaName?.takeIf { it.isNotBlank() }?.let { return it }
        val clean = mediaUrl.orEmpty().substringBefore('?')
        val tail = clean.substringAfterLast('/').substringAfterLast('\\')
        return tail.ifBlank { "附件" }
    }

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isImage(name: String): Boolean = extensionOf(name) in IMAGE_EXTENSIONS

    fun mimeOf(name: String): String =
        MIME_BY_EXTENSION[extensionOf(name)] ?: "application/octet-stream"

    /**
     * 下载到缓存目录，返回本地文件。
     *
     * 缓存名带 URL 摘要：不同会话里同名的附件不会互相串；
     * 先写 .tmp 再 rename，避免中断留下被当作有效缓存的半截文件。
     */
    suspend fun download(
        context: Context,
        client: OpenClawApi,
        config: OpenClawConfig,
        mediaUrl: String,
        mediaName: String?,
    ): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "openclaw_media").apply { mkdirs() }
        val target = File(dir, cacheFileName(mediaUrl, displayName(mediaUrl, mediaName)))
        if (target.exists() && target.length() > 0) return@withContext Result.success(target)
        trimCache(dir)
        client.fetchMedia(config, mediaUrl).mapCatching { bytes ->
            val tmp = File(dir, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("缓存写入失败")
            }
            target
        }
    }

    /** URL 摘要 + 清洗后的文件名，避免同名不同源互相覆盖。 */
    private fun cacheFileName(mediaUrl: String, name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(mediaUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "$digest-${sanitize(name)}"
    }

    /** 缓存目录上限 200MB，超出按最后修改时间删旧的。 */
    private fun trimCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= MAX_CACHE_BYTES) return@forEach
            total -= file.length()
            file.delete()
        }
    }

    /** 按显示尺寸降采样解码，避免大图 OOM。 */
    fun decodeImage(file: File, maxDimension: Int = 1440): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth > 0 && bounds.outHeight > 0 &&
            (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension)
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
    }.getOrNull()

    /** 交给系统打开；没有对应应用时返回 false。 */
    fun open(context: Context, file: File, mime: String): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private const val MAX_CACHE_BYTES = 200L * 1024 * 1024

    private fun sanitize(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .take(120)
            .takeUnless { it == "." || it == ".." }
            .orEmpty()
            .ifBlank { "attachment" }
}
