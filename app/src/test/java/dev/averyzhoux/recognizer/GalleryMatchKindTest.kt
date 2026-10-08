package dev.averyzhoux.recognizer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 相册 JSON 里「命中种类」的读取规则，跑在电脑上：
 * `./gradlew :app:testDebugUnitTest`
 *
 * 为什么值得单独测：这条规则决定**回看旧照片时显示什么颜色**，而它出错
 * **只会静默变色**——不报错、不崩溃，只能靠肉眼看出来。特别是「包含」那次改动，
 * 如果回落写错，几百张旧照片里的精确命中会集体从绿变黄。
 */
class GalleryMatchKindTest {

    @Test
    fun `没命中一律是 None`() {
        assertEquals(MatchKind.None, parseMatchKind(name = "", hit = false, similarity = 0f))
        // 就算分数高，只要没命中也不算种类
        assertEquals(MatchKind.None, parseMatchKind(name = "Exact", hit = false, similarity = 1f))
    }

    @Test
    fun `新照片直接读 kind 字段`() {
        assertEquals(MatchKind.Exact, parseMatchKind("Exact", true, 1f))
        assertEquals(MatchKind.Contains, parseMatchKind("Contains", true, 0.9f))
        assertEquals(MatchKind.Fuzzy, parseMatchKind("Fuzzy", true, 0.83f))
        // 大小写不敏感
        assertEquals(MatchKind.Contains, parseMatchKind("contains", true, 0.9f))
    }

    /**
     * ★ 旧照片（没有 kind 字段）按**当时的规则**回落。
     *
     * 这是这次改动里最关键的一条：旧 JSON 里没有 kind，不能让它们的颜色变掉。
     */
    @Test
    fun `旧照片按相似度回落_精确仍是绿`() {
        // 旧数据里 508 条精确命中都是 sim=1.0，必须回落成 Exact，否则集体从绿变黄
        assertEquals(MatchKind.Exact, parseMatchKind(name = "", hit = true, similarity = 1f))
        assertEquals(MatchKind.Exact, parseMatchKind(name = "", hit = true, similarity = 0.999f))
    }

    @Test
    fun `旧照片按相似度回落_包含仍按老规则算黄`() {
        // 旧数据里 12 条包含命中是 sim=0.90。旧规则下它们显示黄色，
        // 回落成 Fuzzy 正好保持原样——不会因为「包含改判成绿色」而变绿
        assertEquals(MatchKind.Fuzzy, parseMatchKind(name = "", hit = true, similarity = 0.9f))
        assertEquals(MatchKind.Fuzzy, parseMatchKind(name = "", hit = true, similarity = 0.83f))
        assertEquals(MatchKind.Fuzzy, parseMatchKind(name = "", hit = true, similarity = 0.71f))
    }

    @Test
    fun `无法识别的 kind 字段也走回落_不抛异常`() {
        // 万一以后字段被改坏/被手改，不能让相册整本读不出来
        assertEquals(MatchKind.Exact, parseMatchKind("乱七八糟", true, 1f))
        assertEquals(MatchKind.Fuzzy, parseMatchKind("乱七八糟", true, 0.8f))
    }
}
