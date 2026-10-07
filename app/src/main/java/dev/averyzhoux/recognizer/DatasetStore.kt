package dev.averyzhoux.recognizer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一个已导入的数据集的元信息（不含条目本身）。 */
data class DatasetMeta(
    val id: Int,
    val name: String,
    /** 导入时间；内置数据集为 0 */
    val importedAt: Long,
    val entryCount: Int,
    /** 原始文件名，粘贴导入时为 null */
    val sourceFileName: String?,
    /** 内置数据集不可删除 */
    val builtIn: Boolean,
    /** 文本编码，展示用（UTF-8 / GBK） */
    val encoding: String
)

/**
 * 数据集的磁盘存储 + 解析。
 *
 * 目录结构（APP 私有目录，不需要权限）：
 * ```
 * filesDir/datasets/
 *     index.json        所有数据集的元信息 + 当前激活的是哪个
 *     <id>.csv          导入时的**原始文件副本**（原样保留）
 *     <id>.entries.json 解析后的条目，匹配引擎直接读这个
 * ```
 *
 * 保留原始副本是为了以后解析规则升级时能重新解析，不丢用户数据。
 */
class DatasetStore(context: Context) {

    private val dir = File(context.filesDir, "datasets")
    private val indexFile = File(dir, "index.json")

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    // ---------- 读 ----------

    /**
     * 全部数据集：内置的永远排第一（作为兜底），其余按导入时间倒序（新的在前）。
     */
    fun list(): List<DatasetMeta> {
        val imported = readIndex().datasets.sortedByDescending { it.importedAt }
        return listOf(builtInMeta()) + imported
    }

    /** 内置示例数据集的元信息。 */
    fun builtInMeta() = DatasetMeta(
        id = BUILT_IN_ID,
        name = "示例数据集",
        importedAt = 0L,
        entryCount = Dataset.entries.size,
        sourceFileName = null,
        builtIn = true,
        encoding = "内置"
    )

    /** 当前激活的数据集 id；没有就回落到内置数据集。 */
    fun activeId(): Int {
        val index = readIndex()
        val exists = index.datasets.any { it.id == index.activeId }
        return if (exists) index.activeId else BUILT_IN_ID
    }

    /** 读取某个数据集的条目；读不到就回落到内置示例。 */
    fun entriesOf(id: Int): List<Entry> {
        if (id == BUILT_IN_ID) return Dataset.entries
        val file = entriesFile(id)
        if (!file.exists()) return Dataset.entries
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("name")
                if (name.isBlank()) return@mapNotNull null
                val aliasArray = obj.optJSONArray("aliases") ?: JSONArray()
                Entry(
                    name = name,
                    aliases = (0 until aliasArray.length()).map { aliasArray.optString(it) }
                )
            }.ifEmpty { Dataset.entries }
        } catch (error: Throwable) {
            Log.e(TAG, "read entries of dataset $id failed", error)
            Dataset.entries
        }
    }

    // ---------- 写 ----------

    /**
     * 导入一个文件的内容。
     *
     * [bytes] 是原始字节，这里负责探测编码再解析。
     * 成功返回新的元信息，失败返回 null。
     */
    fun importBytes(
        bytes: ByteArray,
        sourceFileName: String?,
        displayName: String
    ): DatasetMeta? {
        val (text, encoding) = DatasetParser.decodeText(bytes)
        val parsed = DatasetParser.parse(text)
        if (parsed.entries.isEmpty()) {
            Log.w(TAG, "import aborted: no entry parsed")
            return null
        }
        return persist(parsed, displayName, sourceFileName, encoding, rawBytes = bytes)
    }

    /** 导入粘贴进来的文本。 */
    fun importText(text: String, displayName: String): DatasetMeta? {
        val parsed = DatasetParser.parse(text)
        if (parsed.entries.isEmpty()) return null
        return persist(
            parsed = parsed,
            displayName = displayName,
            sourceFileName = null,
            encoding = "UTF-8",
            rawBytes = text.toByteArray(Charsets.UTF_8)
        )
    }

    fun setActive(id: Int) {
        val index = readIndex()
        writeIndex(index.copy(activeId = id))
    }

    /** 删除一个数据集（内置的不允许删）。 */
    fun delete(id: Int) {
        if (id == BUILT_IN_ID) return
        csvFile(id).delete()
        entriesFile(id).delete()

        val index = readIndex()
        writeIndex(
            index.copy(
                datasets = index.datasets.filterNot { it.id == id },
                // 正在用的被删了，回落到内置
                activeId = if (index.activeId == id) BUILT_IN_ID else index.activeId
            )
        )
    }

    // ---------- 内部实现 ----------

    private data class Index(val activeId: Int, val datasets: List<DatasetMeta>)

    private fun persist(
        parsed: ParseResult,
        displayName: String,
        sourceFileName: String?,
        encoding: String,
        rawBytes: ByteArray
    ): DatasetMeta? = try {
        val index = readIndex()
        val id = (index.datasets.maxOfOrNull { it.id } ?: BUILT_IN_ID) + 1

        // 原始副本
        csvFile(id).writeBytes(rawBytes)

        // 解析后的条目
        val array = JSONArray()
        parsed.entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("name", entry.name)
                    put("aliases", JSONArray(entry.aliases))
                }
            )
        }
        entriesFile(id).writeText(array.toString(), Charsets.UTF_8)

        val meta = DatasetMeta(
            id = id,
            name = displayName.ifBlank { "数据集 $id" },
            importedAt = System.currentTimeMillis(),
            entryCount = parsed.entries.size,
            sourceFileName = sourceFileName,
            builtIn = false,
            encoding = encoding
        )
        writeIndex(index.copy(datasets = index.datasets + meta, activeId = id))
        Log.d(TAG, "imported dataset #$id '${meta.name}': ${meta.entryCount} entries ($encoding)")
        meta
    } catch (error: Throwable) {
        Log.e(TAG, "persist dataset failed", error)
        null
    }

    private fun readIndex(): Index = try {
        if (!indexFile.exists()) {
            Index(BUILT_IN_ID, emptyList())
        } else {
            val json = JSONObject(indexFile.readText(Charsets.UTF_8))
            val array = json.optJSONArray("datasets") ?: JSONArray()
            val datasets = (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                DatasetMeta(
                    id = obj.optInt("id"),
                    name = obj.optString("name"),
                    importedAt = obj.optLong("importedAt"),
                    entryCount = obj.optInt("entryCount"),
                    sourceFileName = if (obj.has("sourceFileName") && !obj.isNull("sourceFileName")) {
                        obj.optString("sourceFileName")
                    } else {
                        null
                    },
                    builtIn = obj.optBoolean("builtIn", false),
                    encoding = obj.optString("encoding", "UTF-8")
                )
            }
            Index(json.optInt("activeId", BUILT_IN_ID), datasets)
        }
    } catch (error: Throwable) {
        Log.e(TAG, "read dataset index failed", error)
        Index(BUILT_IN_ID, emptyList())
    }

    private fun writeIndex(index: Index) {
        val array = JSONArray()
        index.datasets.forEach { meta ->
            array.put(
                JSONObject().apply {
                    put("id", meta.id)
                    put("name", meta.name)
                    put("importedAt", meta.importedAt)
                    put("entryCount", meta.entryCount)
                    put("sourceFileName", meta.sourceFileName ?: JSONObject.NULL)
                    put("builtIn", meta.builtIn)
                    put("encoding", meta.encoding)
                }
            )
        }
        indexFile.writeText(
            JSONObject().apply {
                put("activeId", index.activeId)
                put("datasets", array)
            }.toString(),
            Charsets.UTF_8
        )
    }

    private fun csvFile(id: Int) = File(dir, "%06d.csv".format(id))

    private fun entriesFile(id: Int) = File(dir, "%06d.entries.json".format(id))

    companion object {
        /** 内置示例数据集的 id，永远存在且不可删除 */
        const val BUILT_IN_ID = 0
        private const val TAG = "Recognizer"
    }
}
