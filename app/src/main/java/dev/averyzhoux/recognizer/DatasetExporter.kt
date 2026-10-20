package dev.averyzhoux.recognizer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/**
 * 把数据集 CSV 直接写进公共「下载」目录下的固定子目录。
 *
 * 为什么不用系统「另存为」选择器：`CreateDocument` 只能给定**文件名**，给不了初始目录
 * （`EXTRA_INITIAL_URI` 只是建议，多数文件管理器会忽略），所以「默认导出到
 * `Download/shijianxiangji`」只能自己写。
 *
 * 为什么用 MediaStore 而不是 `File("/sdcard/Download/...")`：
 * Android 10（API 29）起 Scoped Storage 禁止直接按路径写公共目录，
 * `MediaStore.Downloads` + `RELATIVE_PATH` 是官方通道，**不需要任何权限**；
 * 目录不存在时由系统自动创建。
 */
internal object DatasetExporter {

    /**
     * 导出子目录名（相对公共「下载」目录）。
     *
     * 用 **App 的中文名**「识鉴相机」，和桌面图标下的名字一致，用户在文件管理器里
     * 一眼能对上。中文目录名对 MediaStore 没有任何影响。
     * （旧版曾用拼音 `shijianxiangji`，已按需求改掉。）
     */
    const val SUB_DIR = "识鉴相机"

    private const val MIME_CSV = "text/csv"

    /** 给人看的落点，出错提示里用。 */
    val humanPath: String get() = "下载/$SUB_DIR/"

    /**
     * 能不能直接写。
     *
     * API 29 以下没有 `MediaStore.Downloads` 分卷，按路径写又需要存储权限，
     * 所以那些版本退回系统选择器（见 `MainActivity.startExport`）。
     */
    val canWriteDirectly: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** 一次导出成功的结果。 */
    data class Result(val uri: Uri, val displayName: String)

    /**
     * 写入。**阻塞调用**，必须在 IO 线程上执行。
     *
     * 失败抛异常，由调用方决定怎么提示；中途失败会删掉半截的 pending 条目，
     * 避免「下载」里留一个 0 字节的坏文件。
     */
    fun export(context: Context, fileName: String, csv: String): Result {
        check(canWriteDirectly) { "API ${Build.VERSION.SDK_INT} 不支持直接写入下载目录" }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR"

        // ★ 先算一个没被占用的名字：MediaStore 的同名行为在各家 ROM 上不一致
        //   （有的自动加 (1)，有的直接建两个同名条目），自己算一遍最稳。
        val displayName = uniqueDisplayName(resolver, collection, relativePath, fileName)

        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_CSV)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            // ★ IS_PENDING=1：写入期间这个条目对其他应用不可见，
            //   不然文件管理器/相册可能先看到一个半截文件。
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, pending) ?: error("insert 返回 null")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(csv.toByteArray(Charsets.UTF_8))
            } ?: error("openOutputStream 返回 null")

            // 写完才放出来；IS_PENDING 置 0 是让条目真正可见的那一步
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (error: Throwable) {
            // 失败就删掉 pending 条目，别在「下载」里留空壳
            runCatching { resolver.delete(uri, null, null) }
                .onFailure { Log.w(TAG, "清理未完成的导出条目失败", it) }
            throw error
        }

        Log.d(TAG, "exported '$displayName' to $relativePath")
        return Result(uri, displayName)
    }

    /**
     * 在目标目录里挑一个没被用过的文件名：`a.csv` → `a (2).csv` → `a (3).csv`。
     *
     * 查询失败（个别 ROM 不认 RELATIVE_PATH 条件）时直接返回原名，让 MediaStore 自己处理，
     * 不因为查重而让整个导出失败。
     */
    private fun uniqueDisplayName(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        desired: String
    ): String {
        val existing = runCatching {
            val names = mutableSetOf<String>()
            // ★ 查询条件要同时试带尾斜杠和不带的两种：RELATIVE_PATH 写入时是
            //   `Download/shijianxiangji`，但系统存下来通常带尾斜杠。
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)",
                arrayOf(relativePath, "$relativePath/"),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let { names += it }
                }
            }
            names
        }.getOrElse {
            Log.w(TAG, "查询导出目录失败，跳过重名检查", it)
            emptySet()
        }

        if (desired !in existing) return desired

        val base = desired.substringBeforeLast('.', desired)
        val extension = desired.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = if (extension.isEmpty()) "$base ($index)" else "$base ($index).$extension"
            if (candidate !in existing) return candidate
            index++
        }
    }

    private const val TAG = "Recognizer"
}
