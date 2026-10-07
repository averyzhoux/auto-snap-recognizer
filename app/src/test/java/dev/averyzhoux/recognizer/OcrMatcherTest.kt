package dev.averyzhoux.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 比对引擎的单元测试。跑在电脑上，不需要手机：
 * `./gradlew :app:testDebugUnitTest`
 */
class OcrMatcherTest {

    private val matcher = OcrMatcher(Dataset.entries)

    // ---------- 归一化 ----------

    @Test
    fun `忽略大小写`() {
        assertEquals("serialnumber", TextNormalizer.normalize("SERIAL NUMBER"))
        assertEquals("model", TextNormalizer.normalize("MoDeL"))
    }

    @Test
    fun `忽略全角字符`() {
        assertEquals("abc123", TextNormalizer.normalize("ＡＢＣ１２３"))
    }

    @Test
    fun `忽略空白和标点`() {
        assertEquals("serialnumber", TextNormalizer.normalize("Serial Number:"))
        assertEquals("serialnumber", TextNormalizer.normalize("  serial   number  "))
        assertEquals("sn", TextNormalizer.normalize("S/N"))
    }

    // ---------- 精确匹配 ----------

    @Test
    fun `完全一致算命中`() {
        val result = matcher.matchLine("Serial Number")
        assertTrue(result.isHit)
        assertTrue(result.isExact)
        assertEquals("Serial Number", result.entry?.name)
    }

    @Test
    fun `大小写和冒号不影响精确命中`() {
        val result = matcher.matchLine("SERIAL NUMBER:")
        assertTrue(result.isHit)
        assertTrue(result.isExact)
    }

    // ---------- 别名 ----------

    @Test
    fun `别名可以命中同一项`() {
        val result = matcher.matchLine("Mfg")
        assertTrue(result.isHit)
        assertEquals("Manufacturer", result.entry?.name)
        assertEquals("Mfg", result.matchedOn)
    }

    @Test
    fun `带斜杠的别名也能命中`() {
        val result = matcher.matchLine("S/N")
        assertTrue(result.isHit)
        assertEquals("Serial Number", result.entry?.name)
    }

    // ---------- 包含关系 ----------

    @Test
    fun `表格项出现在识别行里算命中`() {
        // 真实场景：标签行是 "Serial Number: A12345"，表格里只有 "Serial Number"
        val result = matcher.matchLine("Serial Number: A12345")
        assertTrue(result.isHit)
        assertEquals("Serial Number", result.entry?.name)
        assertFalse(result.isExact)
    }

    // ---------- 模糊匹配 ----------

    @Test
    fun `拼写错误仍能命中`() {
        // OCR 把 Manufacturer 认错两个字母
        val result = matcher.matchLine("Manufacterer")
        assertTrue(result.isHit)
        assertEquals("Manufacturer", result.entry?.name)
    }

    @Test
    fun `短词差一个字母不误匹配`() {
        // "Rate" 是 "Rated Voltage" 的子串，但方向反了：表格项更长，不算命中
        val result = matcher.matchLine("Rate")
        assertFalse(result.isHit)
    }

    @Test
    fun `数字被认成字母仍能命中`() {
        // OCR 常把 "Model" 里的 l 认成 1
        val result = matcher.matchLine("Mode1")
        assertTrue(result.isHit)
        assertEquals("Model", result.entry?.name)
    }

    @Test
    fun `短词不参与模糊匹配`() {
        // "Mdel" 只有 4 个字母，低于模糊匹配门槛，不该命中 "Model"
        val result = matcher.matchLine("Mdel")
        assertFalse(result.isHit)
    }

    // ---------- 不命中 ----------

    @Test
    fun `完全不相干的词不命中`() {
        val result = matcher.matchLine("completely unrelated content here")
        assertFalse(result.isHit)
    }

    @Test
    fun `空行不命中`() {
        val result = matcher.matchLine("   ")
        assertFalse(result.isHit)
        assertEquals(0f, result.similarity, 0.001f)
    }

    @Test
    fun `纯符号不命中`() {
        val result = matcher.matchLine("---  ,,  !!")
        assertFalse(result.isHit)
    }

    // ---------- 批量 ----------

    @Test
    fun `批量比对每一行各自给出结果`() {
        val lines = listOf(
            "Serial Number: A12345",
            "Rated Voltage 220V",
            "a completely unrelated sentence",
            "Mfg"
        )
        val matches = matcher.matchAll(lines)

        assertEquals(4, matches.size)
        assertEquals("Serial Number", matches[0].entry?.name)
        assertEquals("Rated Voltage", matches[1].entry?.name)
        assertFalse(matches[2].isHit)
        assertEquals("Manufacturer", matches[3].entry?.name)
    }
}
