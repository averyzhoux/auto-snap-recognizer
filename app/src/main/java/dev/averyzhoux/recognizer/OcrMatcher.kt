package dev.averyzhoux.recognizer

/** 表格里的一行。 */
data class Entry(
    /** 标准名称，例如 "Serial Number" */
    val name: String,
    /** 同一行的其他写法，例如 "S/N"、"SN" */
    val aliases: List<String> = emptyList(),
    /**
     * 用户手动标记的「已识别过」。
     *
     * 这是**纯人工**的状态（在数据集编辑页点出来的），比对引擎完全不看它、
     * 匹配逻辑一行都不受影响。它只影响命中之后**显示成什么颜色**：
     * 标记过的走蓝色，用来区分「这条我刚核对完了」和「这条是自动命中的」。
     */
    val marked: Boolean = false
)

/**
 * 这一行是靠**哪一层**命中表格项的。
 *
 * 之所以要单独记「种类」而不是只看 [LineMatch.similarity]：颜色是个**分类**问题，
 * 用「分数过不过阈值」来表达是隐患——比如「包含」当初只是被赋了 0.9 这个魔法分数，
 * 于是就成了黄色；后来想把包含改判成绿色，就只能去改那个分数（等于让 similarity 撒谎）。
 * 记下种类之后，规则和分数各归各的。
 */
enum class MatchKind {
    /** 归一化后完全一致（含别名、大小写、标点差异） */
    Exact,

    /** 识别行里**包含了整个**表格项，例如 `6TH-6983` 包含 `TH-6983` */
    Contains,

    /** 编辑距离模糊命中，通常是 OCR 认错了字母（`QS-4185` → `JS-4185`） */
    Fuzzy,

    /** 没命中 */
    None
}

/** 某一行 OCR 文本的比对结果。 */
data class LineMatch(
    /** ML Kit 识别出来的原始文本（未经归一化） */
    val rawText: String,
    /** 命中的表格行；没命中为 null */
    val entry: Entry?,
    /** 命中时是靠哪个写法命中的（标准名或某个别名），用于解释“为什么算命中” */
    val matchedOn: String?,
    /** 相似度 0.0~1.0，1.0 表示归一化后完全一致 */
    val similarity: Float,
    /** 靠哪一层命中的；默认 [MatchKind.None] 以兼容旧调用 */
    val kind: MatchKind = MatchKind.None
) {
    val isHit: Boolean get() = entry != null

    /** 归一化后一字不差 */
    val isExact: Boolean get() = kind == MatchKind.Exact

    /**
     * 「强命中」= 完全相同 **或** 包含，用来决定**显示成绿色**。
     *
     * 这两种都表示「表格项确实在这行里」；模糊命中才是「可能是它，但认错了字母」。
     */
    val isStrong: Boolean get() = kind == MatchKind.Exact || kind == MatchKind.Contains
}

/**
 * 把 OCR 识别出的每一行，逐行跟本地表格做比对。
 *
 * 三层策略，从严格到宽松：
 * 1. **完全一致**（归一化之后）→ 相似度 1.0
 * 2. **包含关系**：表格项出现在这一行里，例如识别到 `Serial Number: A123`、表格项是 `Serial Number`
 * 3. **模糊匹配**：编辑距离，容忍 OCR 的字母错误（如 `Manufacterer` -> `Manufacturer`）
 *
 * 短于 [MIN_FUZZY_LENGTH] 的词不参与模糊匹配。英文单词很短，改一个字母往往就是另一个词
 * （`date` / `rate`），所以门槛要比中文严。
 */
class OcrMatcher(
    private val entries: List<Entry>,
    /** 模糊匹配的相似度下限；真正的把关主要靠 [allowedEditDistance] */
    private val fuzzyThreshold: Float = 0.7f
) {

    private data class Candidate(val entry: Entry, val label: String, val normalized: String)

    /** 打分中间结果 */
    private data class Scored(
        val candidate: Candidate,
        val similarity: Float,
        val distance: Int,
        val contained: Boolean
    )

    private val candidates: List<Candidate> = entries.flatMap { entry ->
        (listOf(entry.name) + entry.aliases).mapNotNull { label ->
            val normalized = TextNormalizer.normalize(label)
            if (normalized.isEmpty()) null else Candidate(entry, label, normalized)
        }
    }

    /** 对一整批识别行做比对。 */
    fun matchAll(lines: List<String>): List<LineMatch> = lines.map { matchLine(it) }

    fun matchLine(raw: String): LineMatch {
        val normalized = TextNormalizer.normalize(raw)

        // 空行 / 纯符号行：直接当没命中
        if (normalized.isEmpty()) return LineMatch(raw, null, null, 0f, MatchKind.None)

        // 第 1 层：完全一致
        candidates.firstOrNull { it.normalized == normalized }?.let {
            return LineMatch(raw, it.entry, it.label, 1f, MatchKind.Exact)
        }

        var best: Scored? = null
        for (candidate in candidates) {
            val scored = score(normalized, candidate)
            if (best == null || scored.similarity > best.similarity) best = scored
        }
        val winner = best ?: return LineMatch(raw, null, null, 0f, MatchKind.None)

        // 第 2 层：包含关系。
        //
        // 只认一个方向：**整条识别行里包含了某个表格项**，例如
        //   "Serial Number: A12345" 包含表格项 "Serial Number"  → 命中
        // 反向（表格项包含了整条识别行）不算命中，否则
        //   "Rate" 会因为落在 "Rated Voltage" 里而误命中。
        // 真实的 "Mfg Date" 这种写法靠别名命中，不需要这条规则兜底。
        //
        // 判定宽严**没变**，只是把种类标成 Contains —— 它算「强命中」，显示成绿色。
        val lineIsLonger = normalized.length > winner.candidate.normalized.length
        if (winner.contained && lineIsLonger) {
            return LineMatch(
                raw, winner.candidate.entry, winner.candidate.label,
                winner.similarity, MatchKind.Contains
            )
        }

        // 第 3 层：模糊匹配。
        // 用**绝对编辑距离**定门槛而不是相似度：短词错一个字母相似度就掉到 0.75 以下，
        // 但那种错法恰恰是 OCR 最常见的。所以按长度分级给容忍度。
        val withinLength = normalized.length in MIN_FUZZY_LENGTH..MAX_FUZZY_LENGTH
        val withinDistance = winner.distance <= allowedEditDistance(normalized.length)
        val hit = withinLength && withinDistance && winner.similarity >= fuzzyThreshold

        return if (hit) {
            LineMatch(
                raw, winner.candidate.entry, winner.candidate.label,
                winner.similarity, MatchKind.Fuzzy
            )
        } else {
            LineMatch(raw, null, null, winner.similarity, MatchKind.None)
        }
    }

    /**
     * 给一对文本打分。
     *
     * 先看包含关系（`serialnumberA123` 包含 `serialnumber`），命中直接给 [CONTAINMENT_SCORE]；
     * 否则用编辑距离换算相似度：`1 - 距离 / 较长长度`。
     */
    private fun score(normalizedLine: String, candidate: Candidate): Scored {
        val other = candidate.normalized
        val shorter = if (normalizedLine.length <= other.length) normalizedLine else other
        val longer = if (normalizedLine.length <= other.length) other else normalizedLine

        // 包含关系要求短边够长，否则 2 个字母会命中一堆项
        val contained = shorter.length >= MIN_CONTAINMENT_LENGTH && longer.contains(shorter)

        if (normalizedLine == other) {
            return Scored(candidate, 1f, 0, true)
        }
        if (contained) {
            return Scored(candidate, CONTAINMENT_SCORE, Int.MAX_VALUE, true)
        }

        val distance = levenshtein(normalizedLine, other)
        val longest = maxOf(normalizedLine.length, other.length)
        val similarity = if (longest == 0) 1f else 1f - distance.toFloat() / longest
        return Scored(candidate, similarity, distance, false)
    }

    /**
     * 按长度决定能容忍几个字母的差异。
     * - 5~6 字母：1 个（`date` 这类更短的词不允许，避免 `date`/`rate` 互串）
     * - 7~10 字母：2 个（`Manufacterer` -> `Manufacturer`）
     * - 11 字母以上：3 个
     */
    private fun allowedEditDistance(length: Int): Int = when {
        length <= 6 -> 1
        length <= 10 -> 2
        else -> 3
    }

    /** 标准编辑距离（滚动数组版，O(min(n,m)) 空间）。 */
    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitutionCost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,          // 删除
                    current[j - 1] + 1,       // 插入
                    previous[j - 1] + substitutionCost // 替换
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private companion object {
        /** 包含关系给的分数：比完全一致低，但足够算命中 */
        const val CONTAINMENT_SCORE = 0.9f
        /** 短于这个长度的文本不参与“包含”判定 */
        const val MIN_CONTAINMENT_LENGTH = 3
        /** 短于这个长度的文本不参与模糊匹配（英文短词太容易互串） */
        const val MIN_FUZZY_LENGTH = 5
        /** 长于这个长度的文本通常是整段话，不该整段模糊匹配 */
        const val MAX_FUZZY_LENGTH = 28
    }
}
