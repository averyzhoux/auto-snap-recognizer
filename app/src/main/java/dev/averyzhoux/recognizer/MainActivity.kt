package dev.averyzhoux.recognizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.averyzhoux.recognizer.ui.theme.RecognizerTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.Date
import java.io.File
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Recognizer"

/** 自动模式下两帧之间的间隔 */
private const val AUTO_CAPTURE_INTERVAL_MS = 1_000L

/**
 * 连续推帧模式下，两次「存入相册」之间的最小间隔。
 *
 * 自动拍摄是 1 秒一帧，流式分析更快——画面静止时走像素指纹复用，不跑 ML Kit，
 * 能一直贴着相机帧率出帧。每帧都存的话 [MAX_GALLERY_ITEMS] 张上限几秒钟就满，
 * 磁盘也会一直写（每张约 40KB）。
 *
 * 手动拍摄**不受**这个限制：那是使用者自己按的快门，每张都该留着。
 */
private const val GALLERY_SAVE_INTERVAL_MS = 3_000L

/** 快门按钮的直径 */
private val SHUTTER_SIZE = 76.dp

/** 缩略图槽位尺寸，左右各留一个保证快门在视觉上居中 */
private val THUMBNAIL_SLOT = 56.dp

/** 小米相机选中态的那个黄：也用于「近似命中」 */
private val XiaomiYellow = Color(0xFFFFC800)

/** 「完全相同命中」用的绿。刻意和黄色区分开，一眼能看出这个命中是否精确 */
private val MatchGreen = Color(0xFF3DDC84)

/**
 * 命中项该用什么颜色：
 * - 完全相同（归一化后一致）→ 绿色
 * - 近似（包含 / 模糊 / 别名）→ 黄色
 * - 没命中 → null，由调用方决定灰显
 */
private fun matchColor(match: LineMatch): Color? = when {
    !match.isHit -> null
    match.isExact -> MatchGreen
    else -> XiaomiYellow
}

/**
 * 存下来的预览图宽度。
 *
 * 480px 的 JPEG 约 40KB/张，写在磁盘上（见 [GalleryStore]）；
 * 解码进内存时用 RGB_565，约 600KB/张，且只有最近看过的
 * [THUMBNAIL_CACHE_SIZE] 张会被缓存。
 */
private const val THUMBNAIL_WIDTH = 480

/**
 * 识别管线：决定「怎么从相机拿帧」和「怎么喂给 ML Kit」。
 *
 * 5 种模式的内存/耗时/精度取舍不同，让使用者按场景自己选（见底部的模式行）。
 * 默认 [Standard]——也就是最初那一版行为，保持兼容。
 */
enum class Pipeline(val label: String) {
    /** 原方式：ImageCapture 全分辨率 + toBitmap + fromBitmap */
    Standard("标准"),

    /** A 省内存：ImageCapture 全分辨率，但 ML Kit 直接读相机 YUV（零拷贝） */
    MediaImage("省内存"),

    /** B 降采样：先缩到长边 [DOWNSCALE_LONG_EDGE] 再喂 ML Kit */
    Downscaled("降采样"),

    /** C 小图直出：用 ResolutionSelector 让相机 HAL 直接出小图 */
    SmallCapture("小图直出"),

    /** D 流式分析：ImageAnalysis + KEEP_ONLY_LATEST，由相机推帧而不是我们定时拍 */
    Analysis("流式分析");

    /** 用 ImageAnalysis 驱动（只有 D） */
    val usesAnalysis: Boolean get() = this == Analysis

    /** ML Kit 拿到的是 Bitmap（标准 / 降采样），而不是 MediaImage */
    val usesBitmap: Boolean get() = this == Standard || this == Downscaled

    companion object {
        /** B 降采样：长边目标。2048 是保守值——再小就可能认不出远处的小字 */
        const val DOWNSCALE_LONG_EDGE = 2048

        /** C 小图直出：让相机输出的尺寸 */
        val SMALL_CAPTURE_SIZE = Size(1600, 1200)

        /** D 流式分析：分析流尺寸 */
        val ANALYSIS_SIZE = Size(1920, 1080)
    }
}

/** 相册节流里「还没存过任何一帧」的哨兵指纹 */
private const val NEVER_SAVED = Long.MIN_VALUE

/** 摄像头拍到的原始帧（已是给 ML Kit 用的方向），bitmap 是缩小过的缩略图。 */
private data class CapturedFrame(val bitmap: Bitmap, val rotationDegrees: Int)

/** 一次识别 + 比对的结果。 */
private data class MatchResult(val matches: List<LineMatch>, val fromCache: Boolean)

/** 内存里最多同时缓存几张已解码的预览图（每张约 600KB，12 张约 7MB） */
private const val THUMBNAIL_CACHE_SIZE = 12

/** 当前显示哪一屏 */
private enum class Screen { Camera, Gallery, Detail, Datasets }

/**
 * 一次待确认的导入。
 *
 * 解析先做一遍用于**预览**（让用户当场看出格式对不对），确认后才落盘。
 * 保留原始字节/文本，落盘时由 [DatasetStore] 再解析一遍——同一个解析器，
 * 所以预览和最终存下来的内容一致。
 */
private class PendingImport(
    val bytes: ByteArray?,
    val text: String?,
    val sourceFileName: String?,
    val preview: ParseResult,
    val encoding: String,
    val suggestedName: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏保留；底部导航栏做成透明，内容延伸到手势条后面
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        // 相机类应用：只要这个页面在前台就别让屏幕熄灭
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            RecognizerTheme {
                CameraOcrScreen()
            }
        }
    }
}

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

/**
 * 相机页面：权限申请 -> 预览 -> 拍照 -> 英文 OCR -> 跟表格比对。
 *
 * 支持两种拍摄模式：
 * - **自动**（默认）：每 [AUTO_CAPTURE_INTERVAL_MS] 毫秒抓一帧，连续识别
 * - **手动**：点按钮才抓一帧
 */
@Composable
fun CameraOcrScreen() {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // 用户点过“拒绝”后，再申请就不会弹系统框了，需要引导去设置页
    var askAgain by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) askAgain = false
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        PermissionRationale(
            askAgain = askAgain,
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = { context.openAppSettings() }
        )
        return
    }

    // 拍照回调所在的线程：用一个后台单线程，避免阻塞 UI
    val captureExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    // ML Kit 识别器是重对象，只建一次，页面销毁时必须 close（否则泄漏原生资源）
    val textRecognizer: TextRecognizer = remember {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    // 匹配引擎放进一个 holder：切换数据集时替换 .value，
    // 这样**正在跑的自动循环**捕获的是 holder 而不是旧的 matcher 实例，
    // 下一次拍照立刻就用上新表格（否则要等循环重启才生效）。
    val matcherHolder = remember { mutableStateOf(OcrMatcher(Dataset.entries)) }
    DisposableEffect(Unit) {
        onDispose {
            textRecognizer.close()
            captureExecutor.shutdown()
        }
    }

    // 只在相机绑定成功后才非空
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var status by remember { mutableStateOf<OcrStatus>(OcrStatus.Idle) }

    // 默认手动：点一下快门才拍一帧
    var autoMode by remember { mutableStateOf(false) }

    // 同一时刻只允许一帧在识别；1 秒一帧时识别可能还没结束，靠它跳过这一拍
    val busy = remember { AtomicBoolean(false) }
    // 上一帧的“像素指纹”，用于判断画面是否变化
    var lastSignature by remember { mutableStateOf<Long?>(null) }
    var lastMatches by remember { mutableStateOf<List<LineMatch>?>(null) }

    // 相机还没绑定完成就拍照时会报错，记下时间让自动循环稍后重试。
    // 手动模式下没有循环接应，所以这种情况直接忽略，不弹错误。
    var transientErrorAt by remember { mutableStateOf<Long?>(null) }

    // ---------- 数据集 ----------
    val datasetStore = remember { DatasetStore(context.applicationContext) }
    val datasets = remember { mutableStateListOf<DatasetMeta>() }
    var activeDataset by remember { mutableStateOf<DatasetMeta?>(null) }
    // 从文件选择器/粘贴拿到的待确认导入
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var pasteDialogOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 内部相册：元数据在内存、图片在磁盘
    val galleryStore = remember { GalleryStore(context.applicationContext) }
    // 已解码预览图的小缓存，只留最近看过的几张，避免整本相册都占内存。
    //
    // 刻意**不**在 entryRemoved 里 recycle：被挤出缓存的位图可能还被某个正在
    // 合成（或预取）的网格卡片持有，回收了它再绘制会抛
    // “trying to use a recycled bitmap”。交给 GC 释放即可。
    val thumbnailCache = remember { LruCache<String, Bitmap>(THUMBNAIL_CACHE_SIZE) }
    val gallery = remember { mutableStateListOf<GalleryItem>() }
    var screen by remember { mutableStateOf(Screen.Camera) }
    // 相册里正在看的那张（null = 没在看详情）
    var openedItem by remember { mutableStateOf<GalleryItem?>(null) }

    // 启动时加载数据集：把当前激活的那个读进匹配引擎
    LaunchedEffect(Unit) {
        val (list, activeId, entries) = withContext(Dispatchers.IO) {
            Triple(datasetStore.list(), datasetStore.activeId(), datasetStore.entriesOf(datasetStore.activeId()))
        }
        datasets.clear()
        datasets.addAll(list)
        activeDataset = list.firstOrNull { it.id == activeId } ?: list.first()
        matcherHolder.value = OcrMatcher(entries)
        Log.d(TAG, "dataset loaded: '${activeDataset?.name}' ${entries.size} entries")
    }

    // 启动时把磁盘上已有的相册读进来（只读 JSON，不解码图片）
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { galleryStore.loadAll() }
        gallery.clear()
        gallery.addAll(loaded)
        Log.d(TAG, "gallery loaded ${loaded.size} items from disk")
    }

    // ---------- 数据集：切换 / 导入 ----------

    // 切换当前数据集：重建匹配引擎，并清掉帧缓存（否则会拿旧表格的结果复用）
    val switchDataset: (DatasetMeta) -> Unit = { meta ->
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                datasetStore.setActive(meta.id)
                datasetStore.entriesOf(meta.id)
            }
            matcherHolder.value = OcrMatcher(entries)
            activeDataset = meta
            lastSignature = null
            lastMatches = null
            Log.d(TAG, "switched to dataset '${meta.name}' (${entries.size} entries)")
        }
    }

    // 确认导入：落盘 -> 刷新列表 -> 直接切过去用
    val confirmImport: (PendingImport, String) -> Unit = { pending, name ->
        scope.launch {
            val meta = withContext(Dispatchers.IO) {
                if (pending.bytes != null) {
                    datasetStore.importBytes(pending.bytes, pending.sourceFileName, name)
                } else {
                    datasetStore.importText(pending.text.orEmpty(), name)
                }
            }
            if (meta == null) {
                Log.w(TAG, "import produced no entries, ignored")
            } else {
                val refreshed = withContext(Dispatchers.IO) { datasetStore.list() }
                datasets.clear()
                datasets.addAll(refreshed)
                switchDataset(meta)
            }
            pendingImport = null
        }
    }

    // 文件选择器：不限制 mime，各家文件管理器对 csv 的 mime 判定不一致，
    // 限太死会导致用户的文件是灰的选不中。
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val bytes = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                val name = queryDisplayName(context, uri)
                bytes?.let { it to name }
            }
            if (loaded == null) {
                Log.w(TAG, "无法读取所选文件")
            } else {
                val (bytes, fileName) = loaded
                val (text, encoding) = DatasetParser.decodeText(bytes)
                pendingImport = PendingImport(
                    bytes = bytes,
                    text = null,
                    sourceFileName = fileName,
                    preview = DatasetParser.parse(text),
                    encoding = encoding,
                    suggestedName = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                        ?: "导入的数据集"
                )
            }
        }
    }

    // 当前识别管线。默认「标准」= 最初那一版行为。
    var pipeline by remember { mutableStateOf(Pipeline.Standard) }

    /**
     * 是不是「相机一直在推帧」。
     *
     * 流式分析进来自动连续处理，**完全不看**手动/自动开关——
     * 这条管线本来就没有「按一次快门拍一张」这个概念，开关留着只会让人困惑。
     * 其余四条管线才由 [autoMode] 决定：自动定时拍 / 手动等快门。
     */
    val continuousCapture by remember {
        derivedStateOf { pipeline.usesAnalysis || autoMode }
    }

    // 相册节流用的两个记录：上次存下来的那帧指纹、上次存的时间。
    // 指纹存的是 [CapturedFrame.signature] 的 Long，不是位图。
    val lastSavedSignature = remember { AtomicLong(NEVER_SAVED) }
    val lastSavedAt = remember { AtomicLong(0L) }

    /**
     * 这一帧要不要写进相册。
     *
     * 手动拍摄每张都存；连续推帧时按两条规则节流：
     *  1. 画面跟上次存过的完全一样 → 跳过（指纹相同，说明就是同一张画面）
     *  2. 距上次存不足 [GALLERY_SAVE_INTERVAL_MS] → 跳过
     *
     * 「跳过」不等于丢掉：识别结果照常刷新，画面一变、时间一到就会存下来。
     */
    val shouldSaveToGallery: (Long) -> Boolean = { signature ->
        if (!continuousCapture) {
            true
        } else if (signature == lastSavedSignature.get()) {
            false
        } else {
            val now = System.currentTimeMillis()
            if (now - lastSavedAt.get() < GALLERY_SAVE_INTERVAL_MS) {
                false
            } else {
                lastSavedSignature.set(signature)
                lastSavedAt.set(now)
                true
            }
        }
    }

    /**
     * 处理一帧。5 条管线最终都走这里，区别只在 [pipeline] 决定的取图 / 喂图方式。
     *
     * 位图策略：
     * - 标准 / 降采样：位图既喂 ML Kit，也拿来做缩略图
     * - 省内存 / 小图直出 / 流式：位图**只用来做缩略图**，ML Kit 直接读 MediaImage（零拷贝）
     */
    val processFrame: (ImageProxy) -> Unit = frame@{ image ->
        busy.set(true)
        val current = pipeline
        val rotationDegrees = image.imageInfo.rotationDegrees

        val full = runCatching { image.toBitmap() }.getOrNull()
        if (full == null) {
            Log.e(TAG, "toBitmap() 失败，丢弃这一帧")
            status = OcrStatus.Failed("解码这一帧失败")
            image.close()
            busy.set(false)
            return@frame
        }

        // 缩略图：状态栏和相册都只用它，全分辨率那张不进任何状态
        val thumb = full.scaledToWidth(THUMBNAIL_WIDTH)
        // 指纹用缩略图算——比在全分辨率图上再降采样便宜几十倍
        val signature = CapturedFrame(bitmap = thumb, rotationDegrees = rotationDegrees).signature()

        // 喂给 ML Kit 的那份（只有前两条管线需要位图）
        val ocrSource: Bitmap? = when {
            !current.usesBitmap -> null
            current == Pipeline.Downscaled -> full.scaledToLongEdge(Pipeline.DOWNSCALE_LONG_EDGE)
            else -> full
        }
        // 缩略图和 ocrSource 都是独立拷贝，全分辨率这张可以立刻放掉
        if (ocrSource !== full) full.recycle()

        Log.d(
            TAG,
            "pipeline=${current.label} in=${image.width}x${image.height} " +
                "ocr=${ocrSource?.let { "${it.width}x${it.height}" } ?: "MediaImage"} rot=$rotationDegrees"
        )

        val cached = lastMatches
        if (signature == lastSignature && cached != null) {
            // 画面没变，直接复用上次的比对结果，省掉一次 ML Kit 调用
            Log.d(TAG, "frame unchanged, reuse cached result")
            lastSignature = signature
            status = OcrStatus.Recognized(thumb, cached, fromCache = true)
            if (shouldSaveToGallery(signature)) {
                saveToGallery(
                    store = galleryStore,
                    gallery = gallery,
                    preview = thumb,
                    lines = cached.map { it.rawText },
                    matches = cached
                )
            }
            ocrSource?.recycle()
            image.close()
            busy.set(false)
            return@frame
        }

        status = OcrStatus.Recognizing(thumb, cached)

        // 收尾：成功和失败都走这里，顺手把位图和 ImageProxy 放掉。
        // MediaImage 模式**必须**等 ML Kit 结束才能 close，所以放在回调里而不是 finally。
        val finish: (List<String>, List<LineMatch>, Boolean) -> Unit = { lines, result, ocrFailed ->
            // 失败时不能写 lastSignature / lastMatches：
            // 否则画面不变时会一直复用这份空结果，永远不再重试 OCR。
            if (!ocrFailed) {
                lastSignature = signature
                lastMatches = result
            }
            status = OcrStatus.Recognized(
                bitmap = thumb,
                matches = result,
                fromCache = false,
                ocrFailed = ocrFailed
            )
            if (shouldSaveToGallery(signature)) {
                saveToGallery(
                    store = galleryStore,
                    gallery = gallery,
                    preview = thumb,
                    lines = lines,
                    matches = result
                )
            }
            ocrSource?.recycle()
            image.close()
            busy.set(false)
        }

        try {
            // 注意：ML Kit 的 fromMediaImage 要的是 android.media.Image，
            // 从 ImageProxy 里取；并且这张图必须**保持有效到 ML Kit 回调结束**，
            // 所以 image.close() 放在 finish 里而不是 finally。
            val mediaImage = image.image
            val input = if (ocrSource != null) {
                InputImage.fromBitmap(ocrSource, rotationDegrees)
            } else if (mediaImage != null) {
                InputImage.fromMediaImage(mediaImage, rotationDegrees)
            } else {
                throw IllegalStateException("这一帧拿不到底层 Image，无法零拷贝识别")
            }
            recognize(textRecognizer, matcherHolder.value, input, finish)
        } catch (error: Throwable) {
            Log.e(TAG, "handle captured image failed", error)
            status = OcrStatus.Failed(error.message ?: "处理图片失败")
            ocrSource?.recycle()
            image.close()
            busy.set(false)
        }
    }

    // 用 ImageCapture 抓一帧（标准 / 省内存 / 降采样 / 小图直出 四条管线用）
    val takePicture: (ImageCapture) -> Unit = { useCase ->
        busy.set(true)
        useCase.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    processFrame(image)
                }

                override fun onError(exception: ImageCaptureException) {
                    val retryable = isCameraNotReady(exception)
                    Log.w(TAG, "takePicture failed (retryable=$retryable)", exception)
                    // 相机还没绑定好：交给自动循环稍后重试；循环不在就忽略
                    transientErrorAt = if (retryable) SystemClock.elapsedRealtime() else null
                    if (!retryable) {
                        status = OcrStatus.Failed(exception.message ?: "拍照失败")
                    }
                    busy.set(false)
                }
            }
        )
    }

    // 自动模式：每 1 秒抓一帧。下面这些 key 任一变化就重启循环，
    // Composable 离开时循环自动取消；transientErrorAt 变化会把循环从 delay 里唤醒。
    LaunchedEffect(autoMode, imageCapture, transientErrorAt, screen, pipeline) {
        // 流式分析由相机连续推帧，跟手动/自动开关无关，这里不参与
        if (pipeline.usesAnalysis) return@LaunchedEffect
        val useCase = imageCapture ?: return@LaunchedEffect
        if (!autoMode) return@LaunchedEffect
        // 看相册/看详情时暂停拍摄，别一边翻一边往里塞新照片
        if (screen != Screen.Camera) return@LaunchedEffect

        // 首次进入时给相机一点绑定时间，避免开局必失败
        delay(300)

        while (true) {
            // 同一时刻只跑一帧，识别慢了就顺延，不排队堆积
            if (!busy.get()) {
                takePicture(useCase)
            }
            transientErrorAt = null
            delay(AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    val capture = imageCapture
    val displayMatches = status.matchesOrNull()?.let { matches ->
        // 命中的排前面，避免一堆未命中的噪声把结果淹掉（sortedBy 是稳定排序）
        matches.sortedByDescending { it.isHit }
    }
    // OCR 引擎出错时，空结果不能提示成「没识别到文字」
    val ocrFailed = (status as? OcrStatus.Recognized)?.ocrFailed == true
    // 小米相机式布局：整屏取景，控件浮在上面
    //   顶部：状态胶囊
    //   底部：模式文字 + 左侧缩略图 + 右侧大快门
    //   中间：比对结果面板
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            captureExecutor = captureExecutor,
            pipeline = pipeline,
            onImageCaptureReady = { imageCapture = it },
            onFrame = processFrame,
            modifier = Modifier.fillMaxSize()
        )

        // 顶部一行：命中情况在左，数据集入口在右。
        //
        // 状态胶囊放在一个 weight(1f) 的 Box 里、**靠左**对齐：
        //   - Box 拿到「左边缘 → 数据集胶囊」这整块区域（数据集胶囊不被压缩）
        //   - 胶囊**宽度随文字伸缩**（最小就是文字本身的宽度）
        //   - 超过区域宽度时文字省略
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 12.dp, start = 16.dp, end = 16.dp)
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                StatusBanner(status = status)
            }
            DatasetChip(
                dataset = activeDataset,
                onClick = { screen = Screen.Datasets }
            )
        }

        // 比对结果面板：浮在快门栏上方
        if (displayMatches != null) {
            ResultPanel(
                matches = displayMatches,
                ocrFailed = ocrFailed,
                continuous = continuousCapture,
                onRetake = {
                    status = OcrStatus.Idle
                    lastSignature = null
                    lastMatches = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 150.dp)
            )
        }

        // 底部控件栏：
        //   上一行 = 识别模式（位置参照相机 App 的「专业 / 录像 / 人像」那一排）
        //   下一行 = 左缩略图 / 中快门 / 右模式切换（左右对称）
        // 刻意不加任何背景/渐变衬底 —— 控制栏直接透出取景画面，
        // 只做导航栏避让，让内容延伸到透明的导航栏后面。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 18.dp)
        ) {
            PipelineModeRow(
                current = pipeline,
                onSelect = { picked ->
                    if (picked != pipeline) {
                        pipeline = picked
                        // 换管线要清帧缓存：不同管线画质不同，复用旧结果会误导
                        lastSignature = null
                        lastMatches = null
                    }
                }
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 28.dp, end = 28.dp)
            ) {
                // 左侧：最近一帧缩略图，点一下进内部相册。
                // 刚启动还没有实时帧时，回落到相册里最新的一张（从磁盘按需解码），
                // 否则每次重开 APP 这个位置都是空的。
                Box(
                    modifier = Modifier.size(THUMBNAIL_SLOT),
                    contentAlignment = Alignment.Center
                ) {
                    val live = status.bitmapOrNull()
                    if (live != null) {
                        Thumbnail(bitmap = live, onClick = { screen = Screen.Gallery })
                    } else {
                        // gallery 是最新在前
                        val newest = gallery.firstOrNull()
                        if (newest != null) {
                            val decoded = rememberGalleryBitmap(newest, thumbnailCache)
                            if (decoded != null) {
                                Thumbnail(bitmap = decoded, onClick = { screen = Screen.Gallery })
                            } else {
                                ImagePlaceholder(
                                    modifier = Modifier
                                        .size(THUMBNAIL_SLOT)
                                        .clip(RoundedCornerShape(10.dp))
                                )
                            }
                        }
                    }
                }
    
                Spacer(modifier = Modifier.weight(1f))
    
                ShutterButton(
                    status = status,
                    enabled = capture != null,
                    // 流式分析 / 自动模式下由相机驱动，快门只做状态指示，不参与点击
                    externallyDriven = continuousCapture,
                    onClick = { capture?.let(takePicture) }
                )
    
                Spacer(modifier = Modifier.weight(1f))
    
                // 右侧：模式切换，和左侧缩略图对称的一个圆形按钮
                // 流式分析下这个开关没意义，禁用并显示「连续」
                ModeSwitch(
                    autoMode = autoMode,
                    enabled = !pipeline.usesAnalysis,
                    onToggle = { auto ->
                        autoMode = auto
                        // 切模式时清缓存，免得手动模式下看到自动模式的旧结果
                        lastSignature = null
                        lastMatches = null
                    }
                )
            }
        }

        // 粘贴导入
        if (pasteDialogOpen) {
            PasteDialog(
                onDismiss = { pasteDialogOpen = false },
                onConfirm = { text, name ->
                    pasteDialogOpen = false
                    val parsed = DatasetParser.parse(text)
                    pendingImport = PendingImport(
                        bytes = null,
                        text = text,
                        sourceFileName = null,
                        preview = parsed,
                        encoding = "UTF-8",
                        suggestedName = name.ifBlank { "粘贴的数据集" }
                    )
                }
            )
        }

        // 导入预览确认
        pendingImport?.let { pending ->
            ImportPreviewDialog(
                pending = pending,
                onDismiss = { pendingImport = null },
                onConfirm = { name -> confirmImport(pending, name) }
            )
        }

        // 相册 / 照片详情：盖住取景画面
        when (screen) {
            Screen.Camera -> Unit

            Screen.Gallery -> GalleryScreen(
                items = gallery,
                thumbnailCache = thumbnailCache,
                onClose = { screen = Screen.Camera },
                onOpen = { item ->
                    openedItem = item
                    screen = Screen.Detail
                },
                onClear = {
                    // 磁盘和内存都要清
                    gallery.forEach { thumbnailCache.remove(it.imageFile.path) }
                    galleryStore.clear()
                    gallery.clear()
                    openedItem = null
                },
                modifier = Modifier.fillMaxSize()
            )

            Screen.Datasets -> DatasetScreen(
                datasets = datasets,
                activeId = activeDataset?.id ?: DatasetStore.BUILT_IN_ID,
                onBack = { screen = Screen.Camera },
                onSelect = { meta ->
                    switchDataset(meta)
                    screen = Screen.Camera
                },
                onDelete = { meta ->
                    scope.launch {
                        withContext(Dispatchers.IO) { datasetStore.delete(meta.id) }
                        val refreshed = withContext(Dispatchers.IO) { datasetStore.list() }
                        datasets.clear()
                        datasets.addAll(refreshed)
                        // 删掉的正是当前用的，回落到内置
                        if (activeDataset?.id == meta.id) {
                            refreshed.firstOrNull()?.let { switchDataset(it) }
                        }
                    }
                },
                onPickFile = { filePicker.launch(arrayOf("*/*")) },
                onPaste = { pasteDialogOpen = true },
                modifier = Modifier.fillMaxSize()
            )

            Screen.Detail -> {
                val item = openedItem
                if (item == null) {
                    // 详情对应的那张已经被清掉了，退回相册
                    screen = Screen.Gallery
                } else {
                    GalleryDetailScreen(
                        item = item,
                        thumbnailCache = thumbnailCache,
                        onBack = { screen = Screen.Gallery },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// ---------- 状态辅助 ----------

private fun OcrStatus.bitmapOrNull(): Bitmap? = when (this) {
    is OcrStatus.Recognizing -> bitmap
    is OcrStatus.Recognized -> bitmap
    else -> null
}

/** 当前该显示哪一批比对结果：识别中会回落到上一帧的缓存结果。 */
private fun OcrStatus.matchesOrNull(): List<LineMatch>? = when (this) {
    is OcrStatus.Recognizing -> cached
    is OcrStatus.Recognized -> matches
    else -> null
}

/**
 * 给一帧算一个便宜的“像素指纹”，用来判断画面有没有变。
 * 全图取 32x32 缩略图后采样，开销可以忽略。
 */
private fun CapturedFrame.signature(): Long {
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
private fun Bitmap.scaledToLongEdge(targetLongEdge: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= targetLongEdge) return this
    val ratio = targetLongEdge.toFloat() / longest
    val w = (width * ratio).toInt().coerceAtLeast(1)
    val h = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, w, h, true)
}

private fun Bitmap.scaledToWidth(targetWidth: Int): Bitmap {
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
private fun recognize(
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
private fun saveToGallery(
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

/**
 * 模式切换：和左侧缩略图对称的一个圆形按钮，点一下在「自动 / 手动」之间切换。
 *
 * 选中态用小米相机的黄色 + 一圈黄色描边表示，一眼能看出当前是哪种模式。
 * 流式分析下由相机连续推帧，这个开关不适用：[enabled] 传 false，
 * 按钮灰掉、显示「连续」且不可点。
 */
@Composable
private fun ModeSwitch(
    autoMode: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when {
        !enabled -> "连续"
        autoMode -> "自动"
        else -> "手动"
    }
    // 「连续」是一种被强制的模式，也算选中态，用黄色表示
    val active = !enabled || autoMode

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(THUMBNAIL_SLOT)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(
                width = 1.5.dp,
                color = if (active) XiaomiYellow else Color.White.copy(alpha = 0.6f),
                shape = CircleShape
            )
            .clickable(enabled = enabled) { onToggle(!autoMode) }
    ) {
        Text(
            text = label,
            color = if (active) XiaomiYellow else Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * 小米相机式快门按钮：白色圆环 + 白色内圆。
 *
 * [externallyDriven] 为 true 时（自动模式 / 流式分析）按钮不参与点击，
 * 由相机那边驱动，半透明表示不可点；识别中时内圆变成一个小方块/进度指示。
 */
@Composable
private fun ShutterButton(
    status: OcrStatus,
    enabled: Boolean,
    externallyDriven: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busy = status is OcrStatus.Recognizing
    val clickable = enabled && !busy && !externallyDriven
    // 外部驱动时按钮只做状态指示，所以调暗
    val contentAlpha = if (externallyDriven) 0.35f else 1f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(SHUTTER_SIZE)
            .clip(CircleShape)
            .clickable(enabled = clickable, onClick = onClick)
    ) {
        // 外圈（不额外加 padding，否则会被父容器裁掉）
        Box(
            modifier = Modifier
                .size(SHUTTER_SIZE)
                .border(3.dp, Color.White.copy(alpha = contentAlpha), CircleShape)
        )
        // 内圆
        Box(
            modifier = Modifier
                .size(if (busy) 30.dp else 60.dp)
                .clip(if (busy) RoundedCornerShape(8.dp) else CircleShape)
                .background(
                    if (busy) Color.White.copy(alpha = 0.9f)
                    else Color.White.copy(alpha = contentAlpha)
                )
        )
        if (busy) {
            CircularProgressIndicator(
                color = Color.Black.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 识别模式选择行，位置参照相机 App 的「专业 / 录像 / 人像」那一排。
 *
 * 纯文字、可横向滑动、选中项用强调色高亮（和其它选中态一致）。
 */
@Composable
private fun PipelineModeRow(
    current: Pipeline,
    onSelect: (Pipeline) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp)
    ) {
        // 左右各垫一点，让首尾项不要贴边
        Spacer(modifier = Modifier.width(14.dp))
        Pipeline.entries.forEach { item ->
            val selected = item == current
            Text(
                text = item.label,
                color = if (selected) XiaomiYellow else Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { onSelect(item) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
    }
}

/** 左下角最近一帧缩略图，点一下进内部相册。 */
@Composable
private fun Thumbnail(
    bitmap: Bitmap,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "打开内部相册",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(THUMBNAIL_SLOT)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    )
}

/**
 * 内部相册：两列网格，每格显示缩略图 + 识别结果摘要。
 *
 * 数据全在内存里（[GalleryItem] 只存缩略图），不落盘，退出应用即清空。
 */
@Composable
private fun GalleryScreen(
    items: List<GalleryItem>,
    thumbnailCache: LruCache<String, Bitmap>,
    onClose: () -> Unit,
    onOpen: (GalleryItem) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 系统返回键也用来关相册
    BackHandler(onBack = onClose)

    Surface(color = Color(0xFF101012), modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "‹ 返回",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "相册 ${items.size} 张",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "清空",
                    color = if (items.isEmpty()) Color.White.copy(alpha = 0.3f) else XiaomiYellow,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = items.isNotEmpty(), onClick = onClear)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "还没有照片\n拍一张就会出现在这里",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // 最新的排最前
                    items(items, key = { it.id }) { item ->
                        GalleryCard(
                            item = item,
                            thumbnailCache = thumbnailCache,
                            onClick = { onOpen(item) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 纵览卡片里每格最多显示几行识别结果（分界线上下各算一次）。
 */
private const val CARD_ROWS = 3

/**
 * 相册里的分界线：把「命中的结果」和「其余识别结果」分开。
 */
@Composable
private fun HitDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.18f),
        thickness = 1.dp,
        modifier = modifier.padding(vertical = 6.dp)
    )
}

/**
 * 纵览卡片里的一行识别结果。
 *
 * [hit] 为 true 时用小米黄强调，并用 [note] 标出命中的是表格里的哪一项。
 */
@Composable
private fun CardLine(text: String, color: Color? = null, note: String? = null) {
    val hit = color != null
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = if (hit) "✓ $text" else text,
            color = color ?: Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (note != null) {
            Text(
                text = "→ $note",
                color = (color ?: XiaomiYellow).copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 相册里的一格：缩略图 + 命中情况 + 识别到的前几行文字。点开看详情。 */
@Composable
private fun GalleryCard(
    item: GalleryItem,
    thumbnailCache: LruCache<String, Bitmap>,
    onClick: () -> Unit
) {
    Surface(
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Column {
            val preview = rememberGalleryBitmap(item, thumbnailCache)
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "第 ${item.id} 张",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            } else {
                ImagePlaceholder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#${item.id}",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timeMillis))}",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Text(
                    text = "命中 ${item.hitCount} / ${item.lineCount}",
                    color = if (item.hitCount > 0) XiaomiYellow else Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (item.matches.isEmpty()) {
                    Text(
                        text = "没识别到文字",
                        color = Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    // 分界线之上：命中结果的**副本**，方便一眼看到重点
                    val hits = item.matches.filter { it.isHit }
                    if (hits.isNotEmpty()) {
                        hits.take(CARD_ROWS).forEach { match ->
                            CardLine(
                                text = match.rawText,
                                color = matchColor(match),
                                note = match.entry?.name
                            )
                        }
                        HitDivider()
                    }

                    // 分界线之下：照常列出全部识别结果（命中的也在里面）
                    item.lines.take(CARD_ROWS).forEach { line ->
                        CardLine(text = line)
                    }
                }
            }
        }
    }
}

/**
 * 按需解码一张相册预览图。
 *
 * - 先查 [cache]，命中就直接返回（滚动/来回切不会反复解码）
 * - 没命中就丢到 IO 线程解码，期间返回 null（调用方显示占位）
 * - 图片在磁盘上，所以**内存里只会有最近看过的几张**
 */
@Composable
private fun rememberGalleryBitmap(
    item: GalleryItem,
    cache: LruCache<String, Bitmap>
): Bitmap? {
    val path = item.imageFile.path
    var bitmap by remember(path) { mutableStateOf(cache.get(path)) }

    LaunchedEffect(path) {
        if (bitmap == null && item.imageFile.exists()) {
            val decoded = withContext(Dispatchers.IO) { decodePreview(item.imageFile) }
            if (decoded != null) {
                cache.put(path, decoded)
                bitmap = decoded
            }
        }
    }
    return bitmap
}

/** 解码还没完成时的占位块，避免布局跳动。 */
@Composable
private fun ImagePlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "载入中…",
            color = Color.White.copy(alpha = 0.3f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * 相册里某一张的详情：大图 + **完整的**识别结果。
 *
 * 和拍摄页一样用 [MatchRow] 逐行展示，命中的行高亮打勾并标出命中的表格项，
 * 区别只是这里的图是当时存下来的预览图，且文字区可以整屏滚动。
 */
@Composable
private fun GalleryDetailScreen(
    item: GalleryItem,
    thumbnailCache: LruCache<String, Bitmap>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    Surface(color = Color(0xFF101012), modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "‹ 相册",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "#${item.id}  " +
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.timeMillis)),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp)
            ) {
                // 大图。磁盘上的预览图（480px）按需解码。
                // 用 aspectRatio 让图片框贴合图片本身的比例，
                // 否则框会很宽很矮，ContentScale.Fit 只按高度缩放，图会变得很小。
                val detailBitmap = rememberGalleryBitmap(item, thumbnailCache)
                if (detailBitmap != null) {
                    Image(
                        bitmap = detailBitmap.asImageBitmap(),
                        contentDescription = "第 ${item.id} 张",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(
                                detailBitmap.width.toFloat() / detailBitmap.height.toFloat()
                            )
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    ImagePlaceholder(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                            .clip(RoundedCornerShape(14.dp))
                    )
                }

                // 命中统计
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(
                        text = "命中 ",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "${item.hitCount}",
                        color = XiaomiYellow,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = " / ${item.lineCount}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.titleSmall
                    )
                }

                // 逐行结果，和拍摄页同一个组件。
                // 分界线之上先放命中的，之下再列其余的。
                if (item.matches.isEmpty()) {
                    Text(
                        text = "这一张没有识别到文字",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                } else {
                    // 分界线之上：命中结果的**副本**
                    val hits = item.matches.filter { it.isHit }
                    if (hits.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 10.dp)
                        ) {
                            hits.forEach { match -> MatchRow(match) }
                        }
                        HitDivider()
                    }

                    // 分界线之下：照常显示完整的逐行结果（命中的也在里面）
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 10.dp)
                    ) {
                        item.matches.forEach { match -> MatchRow(match) }
                    }
                }
            }
        }
    }
}

/**
 * 顶部右侧的数据集入口。
 *
 * 必须一直显示「当前用的是哪个表格」——否则识别结果不对时，
 * 你会以为是识别错了，其实是数据集没切。
 */
@Composable
private fun DatasetChip(
    dataset: DatasetMeta?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val name = dataset?.name ?: "加载中…"
    val count = dataset?.entryCount ?: 0
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(50),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            Text(
                text = "$count ›",
                color = XiaomiYellow,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

/** 数据集入口那一行。 */
@Composable
private fun DatasetRow(
    meta: DatasetMeta,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (active) XiaomiYellow.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSelect)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = meta.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (meta.builtIn) {
                        Text(
                            text = "  内置",
                            color = Color.White.copy(alpha = 0.45f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Text(
                    text = buildString {
                        append("${meta.entryCount} 项 · ${meta.encoding}")
                        if (!meta.builtIn) {
                            append(" · ")
                            append(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(meta.importedAt)))
                        }
                        meta.sourceFileName?.let { append(" · $it") }
                    },
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (active) {
                Text(
                    text = "✓ 使用中",
                    color = XiaomiYellow,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // 内置的不允许删
            if (!meta.builtIn) {
                Text(
                    text = "删除",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 数据集管理页：列出全部数据集，并提供两个导入入口。 */
@Composable
private fun DatasetScreen(
    datasets: List<DatasetMeta>,
    activeId: Int,
    onBack: () -> Unit,
    onSelect: (DatasetMeta) -> Unit,
    onDelete: (DatasetMeta) -> Unit,
    onPickFile: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    Surface(color = Color(0xFF101012), modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "‹ 返回",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "数据集（表格）",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                // 占位，让标题居中
                Text(
                    text = "      ",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Text(
                        text = "识别结果会跟「使用中」的这张表格逐项比对。" +
                            "每行一个关键词；一行里有逗号时，第一个是名称、其余当别名。",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                items(datasets, key = { it.id }) { meta ->
                    DatasetRow(
                        meta = meta,
                        active = meta.id == activeId,
                        onSelect = { onSelect(meta) },
                        onDelete = { onDelete(meta) }
                    )
                }
            }

            // 两个导入入口
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 16.dp)
            ) {
                Button(
                    onClick = onPickFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("选文件导入")
                }
                OutlinedButton(
                    onClick = onPaste,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("粘贴导入")
                }
            }
        }
    }
}

/** 粘贴文本导入。 */
@Composable
private fun PasteDialog(
    onDismiss: () -> Unit,
    onConfirm: (text: String, name: String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴导入") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("每行一个关键词") },
                    placeholder = { Text("Serial Number\nModel\nManufacturer, Mfg") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("数据集名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text, name) },
                enabled = text.isNotBlank()
            ) {
                Text("预览")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 导入预览确认。
 *
 * 这一步不能省：格式不对（分隔符、编码、列数）要在这里就能看出来，
 * 否则导入一堆垃圾数据还以为是自己拍错了。
 */
@Composable
private fun ImportPreviewDialog(
    pending: PendingImport,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit
) {
    var name by remember { mutableStateOf(pending.suggestedName) }
    val preview = pending.preview

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认导入") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = buildString {
                        append("解析出 ")
                        append(preview.entries.size)
                        append(" 项")
                        append("（共 ")
                        append(preview.totalLines)
                        append(" 行，编码 ")
                        append(pending.encoding)
                        append("）")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                if (preview.skippedLines.isNotEmpty()) {
                    Text(
                        text = "跳过了 ${preview.skippedLines.size} 行（空行 / 注释 / 重复项）",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB26A00),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (preview.entries.isEmpty()) {
                    Text(
                        text = "没有解析出任何条目，请检查文件内容",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFC62828),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Text(
                        text = "前几项预览：",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    preview.entries.take(8).forEach { entry ->
                        Text(
                            text = buildString {
                                append("· ")
                                append(entry.name)
                                if (entry.aliases.isNotEmpty()) {
                                    append("   （别名：")
                                    append(entry.aliases.joinToString(" / "))
                                    append("）")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    if (preview.entries.size > 8) {
                        Text(
                            text = "…… 还有 ${preview.entries.size - 8} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("数据集名称") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = preview.entries.isNotEmpty()
            ) {
                Text("导入并使用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 从 content Uri 里取显示用的文件名。 */
private fun queryDisplayName(context: Context, uri: android.net.Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()

/** 顶部状态胶囊。 */
@Composable
private fun StatusBanner(status: OcrStatus, modifier: Modifier = Modifier) {
    val text = when (status) {
        OcrStatus.Idle -> "对准目标，等待识别"
        is OcrStatus.Recognizing -> {
            val cached = status.cached
            if (cached == null) "正在识别文字…"
            else "命中 ${cached.count { it.isHit }} / ${cached.size}（更新中…）"
        }
        is OcrStatus.Recognized -> when {
            // 引擎出错和「图里没字」是两回事，提示必须分开
            status.ocrFailed -> "OCR 识别失败"
            status.matches.isEmpty() -> "没识别到文字"
            status.hitCount > 0 ->
                "命中 ${status.hitCount} / ${status.matches.size}" +
                    if (status.fromCache) "（画面未变）" else ""
            // 刻意写短：胶囊越窄，顶部越不容易挤
            else -> "无命中 · ${status.matches.size} 行"
        }
        is OcrStatus.Failed -> "失败：${status.message}"
    }
    Surface(
        color = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (status is OcrStatus.Recognizing) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                // 被 Row 的 weight 压缩时省略，而不是溢出盖住右侧的数据集胶囊
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 比对结果面板：逐行显示，命中的行打勾并标出命中的表格项。
 *
 * [continuous] 表示相机在持续推帧（自动模式 / 流式分析），此时「重拍」没有意义。
 */
@Composable
private fun ResultPanel(
    matches: List<LineMatch>,
    ocrFailed: Boolean,
    continuous: Boolean,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hitCount = matches.count { it.isHit }
    Surface(
        color = Color.Black.copy(alpha = 0.72f),
        shape = RoundedCornerShape(20.dp),
        // 面板整体高度必须封顶：识别行数多的时候不能无限长，
        // 否则会盖住底部快门栏
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "命中 ",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "$hitCount",
                    color = XiaomiYellow,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = " / ${matches.size}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            if (matches.isEmpty()) {
                Text(
                    text = if (ocrFailed) {
                        "OCR 识别失败，请重试"
                    } else {
                        "没有识别到文字，试试靠近一点、让文字占满画面"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                // weight(1f) 让列表吃掉标题/按钮之外的全部空间，超出部分内部滚动
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    matches.forEach { match -> MatchRow(match) }
                }
            }

            // 持续推帧时“重拍”没有意义
            if (!continuous) {
                Text(
                    text = "重拍",
                    color = XiaomiYellow,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onRetake)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** 单行的比对结果：命中 = 高亮 + 打勾，未命中 = 灰显。 */
@Composable
private fun MatchRow(match: LineMatch) {
    // 完全相同 → 绿，近似 → 黄，未命中 → 灰
    val color = matchColor(match)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color?.copy(alpha = 0.16f) ?: Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (match.isHit) "✓" else "·",
            color = color ?: Color.White.copy(alpha = 0.35f),
            style = MaterialTheme.typography.bodyMedium
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = match.rawText,
                color = if (match.isHit) Color.White else Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyMedium
            )
            if (match.isHit) {
                Text(
                    text = buildString {
                        append("→ ")
                        append(match.entry?.name)
                        if (!match.isExact) {
                            append("（近似 ")
                            append("%.0f%%".format(match.similarity * 100))
                            append("）")
                        }
                    },
                    color = color ?: XiaomiYellow,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun PermissionRationale(
    askAgain: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (askAgain) "需要相机权限才能预览" else "相机权限已被拒绝，请到系统设置里手动开启",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 12.dp)
        ) {
            if (askAgain) {
                Button(onClick = onRequest) { Text("授予权限") }
            } else {
                Button(onClick = onOpenSettings) { Text("打开设置") }
            }
        }
    }
}

/**
 * 相机预览 + 拍照用例。
 *
 * 关键点：
 * - [ProcessCameraProvider] 把 Preview / ImageCapture 绑定到 LifecycleOwner（这里是 Activity），
 *   CameraX 自动处理 onStart/onStop/onDestroy，不用手写开关相机。
 * - [PreviewView] 是传统 View，用 [AndroidView] 包进 Compose。
 * - [ImageCapture] 的 targetRotation 跟随屏幕旋转，拍出来的图才是正的。
 *
 * 流式分析（[Pipeline.Analysis]）用 [ImageAnalysis] 替代 ImageCapture：
 * KEEP_ONLY_LATEST + 上一帧 close 之后才推下一帧，所以是「识别完立刻拿最新的一帧」，
 * **不看**手动/自动开关——进了这条管线就一直处理。
 */
@Composable
fun CameraPreview(
    captureExecutor: ExecutorService,
    pipeline: Pipeline,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onFrame: (ImageProxy) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            // 性能更好、可预测的缩放方式
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)

    // pipeline 作为 key：切换管线时重建用例并重新绑定
    DisposableEffect(lifecycleOwner, pipeline) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var boundCameraProvider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            boundCameraProvider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            cameraProvider.unbindAll()

            if (pipeline.usesAnalysis) {
                // D 流式分析：由相机推帧，KEEP_ONLY_LATEST 保证不排队堆积
                analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Pipeline.ANALYSIS_SIZE,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(previewView.display.rotation)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(captureExecutor) { image ->
                            // 流式分析就是「一直处理」：手动/自动开关对这条管线不生效
                            onFrame(image)
                        }
                    }

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } else {
                // A/B/C/标准：ImageCapture。C 走 ResolutionSelector 让 HAL 直接出小图
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setTargetRotation(previewView.display.rotation)
                    .apply {
                        if (pipeline == Pipeline.SmallCapture) {
                            setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Pipeline.SMALL_CAPTURE_SIZE,
                                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                        )
                                    )
                                    .build()
                            )
                        }
                    }
                    .build()

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                onImageCaptureReady(imageCapture)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // 页面销毁 / 切换管线时解绑，避免相机被占用或回调打到已销毁的 View 上
            analysis?.clearAnalyzer()
            boundCameraProvider?.unbindAll()
        }
    }
}

private fun Context.openAppSettings() {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", packageName, null)
    )
    startActivity(intent)
}

/**
 * 判断这个错误是不是“相机还没绑定好”这种可以重试的情况。
 *
 * `bindToLifecycle()` 是异步的：ImageCapture 对象一创建就非空了，但底层相机
 * 可能要过几百毫秒才真正打开。这期间 takePicture 会抛
 * `Not bound to a valid Camera`，等一会儿重试即可，不是真故障。
 */
private fun isCameraNotReady(exception: ImageCaptureException): Boolean {
    val message = exception.message ?: return false
    return message.contains("Not bound to a valid Camera", ignoreCase = true) ||
        message.contains("Camera is closed", ignoreCase = true)
}
