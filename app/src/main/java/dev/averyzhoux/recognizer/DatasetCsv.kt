package dev.averyzhoux.recognizer

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据集导出成 CSV 的**纯函数**部分。
 *
 * 刻意和 [DatasetStore] 分开、和 `android.*` 一行都不沾，这样能在电脑上直接单测
 * （见 `DatasetCsvTest`）。导出格式的约束写在 [encodeEntriesCsv] 上。
 *
 * 导出的内容 = 名称 + 别名 + **「已识别」标记**（行末一列 `marked=true/false`）。
 * 带上标记是为了「导出 → 改 → 导回来」时人工核对状态不丢；旧文件没有这一列时
 * 一律按未标记处理，见 `DatasetParser.parse`。
 */
object DatasetCsv {

    /** 导出文件名里的时间戳格式：`20261009-153012` */
    private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"

    /**
     * 把条目编码成 CSV 文本。
     *
     * 需求指定的形式：**第一列标记、第二列词条、剩下的都是别名**。
     *
     * ```
     * 标记,词条,别名1,别名2
     * ✓,Manufacturer,Mfg,Maker
     * ,Model,Model No
     * ,Warranty
     * ```
     *
     * 规则：
     *
     * 1. **列含义固定**：第 1 列标记、第 2 列词条、第 3 列起全部别名。
     * 2. **已标记写 `✓`，未标记留空**（行首保留逗号，占住标记列的位置）。
     * 3. **别名全部导出**，不再丢失；条目的别名个数不同，所以各行长度可以不同。
     * 4. **字段含逗号、引号或换行时加双引号**，其余情况原样输出。
     * 5. **输出表头 `标记,词条`**，导入端跳过（见 `DatasetParser`）。
     *
     * 行尾用 `\r\n`：Excel 在 Windows 上认这个。
     */
    fun encodeEntriesCsv(entries: List<Entry>): String {
        val builder = StringBuilder()
        builder.append(HEADER_LINE).append("\r\n")

        entries.forEach { entry ->
            val fields = ArrayList<String>(2 + entry.aliases.size)
            // 标记列在最前：未标记时这里是空串，行首那个逗号就是它的占位
            fields += if (entry.marked) MARKED_SYMBOL else ""
            fields += entry.name
            entry.aliases.forEach { fields += it }

            builder.append(fields.joinToString(FIELD_SEPARATOR) { escapeField(it) })
            builder.append("\r\n")
        }
        return builder.toString()
    }

    /** 导出用的字段分隔符：半角逗号（CSV 的 C）。 */
    const val FIELD_SEPARATOR = ","

    /** 表头。导入端按这两个词跳过（见 `DatasetParser`）。 */
    private const val HEADER_LINE = "标记,词条"

    /** 已标记的记号，和 [DatasetParser] 共用。 */
    const val MARKED_SYMBOL = "✓"

    /**
     * 导出用的默认文件名：`数据集名-20261009-153012.csv`。
     *
     * 名称里的路径分隔符和引号换掉——它们会让文件名在各家文件管理器、网盘同步里出问题；
     * 其余字符（中文、空格、`#`）原样保留，用户认得出这是哪份表。
     */
    fun suggestFileName(datasetName: String, nowMillis: Long = System.currentTimeMillis()): String {
        val safe = datasetName
            .replace('/', '_')
            .replace('\\', '_')
            .replace('"', '_')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .ifBlank { "数据集" }
        val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(nowMillis))
        return "$safe-$stamp.csv"
    }

    /**
     * 只在**必要时**加引号并转义内部的引号。
     *
     * 需求要的形式是 `word-a，✓` 这种朴素写法，不能给每个字段都套引号。
     * 需要转义的情况只有三类：字段里含分隔符（全角 / 英文逗号、制表符、分号）、
     * 含双引号、含换行——不转义的话这一行会被解析器切错或断成两行。
     */
    private fun escapeField(field: String): String {
        val needsQuoting = field.any {
            it == '，' || it == ',' || it == '\t' || it == ';' ||
                it == '"' || it == '\n' || it == '\r'
        }
        if (!needsQuoting) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}
