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
    /** 内置数据集不可删除（但可以进编辑页改「已识别」标记） */
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
        if (id == BUILT_IN_ID) return builtInEntries()
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
                    aliases = (0 until aliasArray.length()).map { aliasArray.optString(it) },
                    // ★ 兼容旧文件：早期的 entries.json 里没有 marked 字段，
                    //   optBoolean 读不到就回落到 false，老数据自动视作「没标记过」
                    marked = obj.optBoolean("marked", false)
                )
            }.ifEmpty { Dataset.entries }
        } catch (error: Throwable) {
            Log.e(TAG, "read entries of dataset $id failed", error)
            Dataset.entries
        }
    }

    /**
     * 内置示例数据集的条目：代码里的 [Dataset.entries] + 磁盘上存的「已识别」标记。
     *
     * ★ 内置的**条目本身**永远以代码为准，磁盘上只存标记（按名称），
     *   不存整份 entries.json。这样以后在 [Dataset] 里增删示例项时，
     *   老用户不会被一份过期的文件盖住，标记也能跟着名字对上。
     */
    private fun builtInEntries(): List<Entry> {
        val marked = readBuiltInMarks()
        if (marked.isEmpty()) return Dataset.entries
        return Dataset.entries.map { it.copy(marked = it.name in marked) }
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

    /**
     * 覆写某个数据集的条目（编辑页改「已标记」用）。
     *
     * 只动 `<id>.entries.json`，**不碰** `<id>.csv` 原始副本——
     * 原始文件永远保留导入时的样子，以后解析规则升级还能重新解析。
     *
     * 内置数据集（[BUILT_IN_ID]）的条目写死在代码里，这里退化成**只存标记**：
     * 把 `marked == true` 的名字写进 `builtin.marks.json`，条目本身一个都不落盘。
     *
     * `@Synchronized`：编辑页每点一下标记就写一次整份 JSON。人手点击最快也就百来毫秒
     * 一次，而内部存储写几十 KB 只要 1ms 左右，实际上写不会重叠；加锁是为了万一重叠时
     * 不会两个线程交错写出半个文件（半个 JSON 会让整个数据集读不出来）。
     */
    @Synchronized
    fun saveEntries(id: Int, entries: List<Entry>): Boolean {
        if (id == BUILT_IN_ID) return writeBuiltInMarks(entries.filter { it.marked }.map { it.name }.toSet())
        return try {
            entriesFile(id).writeText(encodeEntries(entries).toString(), Charsets.UTF_8)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "save entries of dataset $id failed", error)
            false
        }
    }

    // ---------- 内部实现 ----------

    private data class Index(val activeId: Int, val datasets: List<DatasetMeta>)

    /** 条目列表 → JSON。导入和编辑页共用，保证两边写出来的格式一致。 */
    private fun encodeEntries(entries: List<Entry>): JSONArray {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("name", entry.name)
                    put("aliases", JSONArray(entry.aliases))
                    put("marked", entry.marked)
                }
            )
        }
        return array
    }

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

        // 解析后的条目（刚导入的都没标记）
        entriesFile(id).writeText(encodeEntries(parsed.entries).toString(), Charsets.UTF_8)

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

    /** 内置数据集「已识别」标记的落盘文件；不存在 = 一个都没标。 */
    private fun builtInMarksFile() = File(dir, "builtin.marks.json")

    private fun readBuiltInMarks(): Set<String> = try {
        val file = builtInMarksFile()
        if (!file.exists()) {
            emptySet()
        } else {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.toSet()
        }
    } catch (error: Throwable) {
        Log.e(TAG, "read built-in marks failed", error)
        emptySet()
    }

    private fun writeBuiltInMarks(names: Set<String>): Boolean = try {
        builtInMarksFile().writeText(JSONArray(names.toList()).toString(), Charsets.UTF_8)
        true
    } catch (error: Throwable) {
        Log.e(TAG, "write built-in marks failed", error)
        false
    }

    companion object {
        /** 内置示例数据集的 id，永远存在且不可删除 */
        const val BUILT_IN_ID = 0
        private const val TAG = "Recognizer"
    }
}
