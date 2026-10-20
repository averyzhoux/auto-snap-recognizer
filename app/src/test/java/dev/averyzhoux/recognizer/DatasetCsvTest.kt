package dev.averyzhoux.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据集导出的单元测试（纯 JVM，`./gradlew :app:testDebugUnitTest`）。
 *
 * 导出格式是需求指定的：**第一列标记、第二列词条、剩下的都是别名**。
 */
class DatasetCsvTest {

    // ---------- 格式 ----------

    @Test
    fun `第一列标记第二列词条其余是别名`() {
        val text = DatasetCsv.encodeEntriesCsv(
            listOf(Entry("Manufacturer", listOf("Mfg", "Maker"), marked = true))
        )
        val lines = text.trimEnd().split("\r\n")

        assertEquals("标记,词条", lines[0])
        assertEquals("✓,Manufacturer,Mfg,Maker", lines[1])
    }

    @Test
    fun `未标记的行首列留空`() {
        val text = DatasetCsv.encodeEntriesCsv(listOf(Entry("Model", listOf("Model No"))))
        val line = text.trimEnd().split("\r\n")[1]

        assertEquals(",Model,Model No", line)
    }

    @Test
    fun `别名全部导出`() {
        val text = DatasetCsv.encodeEntriesCsv(
            listOf(Entry("Batch Number", listOf("Batch No", "Lot Number", "Lot No")))
        )

        assertTrue(text.contains("Batch No"))
        assertTrue(text.contains("Lot Number"))
        assertTrue(text.contains("Lot No"))
    }

    @Test
    fun `名称含逗号时加引号不拆列`() {
        val text = DatasetCsv.encodeEntriesCsv(listOf(Entry("A,B", marked = true)))
        val line = text.trimEnd().split("\r\n")[1]

        assertEquals("✓,\"A,B\"", line)
    }

    @Test
    fun `行尾是CRLF`() {
        val text = DatasetCsv.encodeEntriesCsv(listOf(Entry("Model")))
        assertTrue(text.contains("\r\n"))
        assertFalse(text.replace("\r\n", "").contains("\n"))
    }

    @Test
    fun `空条目列表只有表头`() {
        assertEquals("标记,词条", DatasetCsv.encodeEntriesCsv(emptyList()).trimEnd())
    }

    // ---------- 往返 ----------

    @Test
    fun `名称别名与标记往返一致`() {
        val entries = listOf(
            Entry("Serial Number", listOf("Serial No", "S/N", "SN"), marked = true),
            Entry("Model", listOf("Model No"), marked = false),
            Entry("Manufacturer", emptyList(), marked = true)
        )

        val parsed = DatasetParser.parse(DatasetCsv.encodeEntriesCsv(entries)).entries

        assertEquals(entries.size, parsed.size)
        entries.forEachIndexed { index, expected ->
            assertEquals(expected.name, parsed[index].name)
            assertEquals(expected.aliases, parsed[index].aliases)
            assertEquals(expected.marked, parsed[index].marked)
        }
    }

    @Test
    fun `表头不会被当成条目`() {
        val parsed = DatasetParser.parse(DatasetCsv.encodeEntriesCsv(listOf(Entry("Model")))).entries

        assertEquals(1, parsed.size)
        assertFalse(parsed.any { it.name == "标记" })
    }

    @Test
    fun `中文名称往返不丢`() {
        val parsed = DatasetParser.parse(
            DatasetCsv.encodeEntriesCsv(listOf(Entry("设备编号", listOf("编号"), marked = true)))
        ).entries

        assertEquals("设备编号", parsed[0].name)
        assertEquals(listOf("编号"), parsed[0].aliases)
        assertTrue(parsed[0].marked)
    }

    @Test
    fun `内置示例数据集导出后完整往返`() {
        val parsed = DatasetParser.parse(DatasetCsv.encodeEntriesCsv(Dataset.entries)).entries

        assertEquals(Dataset.entries.size, parsed.size)
        Dataset.entries.forEachIndexed { index, expected ->
            assertEquals(expected.name, parsed[index].name)
            assertEquals(expected.aliases, parsed[index].aliases)
        }
    }

    // ---------- 文件名 ----------

    @Test
    fun `文件名带时间戳和csv后缀`() {
        val name = DatasetCsv.suggestFileName("我的数据集", nowMillis = 0L)
        assertTrue(name.startsWith("我的数据集-"))
        assertTrue(name.endsWith(".csv"))
    }

    @Test
    fun `文件名里的路径分隔符被替换`() {
        val name = DatasetCsv.suggestFileName("a/b\\c\"d", nowMillis = 0L)
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('"'))
    }

    @Test
    fun `空名称回落到默认名`() {
        assertTrue(DatasetCsv.suggestFileName("   ", nowMillis = 0L).startsWith("数据集-"))
    }
}
