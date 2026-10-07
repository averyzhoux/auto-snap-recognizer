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
        val entries = LinkedHashMap<String, MutableList<String>>()
        val skipped = mutableListOf<Int>()
        var total = 0

        text.lineSequence().forEach { rawLine ->
            total++
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val fields = line.split(*SEPARATORS)
                .map { it.trim().trim('"').trim() }
                .filter { it.isNotEmpty() }

            if (fields.isEmpty()) {
                skipped += total
                return@forEach
            }

            val name = fields.first()
            val aliases = fields.drop(1)
            val existing = entries[name]
            if (existing == null) {
                entries[name] = aliases.toMutableList()
            } else {
                aliases.forEach { if (it !in existing) existing += it }
                skipped += total
            }
        }

        return ParseResult(
            entries = entries.map { (name, aliases) -> Entry(name, aliases) },
            skippedLines = skipped,
            totalLines = total
        )
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
