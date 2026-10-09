package dev.averyzhoux.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 取景页行尾「确认标记」的两段纯逻辑，跑在电脑上：`./gradlew :app:testDebugUnitTest`
 *
 * 为什么值得单独测：这两段都**只会静默出错**——不报错、不崩溃。
 * [toggleEntryMarked] 名字对不上时要是改错了项，或者 [remapEntryMarks] 没把实例换掉，
 * 表现都只是「颜色不对」，肉眼很难发现，也不会有任何日志。
 */
class EntryMarkTest {

    private fun entry(name: String, marked: Boolean = false) = Entry(name = name, marked = marked)

    // ---------- toggleEntryMarked ----------

    @Test
    fun `按名字翻转标记_未标记变已标记`() {
        val entries = listOf(entry("A"), entry("B"))
        val updated = toggleEntryMarked(entries, "B")
        assertEquals(listOf(false, true), updated?.map { it.marked })
    }

    @Test
    fun `按名字翻转标记_已标记变未标记`() {
        val entries = listOf(entry("A", marked = true), entry("B", marked = true))
        val updated = toggleEntryMarked(entries, "A")
        assertEquals(listOf(false, true), updated?.map { it.marked })
    }

    @Test
    fun `名字对不上时返回 null_原列表一个都不动`() {
        // ★ 这是最关键的一条：误点/数据集换了之后名字对不上，必须什么都不做，
        //   绝不能「猜一个最近的」去改，那会静默改错项。
        val entries = listOf(entry("A"), entry("B"))
        assertNull(toggleEntryMarked(entries, "C"))
        assertEquals(listOf(false, false), entries.map { it.marked })
    }

    @Test
    fun `翻转不修改传入的列表_原列表保持不变`() {
        val entries = listOf(entry("A"))
        toggleEntryMarked(entries, "A")
        assertEquals(listOf(false), entries.map { it.marked })
    }

    @Test
    fun `重名时只翻第一个`() {
        val entries = listOf(entry("A"), entry("A"))
        assertEquals(listOf(true, false), toggleEntryMarked(entries, "A")?.map { it.marked })
    }

    // ---------- remapEntryMarks ----------

    private fun match(raw: String, name: String?, marked: Boolean = false) = LineMatch(
        rawText = raw,
        entry = name?.let { Entry(name = it, marked = marked) },
        matchedOn = name,
        similarity = 1f,
        kind = if (name == null) MatchKind.None else MatchKind.Exact
    )

    @Test
    fun `按名字换成新实例_标记跟着数据集走`() {
        val matches = listOf(match("x", "A"), match("y", "B"))
        val remapped = remapEntryMarks(matches, listOf(entry("A", marked = true), entry("B")))
        assertEquals(listOf(true, false), remapped.map { it.entry?.marked })
    }

    @Test
    fun `未命中的行原样保留`() {
        val matches = listOf(match("x", null))
        val remapped = remapEntryMarks(matches, listOf(entry("A", marked = true)))
        assertNull(remapped[0].entry)
        assertEquals("x", remapped[0].rawText)
    }

    @Test
    fun `结果里有数据集查不到的名字时_那一行原样保留`() {
        // 换过数据集 / 用旧表格拍的结果：查不到就保持原样，不能把 entry 抹掉
        val matches = listOf(match("x", "GHOST"))
        val remapped = remapEntryMarks(matches, listOf(entry("A", marked = true)))
        assertEquals("GHOST", remapped[0].entry?.name)
    }

    @Test
    fun `条目列表为空时原样返回同一个实例`() {
        // 数据集还没加载完就点了按钮：不能把结果里的 entry 全丢掉
        val matches = listOf(match("x", "A"))
        assertSame(matches, remapEntryMarks(matches, emptyList()))
    }

    @Test
    fun `标记没变时复用原 LineMatch 实例`() {
        // 内容一样就不必造新对象，省掉一次无谓的重组
        val original = match("x", "A")
        val remapped = remapEntryMarks(listOf(original), listOf(entry("A")))
        assertSame(original, remapped[0])
    }
}
