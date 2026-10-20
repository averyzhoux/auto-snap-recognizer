package dev.averyzhoux.recognizer

import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 解析结果：成功解析出的条目 + 被跳过的行号。 */
data class ParseResult(
    val entries: List<Entry>,
    val skippedLines: List<Int>,
    val totalLines: Int
)

/**
 * 数据集文本的解析与编码探测。
 *
 * 刻意和 [DatasetStore] 分开：这里全是纯函数、不碰 Context，
 * 所以可以在电脑上直接跑单元测试（见 `DatasetParserTest`）。
 */
object DatasetParser {

    /** 一行里可能出现的分隔符：CSV 逗号、TSV 制表符、欧洲 CSV 分号。 */
    private val SEPARATORS = charArrayOf(',', '\t', ';')

    /**
     * 解析文本为条目。
     *
     * 规则（都做了意味着容错，不会因为一行有问题就整体失败）：
     * - 跳过空行
     * - 跳过 `#` 开头的注释行
     * - 一行一个条目；同一行被分隔符切开时，**第一个是名称，其余当别名**
     * - 去掉字段两侧的空白和 CSV 引号
     * - 名称重复的合并别名，不会产生重复条目
     */
    fun parse(text: String): ParseResult {
        val entries = LinkedHashMap<String, EntryDraft>()
        val skipped = mutableListOf<Int>()
        var total = 0

        // 一旦认出表头或第一条「首列是标记」的行，后面全部按新格式解释。
        // ★ 记住整个文件的格式而不是逐行猜：未标记行的首列是空字串，
        //   单看一行和旧格式的「首列是词条」区分不开，靠文件级状态才不会误判。
        var markedFirst = false

        text.lineSequence().forEach { rawLine ->
            total++
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            // ★ 加引号的整段先合并回来，再做切分。
            //   引用段里含分隔符（`"A,B"`、`"Mo;del"`）是按协议写的，不能被当成分隔符切开。
            //   ★ 这里**保留空字段**：新格式未标记的行以逗号开头，首列空串是「标记列为空」
            //     的唯一证据，过滤掉就分不出新旧格式了。
            val raw = mergeQuotedFieldsRaw(line)
            if (raw.all { it.isBlank() }) {
                skipped += total
                return@forEach
            }

            // 表头行：新格式 `标记,词条`、旧格式 `词条,标记`，都跳过
            if (isNewHeaderLine(raw)) {
                markedFirst = true
                return@forEach
            }
            if (isLegacyHeaderLine(raw)) return@forEach

            // ★ 新格式判据：首列是 ✓，或首列为空（未标记）且有第二列。
            //   旧格式的行首列一定是词条本身，不会是空串（空行上面已跳过）。
            val isMarkedFirstLine = markedFirst ||
                (raw.size >= 2 && (raw[0].isBlank() || raw[0].trim() in MARK_SYMBOLS))
            if (isMarkedFirstLine) markedFirst = true

            val (name, aliases, marked) = if (isMarkedFirstLine) {
                Triple(
                    raw.getOrNull(1).orEmpty().trim(),
                    raw.drop(2).map { it.trim() }.filter { it.isNotEmpty() },
                    raw[0].trim() in MARK_SYMBOLS
                )
            } else {
                // 旧格式：首列词条，其余别名；首列之后可能出现空字段，过滤掉
                val fields = raw.map { it.trim() }.filter { it.isNotEmpty() }
                if (fields.isEmpty()) {
                    skipped += total
                    return@forEach
                }
                // 行末的 ✓ / marked=true/false 是标记列，先剥掉
                val (stripped, mark) = stripMarkedField(fields)
                Triple(stripped.firstOrNull().orEmpty(), stripped.drop(1), mark)
            }

            if (name.isEmpty()) {
                skipped += total
                return@forEach
            }

            val existing = entries[name]
            if (existing == null) {
                entries[name] = EntryDraft(aliases.toMutableList(), marked)
            } else {
                aliases.forEach { if (it !in existing.aliases) existing.aliases += it }
                // 同一名称出现多行时，只要有一行标了就算标了：导入方是想恢复「已核对」，
                // 不该因为另一行写着 false 就把它抹掉。
                existing.marked = existing.marked || marked
                skipped += total
            }
        }

        return ParseResult(
            entries = entries.map { (name, draft) -> Entry(name, draft.aliases, draft.marked) },
            skippedLines = skipped,
            totalLines = total
        )
    }

    /**
     * 标记列在旧格式里的键名前缀：`marked=true` / `marked=false`。
     *
     * 现在导出用 `✓`（见 `DatasetCsv.MARKED_SYMBOL`），但上一版导出的文件写的是
     * `marked=true`，这里保留识别，避免那批文件导回来把 `marked=true` 当成别名。
     */
    const val MARKED_FIELD_KEY = "marked"

    /** 各种写法的对勾都认，Excel / 输入法给的可能不是同一个码位。 */
    private val MARK_SYMBOLS = setOf("✓", "✔", "√")

    /**
     * 新格式表头：`标记,词条`（标记列在前）。
     *
     * 只认「首字段是标记名 + 第二个字段是词条名」的组合，避免把真的叫「标记」的
     * 数据集项误跳过。
     */
    private fun isNewHeaderLine(fields: List<String>): Boolean {
        if (fields.size < 2) return false
        val first = fields[0].trim().lowercase()
        val second = fields[1].trim().lowercase()
        return first in HEADER_MARK_WORDS && second in HEADER_NAME_WORDS
    }

    /** 旧格式表头：`词条,标记`（标记列在末尾）。 */
    private fun isLegacyHeaderLine(fields: List<String>): Boolean {
        if (fields.size != 2) return false
        val first = fields[0].trim().lowercase()
        val second = fields[1].trim().lowercase()
        return first in HEADER_NAME_WORDS && second in HEADER_MARK_WORDS
    }

    private val HEADER_NAME_WORDS = setOf("词条", "名称", "name", "word")
    private val HEADER_MARK_WORDS = setOf("标记", "marked", "mark", "已识别")

    /**
     * 剥掉行末的标记字段。
     *
     * 认两种情况：
     * - `✓` / `✔` / `√`：现在导出的形式
     * - `marked=true|false`：上一版导出的形式，保留兼容
     *
     * 其它值一律**原样保留**为别名——宁可多一个别名，也不要把用户真实的数据吃掉。
     * 所以旧的两列文件（`Model,Mfg`）仍然解析成「Model + 别名 Mfg」。
     */
    private fun stripMarkedField(fields: List<String>): Pair<List<String>, Boolean> {
        if (fields.isEmpty()) return fields to false
        val last = fields.last().trim()

        if (last in MARK_SYMBOLS) return fields.dropLast(1) to true

        val prefix = "$MARKED_FIELD_KEY="
        if (!last.startsWith(prefix, ignoreCase = true)) return fields to false

        return when (last.substring(prefix.length).trim()) {
            "true", "TRUE", "True" -> fields.dropLast(1) to true
            "false", "FALSE", "False" -> fields.dropLast(1) to false
            else -> fields to false
        }
    }

    /** 解析过程中的临时条目：别名边读边合并，标记单独记着。 */
    private class EntryDraft(
        val aliases: MutableList<String>,
        var marked: Boolean
    )

    /**
     * 把一行里的字段切开，但**引号内的分隔符不算分隔符**。
     *
     * 和标准 CSV 的区别（刻意保留）：
     * - 引号只认出现在**字段开头**的那种（前面是行首或分隔符）。像 `N.W.` 里的点号不在
     *   引号里，`"S/N"` 这种整段加引号的也照旧。
     * - `""` 不当作转义，遇到引号就翻一次状态。标准转义在这里会让 `"a""b"` 这种
     *   本来只是「名字里有引号」的输入反而拆错，而手写的表格里出现连续两个引号的概率，
     *   比有人按标准 CSV 转义的高得多。
     * - 引号内的逗号 / 制表符 / 分号原样保留，所以 `"A,B"` 是一个字段 `A,B`。
     *
     * 结果仍然**不需要**回退到 [SEPARATORS] 的旧切法：没有引号的行，行为与旧代码逐字一致。
     */
    /**
     * 切分字段并**去掉空字段**（旧格式用）。
     *
     * 旧格式里空字段没有意义（`Manufacturer,,Mfg,` 只是多打了逗号），过滤掉即可。
     * 新格式的标记列可能是空串，所以那边必须用 [mergeQuotedFieldsRaw]。
     */
    private fun mergeQuotedFields(line: String): List<String> =
        mergeQuotedFieldsRaw(line).filter { it.isNotEmpty() }

    /**
     * 切分字段，**保留空字段和位置**（新格式用）。
     *
     * 新格式的第一列是标记，未标记时就是空串。若在这里过滤掉，`，Model` 这种行
     * 会退化成 `Model`，和旧格式「首列是词条」区分不开。
     */
    private fun mergeQuotedFieldsRaw(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        // 当前这个字段的第一个字符是不是引号（决定收尾时要不要再 trim 掉引号）
        var fieldStartedWithQuote = false
        var atFieldStart = true

        line.forEach { ch ->
            when {
                ch == '"' && (inQuotes || atFieldStart) -> {
                    if (atFieldStart && !inQuotes) fieldStartedWithQuote = true
                    inQuotes = !inQuotes
                    current.append(ch)
                }

                inQuotes -> current.append(ch)

                ch in SEPARATORS -> {
                    fields += cleanField(current.toString(), fieldStartedWithQuote)
                    current.setLength(0)
                    fieldStartedWithQuote = false
                    atFieldStart = true
                    return@forEach
                }

                else -> {
                    current.append(ch)
                    atFieldStart = false
                }
            }
        }
        fields += cleanField(current.toString(), fieldStartedWithQuote)
        return fields
    }

    /** 去掉两侧空白；**整段被引号包住**时只去引号、保留里面的空格。 */
    private fun cleanField(raw: String, quoted: Boolean): String {
        val trimmed = raw.trim()
        if (quoted && trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
            return trimmed.substring(1, trimmed.length - 1).trim()
        }
        return trimmed.trim('"').trim()
    }

    /**
     * 探测编码并解码。
     *
     * Excel 在中文 Windows 上导出的 CSV 默认是 **GBK**，直接按 UTF-8 读会全是乱码，
     * 所以按 `UTF-8 BOM → 严格 UTF-8 → GBK` 的顺序试探。
     */
    fun decodeText(bytes: ByteArray): Pair<String, String> {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8) to "UTF-8 (BOM)"
        }

        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString() to "UTF-8"
        } catch (_: CharacterCodingException) {
            // 不是合法 UTF-8，落到 GBK
        }

        return try {
            String(bytes, Charset.forName("GBK")) to "GBK"
        } catch (error: Throwable) {
            Log.e("Recognizer", "GBK decode failed, fallback to UTF-8", error)
            String(bytes, Charsets.UTF_8) to "UTF-8 (替换非法字节)"
        }
    }
}
