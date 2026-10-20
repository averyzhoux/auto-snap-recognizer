package dev.averyzhoux.recognizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 命中行「别名补显」的单元测试。
 *
 * 规则：靠别名命中时，第二行要在正式名后面补上实际命中的写法（` · Mfg`）；
 * 靠正式名命中、或大小写只是不同的同一个词时，不重复显示。
 */
class MatchedAliasTextTest {

    private fun match(name: String, matchedOn: String?) = LineMatch(
        rawText = "Mfg",
        entry = Entry(name),
        matchedOn = matchedOn,
        similarity = 1f,
        kind = MatchKind.Exact
    )

    @Test
    fun `靠别名命中时补上别名`() {
        assertEquals(" · Mfg", match("Manufacturer", "Mfg").matchedAliasText())
    }

    @Test
    fun `靠正式名命中时为空`() {
        assertEquals("", match("Manufacturer", "Manufacturer").matchedAliasText())
    }

    @Test
    fun `大小写不同不重复显示`() {
        assertEquals("", match("Manufacturer", "manufacturer").matchedAliasText())
    }

    @Test
    fun `未命中时为空`() {
        val miss = LineMatch("something", null, null, 0f, MatchKind.None)
        assertEquals("", miss.matchedAliasText())
    }

    @Test
    fun `matchedOn 为空串时为空`() {
        assertEquals("", match("Manufacturer", "").matchedAliasText())
    }

    @Test
    fun `两侧空白被忽略`() {
        assertEquals(" · Mfg", match(" Manufacturer ", " Mfg ").matchedAliasText())
    }
}
