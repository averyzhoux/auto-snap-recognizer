package dev.averyzhoux.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据集解析的单元测试。跑在电脑上，不需要手机：
 * `./gradlew :app:testDebugUnitTest`
 */
class DatasetParserTest {

    // ---------- 基本解析 ----------

    @Test
    fun `一行一个关键词`() {
        val result = DatasetParser.parse(
            """
            Serial Number
            Model
            Manufacturer
            """.trimIndent()
        )

        assertEquals(3, result.entries.size)
        assertEquals("Serial Number", result.entries[0].name)
        assertEquals("Model", result.entries[1].name)
        assertEquals("Manufacturer", result.entries[2].name)
        assertTrue(result.entries.all { it.aliases.isEmpty() })
    }

    @Test
    fun `逗号分隔时第一个是名称其余是别名`() {
        val result = DatasetParser.parse("Manufacturer, Mfg, Maker")

        assertEquals(1, result.entries.size)
        assertEquals("Manufacturer", result.entries[0].name)
        assertEquals(listOf("Mfg", "Maker"), result.entries[0].aliases)
    }

    @Test
    fun `制表符和分号也能当分隔符`() {
        val tab = DatasetParser.parse("Model\tModelNo")
        assertEquals("Model", tab.entries[0].name)
        assertEquals(listOf("ModelNo"), tab.entries[0].aliases)

        val semicolon = DatasetParser.parse("Model;ModelNo")
        assertEquals("Model", semicolon.entries[0].name)
        assertEquals(listOf("ModelNo"), semicolon.entries[0].aliases)
    }

    // ---------- 容错 ----------

    @Test
    fun `空行和注释被跳过`() {
        val result = DatasetParser.parse(
            """
            # 这是注释
            Serial Number

            Model
            """.trimIndent()
        )

        assertEquals(2, result.entries.size)
        assertEquals("Serial Number", result.entries[0].name)
        assertEquals("Model", result.entries[1].name)
        // 注释行和空行不算“被跳过的问题行”
        assertTrue(result.skippedLines.isEmpty())
    }

    @Test
    fun `去掉两侧空白和CSV引号`() {
        val result = DatasetParser.parse("""  "Serial Number"  ,  "S/N"  """)

        assertEquals("Serial Number", result.entries[0].name)
        assertEquals(listOf("S/N"), result.entries[0].aliases)
    }

    @Test
    fun `空字段被忽略`() {
        val result = DatasetParser.parse("Manufacturer,,Mfg,")

        assertEquals("Manufacturer", result.entries[0].name)
        assertEquals(listOf("Mfg"), result.entries[0].aliases)
    }

    @Test
    fun `重复名称合并别名不产生重复条目`() {
        val result = DatasetParser.parse(
            """
            Manufacturer, Mfg
            Manufacturer, Maker
            """.trimIndent()
        )

        assertEquals(1, result.entries.size)
        assertEquals("Manufacturer", result.entries[0].name)
        assertEquals(listOf("Mfg", "Maker"), result.entries[0].aliases)
        // 第二行被记为重复
        assertEquals(listOf(2), result.skippedLines)
    }

    @Test
    fun `重复别名不会重复添加`() {
        val result = DatasetParser.parse(
            """
            Manufacturer, Mfg
            Manufacturer, Mfg
            """.trimIndent()
        )

        assertEquals(listOf("Mfg"), result.entries[0].aliases)
    }

    @Test
    fun `只有分隔符的行算跳过`() {
        val result = DatasetParser.parse("Model\n,,,\nModel No")

        assertEquals(2, result.entries.size)
        assertEquals(listOf(2), result.skippedLines)
    }

    @Test
    fun `空文本解析出零条`() {
        val result = DatasetParser.parse("")
        assertEquals(0, result.entries.size)
        // 空串经 lineSequence() 仍会产生一个空行，这行会被当空白跳过
        assertEquals(1, result.totalLines)
        assertTrue(result.skippedLines.isEmpty())
    }

    // ---------- 编码 ----------

    @Test
    fun `纯ASCII识别为UTF8`() {
        val (text, encoding) = DatasetParser.decodeText("Model".toByteArray(Charsets.UTF_8))
        assertEquals("Model", text)
        assertEquals("UTF-8", encoding)
    }

    @Test
    fun `中文UTF8能正确解码`() {
        val original = "设备编号\n型号规格"
        val (text, encoding) = DatasetParser.decodeText(original.toByteArray(Charsets.UTF_8))
        assertEquals(original, text)
        assertEquals("UTF-8", encoding)
    }

    @Test
    fun `带BOM的UTF8被识别并去掉BOM`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + "Model".toByteArray(Charsets.UTF_8)

        val (text, encoding) = DatasetParser.decodeText(bytes)
        assertEquals("Model", text)
        assertTrue(encoding.startsWith("UTF-8"))
    }

    @Test
    fun `GBK编码的中文能正确解码`() {
        // 这是关键用例：Excel 在中文 Windows 上导出的 CSV 默认就是 GBK
        val original = "设备编号\n型号规格"
        val gbkBytes = original.toByteArray(java.nio.charset.Charset.forName("GBK"))

        val (text, encoding) = DatasetParser.decodeText(gbkBytes)
        assertEquals(original, text)
        assertEquals("GBK", encoding)
    }

    // ---------- 端到端 ----------

    @Test
    fun `GBK文件解析后条目正确`() {
        val csv = "设备编号,编号\n型号规格\n生产日期"
        val bytes = csv.toByteArray(java.nio.charset.Charset.forName("GBK"))

        val (text, _) = DatasetParser.decodeText(bytes)
        val parsed = DatasetParser.parse(text)

        assertEquals(3, parsed.entries.size)
        assertEquals("设备编号", parsed.entries[0].name)
        assertEquals(listOf("编号"), parsed.entries[0].aliases)
        assertEquals("生产日期", parsed.entries[2].name)
    }

    @Test
    fun `解析出的条目能被匹配引擎直接用`() {
        val parsed = DatasetParser.parse("Manufacturer, Mfg\nModel")
        val matcher = OcrMatcher(parsed.entries)

        assertTrue(matcher.matchLine("Mfg").isHit)
        assertEquals("Manufacturer", matcher.matchLine("Mfg").entry?.name)
        assertTrue(matcher.matchLine("Model").isHit)
    }
}
