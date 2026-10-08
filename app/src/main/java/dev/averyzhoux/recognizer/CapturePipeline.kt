package dev.averyzhoux.recognizer

import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.compose.foundation.layout.size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer

internal const val TAG = "Recognizer"

/** 自动模式下两帧之间的间隔 */
internal const val AUTO_CAPTURE_INTERVAL_MS = 1_000L

/**
 * 连续推帧模式下，两次「存入相册」之间的最小间隔。
 *
 * 自动拍摄是 1 秒一帧，流式更快——画面静止时走像素指纹复用，不跑 ML Kit，
 * 能一直贴着相机帧率出帧。每帧都存的话 [MAX_GALLERY_ITEMS] 张上限几秒钟就满，
 * 磁盘也会一直写（每张约 40KB）。
 *
 * 手动拍摄**不受**这个限制：那是使用者自己按的快门，每张都该留着。
 */
internal const val GALLERY_SAVE_INTERVAL_MS = 3_000L
/**
 * 存下来的预览图宽度。
 *
 * 480px 的 JPEG 约 40KB/张，写在磁盘上（见 [GalleryStore]）；
 * 解码进内存时用 RGB_565，约 600KB/张，且只有最近看过的
 * [THUMBNAIL_CACHE_SIZE] 张会被缓存。
 */
internal const val THUMBNAIL_WIDTH = 480
/**
 * 识别管线：决定「怎么从相机拿帧」和「怎么喂给 ML Kit」。
 *
 * 5 种模式的内存/耗时/精度取舍不同，让使用者按场景自己选（见底部的模式行）。
 * 默认 [SmallCapture]——从 HAL 源头就出小图，日常扫标签够用。
 *
 * [description] 是给设置面板看的一句话说明：进面板第一眼要先知道这条管线是干什么的，
 * 再往下才是能调什么。写完对着模式行念一遍，念不顺就是写太长了。
 */
enum class Pipeline(val label: String, val description: String) {
    /** 原方式：ImageCapture 全分辨率 + toBitmap + fromBitmap */
    Standard(
        "基础",
        "全分辨率拍照再识别。最稳，也最费内存——最初那一版行为"
    ),

    /** A 省内存：ImageCapture 全分辨率，但 ML Kit 直接读相机 YUV（零拷贝） */
    MediaImage(
        "省内存",
        "照样全分辨率取帧，但识别直接读相机原始数据，少拷一份大图"
    ),

    /** B 降采样：先缩到长边 [DOWNSCALE_LONG_EDGE] 再喂 ML Kit */
    Downscaled(
        "降采样",
        "识别前先把图缩小。更快、更省内存，代价是远处的小字可能认不出"
    ),

    /** C 小型图：用 ResolutionSelector 让相机 HAL 直接出小图 */
    SmallCapture(
        "小型图",
        "让相机直接输出 1600×1200 的小图，从源头省内存"
    ),

    /** D 流式：ImageAnalysis + KEEP_ONLY_LATEST，由相机推帧而不是我们定时拍 */
    Analysis(
        "流式",
        "相机连续推帧，这一帧识别完立刻看下一帧。适合一口气扫一堆标签"
    );

    /** 用 ImageAnalysis 驱动（只有 D） */
    val usesAnalysis: Boolean get() = this == Analysis

    /** ML Kit 拿到的是 Bitmap（基础 / 降采样），而不是 MediaImage */
    val usesBitmap: Boolean get() = this == Standard || this == Downscaled

    companion object {
        /** B 降采样：长边目标。2048 是保守值——再小就可能认不出远处的小字 */
        const val DOWNSCALE_LONG_EDGE = 2048

        /** C 小型图：让相机输出的尺寸 */
        val SMALL_CAPTURE_SIZE = Size(1600, 1200)

        /** D 流式：分析流尺寸 */
        val ANALYSIS_SIZE = Size(1920, 1080)
    }
}

/** 相册节流里「还没存过任何一帧」的哨兵指纹 */
internal const val NEVER_SAVED = Long.MIN_VALUE

/** 摄像头拍到的原始帧（已是给 ML Kit 用的方向），bitmap 是缩小过的缩略图。 */
internal data class CapturedFrame(val bitmap: Bitmap, val rotationDegrees: Int)

/** 一次识别 + 比对的结果。 */
internal data class MatchResult(val matches: List<LineMatch>, val fromCache: Boolean)

/** 内存里最多同时缓存几张已解码的预览图（每张约 600KB，12 张约 7MB） */
internal const val THUMBNAIL_CACHE_SIZE = 12
/** 整体流程的状态机。 */
sealed interface OcrStatus {
    data object Idle : OcrStatus

    /** 有一帧正在识别；此时界面上可能还显示着上一帧的结果 */
    data class Recognizing(val bitmap: Bitmap, val cached: List<LineMatch>?) : OcrStatus

    data class Recognized(
        val bitmap: Bitmap,
        val matches: List<LineMatch>,
        /** true = 这一帧跟上一帧几乎一样，直接复用了上次的比对结果 */
        val fromCache: Boolean,
        /**
         * true = OCR 引擎本身出错（区别于「识别成功但图里确实没字」）。
         * 两种情况 matches 都是空的，但给用户的提示必须不同。
         */
        val ocrFailed: Boolean = false
    ) : OcrStatus {
        val hitCount: Int get() = matches.count { it.isHit }
    }

    data class Failed(val message: String) : OcrStatus
}
internal fun OcrStatus.bitmapOrNull(): Bitmap? = when (this) {
    is OcrStatus.Recognizing -> bitmap
    is OcrStatus.Recognized -> bitmap
    else -> null
}

/** 当前该显示哪一批比对结果：识别中会回落到上一帧的缓存结果。 */
internal fun OcrStatus.matchesOrNull(): List<LineMatch>? = when (this) {
    is OcrStatus.Recognizing -> cached
    is OcrStatus.Recognized -> matches
    else -> null
}

/**
 * 给一帧算一个便宜的“像素指纹”，用来判断画面有没有变。
 * 全图取 32x32 缩略图后采样，开销可以忽略。
 */
internal fun CapturedFrame.signature(): Long {
    val small = Bitmap.createScaledBitmap(bitmap, 32, 32, false)
    var hash = 1125899906842597L
    val pixels = IntArray(32 * 32)
    small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
    for (pixel in pixels) {
        // 丢掉低 3 位颜色信息，避免一点点噪点就判定为“变了”
        hash = hash * 31 + (pixel and 0xF8F8F8)
    }
    if (small !== bitmap) small.recycle()
    return hash
}

/** 按长边缩放（降采样管线用）。已经小于目标就原样返回。 */
internal fun Bitmap.scaledToLongEdge(targetLongEdge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= targetLongEdge) return this
    val ratio = targetLongEdge.toFloat() / longest
    val w = (width * ratio).toInt().coerceAtLeast(1)
    val h = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, w, h, true)
}

internal fun Bitmap.scaledToWidth(targetWidth: Int): Bitmap {
    if (width <= targetWidth) return this
    val targetHeight = (height * (targetWidth.toFloat() / width)).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

/**
 * 调 ML Kit 识别 + 跟表格比对。
 *
 * 回调发生在 ML Kit 自己的线程上，但赋值的是 Compose 的 snapshot state，
 * 所以直接写就行，不需要手动切主线程。
 */
internal fun recognize(
    recognizer: TextRecognizer,
    matcher: OcrMatcher,
    inputImage: InputImage,
    onResult: (lines: List<String>, matches: List<LineMatch>, ocrFailed: Boolean) -> Unit
) {
    recognizer.process(inputImage)
        .addOnSuccessListener { visionText ->
            val lines = visionText.textBlocks
                .flatMap { block -> block.lines }
                .map { line -> line.text }
                .filter { it.isNotBlank() }

            val matches = matcher.matchAll(lines)
            val hits = matches.count { it.isHit }

            Log.d(TAG, "recognized ${lines.size} lines, $hits hit the dataset")
            matches.forEach { match ->
                Log.d(
                    TAG,
                    "  [${if (match.isHit) "HIT" else " - "}] " +
                        "'${match.rawText}' -> ${match.entry?.name ?: "-"} " +
                        "(sim=${"%.2f".format(match.similarity)})"
                )
            }

            onResult(lines, matches, false)
        }
        .addOnFailureListener { error ->
            Log.e(TAG, "OCR failed", error)
            onResult(emptyList(), emptyList(), true)
        }
}

/**
 * 把这一张写进磁盘相册，并插到列表最前面。
 *
 * 这个方法是在 [captureExecutor] 的线程上调用的（拍照回调本身就在后台），
 * 所以这里的文件 I/O 不会卡 UI。
 */
internal fun saveToGallery(
    store: GalleryStore,
    gallery: MutableList<GalleryItem>,
    preview: Bitmap,
    lines: List<String>,
    matches: List<LineMatch>
) {
    val item = store.save(
        preview = preview,
        timeMillis = System.currentTimeMillis(),
        lines = lines,
        matches = matches
    ) ?: return

    // 最新的排最前
    gallery.add(0, item)

    // 磁盘上超出上限的已被 store 删掉，内存列表跟着裁一下
    while (gallery.size > MAX_GALLERY_ITEMS) {
        gallery.removeAt(gallery.lastIndex)
    }
}

// ---------- UI ----------
