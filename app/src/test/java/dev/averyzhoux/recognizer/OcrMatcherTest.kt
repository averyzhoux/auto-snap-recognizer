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

    /**
     * 「宝马展」真实标签表的一个切片，用来测真实场景的包含 / 模糊命中。
     * 下面的样本全部来自真机相册里的实际识别结果。
     */
    private val tags = OcrMatcher(
        listOf(
            Entry("TH-6983"),
            Entry("TS-2797"),
            Entry("JS3247AB"),
            Entry("JS-4185"),
            Entry("TA-9706AJ8")
        )
    )

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

    /**
     * 「包含」要算**强命中**（显示成绿色），这正是这一项和模糊命中的区别所在。
     *
     * 例子全部来自真实相册数据（宝马展的标签照片）——这些都是「表格项确实在这行里」，
     * 不是「可能是它但认错了字母」。
     */
    @Test
    fun `包含关系算强命中_显示为绿色`() {
        // 前面多认了一个字符
        val extra = tags.matchLine("6TH-6983")
        assertEquals("TH-6983", extra.entry?.name)
        assertEquals(MatchKind.Contains, extra.kind)
        assertTrue("包含要算强命中（绿）", extra.isStrong)
        assertFalse("但不算完全相同", extra.isExact)

        // 带 "No:" 前缀
        assertEquals(MatchKind.Contains, tags.matchLine("NO: TS-2797").kind)
        // 前缀和内容粘在一起
        assertEquals(MatchKind.Contains, tags.matchLine("NoTS 2797").kind)
        // 一行里识别到两个标签，只要包含其中一个就该算强命中
        assertEquals(MatchKind.Contains, tags.matchLine("JS3247AB TA-9706AJ8").kind)
    }

    /** 模糊命中**不算**强命中，仍然是黄色。 */
    @Test
    fun `模糊命中不算强命中_仍为黄色`() {
        // OCR 把 J 认成 Q
        val fuzzy = tags.matchLine("QS-4185")
        assertTrue(fuzzy.isHit)
        assertEquals("JS-4185", fuzzy.entry?.name)
        assertEquals(MatchKind.Fuzzy, fuzzy.kind)
        assertFalse("认错字母不算强命中", fuzzy.isStrong)
    }

    /** 完全相同的种类必须是 [MatchKind.Exact]，否则新规则下会被当成黄色。 */
    @Test
    fun `完全相同的种类是 Exact 且算强命中`() {
        val exact = matcher.matchLine("Serial Number")
        assertEquals(MatchKind.Exact, exact.kind)
        assertTrue(exact.isExact)
        assertTrue(exact.isStrong)

        // 归一化抹掉了连字符和空格的差异，所以这也是完全相同
        assertEquals(MatchKind.Exact, tags.matchLine("TH 6983").kind)
    }

    /** 没命中时种类是 [MatchKind.None]，绝不能算强命中。 */
    @Test
    fun `未命中的种类是 None`() {
        val miss = matcher.matchLine("Ctrl")
        assertFalse(miss.isHit)
        assertEquals(MatchKind.None, miss.kind)
        assertFalse(miss.isStrong)
        assertFalse(miss.isExact)
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
