package com.example.recognizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 相册磁盘上限：超过就从最早的开始删。
 * 每张 480px JPEG 约 40KB，200 张约 8MB，磁盘完全够用。
 */
const val MAX_GALLERY_ITEMS = 200

/**
 * 内部相册里的一张（**元数据**，不含 Bitmap）。
 *
 * 预览图在磁盘上（[imageFile]），只在真正要显示时解码，
 * 所以内存里不会同时持有整本相册的照片。
 */
data class GalleryItem(
    /** 序号，同时是文件名（000012.jpg / 000012.json） */
    val id: Int,
    val imageFile: File,
    val timeMillis: Long,
    /** 命中的行数 / 识别到的总行数 */
    val hitCount: Int,
    val lineCount: Int,
    /** 识别出的文字（原文，未归一化） */
    val lines: List<String>,
    /** 完整比对结果，点开某张时可以复用 */
    val matches: List<LineMatch>
)

/**
 * 内部相册的磁盘存储。
 *
 * 目录结构（都在 APP 私有目录里，不需要任何存储权限，卸载即清除）：
 * ```
 * filesDir/gallery/
 *     000012.jpg    预览图（480px 宽，JPEG）
 *     000012.json   这一张的识别结果 + 比对结果
 * ```
 *
 * **只存预览图和识别结果，不存全分辨率原图**，所以磁盘占用很小
 * （480px JPEG 约 40KB/张）。
 */
class GalleryStore(context: Context) {

    private val dir: File = File(context.filesDir, "gallery")

    /** 下一个可用 ID；null 表示还没算过 */
    private var nextId: Int? = null

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** 已存了多少张。 */
    fun count(): Int = previewFiles().size

    /**
     * 存一张：先写图片，再写 JSON。
     *
     * 任何一步失败都返回 null（相册里就不出现这张），不抛异常打断拍照流程。
     */
    fun save(
        preview: Bitmap,
        timeMillis: Long,
        lines: List<String>,
        matches: List<LineMatch>
    ): GalleryItem? {
        return try {
            // ID 由存储层分配，保证和磁盘上已有的不冲突
            val index = allocateId()

            val imageFile = imageFile(index)
            FileOutputStream(imageFile).use { out ->
                preview.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            val metaFile = metaFile(index)
            metaFile.writeText(
                buildJson(index, timeMillis, lines, matches).toString(),
                Charsets.UTF_8
            )

            pruneIfNeeded()

            GalleryItem(
                id = index,
                imageFile = imageFile,
                timeMillis = timeMillis,
                hitCount = matches.count { it.isHit },
                lineCount = matches.size,
                lines = lines,
                matches = matches
            )
        } catch (error: Throwable) {
            Log.e(TAG_STORE, "save gallery item failed", error)
            null
        }
    }

    /**
     * 读出磁盘上已有的全部条目，最新的排前面。
     *
     * 只读 JSON（文字），不读图片——图片等真正要显示时再解码。
     */
    fun loadAll(): List<GalleryItem> = previewFiles()
        .mapNotNull { image ->
            val id = image.nameWithoutExtension.toIntOrNull() ?: return@mapNotNull null
            val meta = metaFile(id)
            if (!meta.exists()) return@mapNotNull null
            try {
                parseJson(id, image, meta.readText(Charsets.UTF_8))
            } catch (error: Throwable) {
                Log.e(TAG_STORE, "parse gallery item $id failed", error)
                null
            }
        }
        .sortedByDescending { it.id }

    /**
     * 分配一个磁盘上没用过的 ID。
     *
     * 不能让调用方用一个内存计数器：APP 刚启动、相册还没读完时按下快门，
     * 计数器还是 0，就会把 000001.jpg 覆盖掉，静默丢一张旧照片。
     */
    @Synchronized
    private fun allocateId(): Int {
        val cached = nextId
        if (cached != null) {
            nextId = cached + 1
            return cached
        }
        val maxExisting = previewFiles()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .maxOrNull() ?: 0
        nextId = maxExisting + 2
        return maxExisting + 1
    }

    /** 删掉某一张（图片 + JSON）。 */
    fun delete(item: GalleryItem) {
        item.imageFile.delete()
        metaFile(item.id).delete()
    }

    /** 清空整个相册。 */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    // ---------- 内部实现 ----------

    private fun buildJson(
        index: Int,
        timeMillis: Long,
        lines: List<String>,
        matches: List<LineMatch>
    ): JSONObject = JSONObject().apply {
        put("id", index)
        put("time", timeMillis)
        put("lines", JSONArray(lines))

        val matchArray = JSONArray()
        matches.forEach { match ->
            matchArray.put(
                JSONObject().apply {
                    put("text", match.rawText)
                    // 没命中就不写 entry
                    match.entry?.let { put("entry", it.name) }
                    match.matchedOn?.let { put("matchedOn", it) }
                    put("sim", match.similarity.toDouble())
                    put("hit", match.isHit)
                }
            )
        }
        put("matches", matchArray)
    }

    private fun parseJson(id: Int, image: File, text: String): GalleryItem {
        val json = JSONObject(text)
        val timeMillis = json.optLong("time", image.lastModified())

        val linesArray = json.optJSONArray("lines") ?: JSONArray()
        val lines = (0 until linesArray.length()).map { linesArray.optString(it) }

        val matchArray = json.optJSONArray("matches") ?: JSONArray()
        val matches = (0 until matchArray.length()).map { i ->
            val obj = matchArray.getJSONObject(i)
            val entryName = if (obj.has("entry")) obj.optString("entry") else null
            LineMatch(
                rawText = obj.optString("text"),
                // 别名等信息不需要，展示只用得到名字
                entry = entryName?.let { Entry(name = it) },
                matchedOn = if (obj.has("matchedOn")) obj.optString("matchedOn") else null,
                similarity = obj.optDouble("sim", 0.0).toFloat()
            )
        }

        return GalleryItem(
            id = id,
            imageFile = image,
            timeMillis = timeMillis,
            hitCount = matches.count { it.isHit },
            lineCount = matches.size,
            lines = lines,
            matches = matches
        )
    }

    private fun previewFiles(): List<File> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".jpg") }
            ?.toList()
            .orEmpty()

    private fun imageFile(index: Int): File = File(dir, "%06d.jpg".format(index))

    private fun metaFile(index: Int): File = File(dir, "%06d.json".format(index))

    /** 超过 [MAX_GALLERY_ITEMS] 就从最早的开始删。 */
    private fun pruneIfNeeded() {
        val all = previewFiles().sortedByDescending { it.name }
        if (all.size <= MAX_GALLERY_ITEMS) return

        all.drop(MAX_GALLERY_ITEMS).forEach { old ->
            old.delete()
            old.nameWithoutExtension.toIntOrNull()?.let { metaFile(it).delete() }
        }
    }

    companion object {
        private const val TAG_STORE = "Recognizer"
    }
}

/**
 * 解码预览图。
 *
 * 用 RGB_565（照片没有透明通道），每像素 2 字节而不是 4 字节，内存减半。
 */
fun decodePreview(file: File): Bitmap? = try {
    BitmapFactory.Options().run {
        inPreferredConfig = Bitmap.Config.RGB_565
        BitmapFactory.decodeFile(file.absolutePath, this)
    }
} catch (error: Throwable) {
    Log.e("Recognizer", "decode preview failed: ${file.name}", error)
    null
}
