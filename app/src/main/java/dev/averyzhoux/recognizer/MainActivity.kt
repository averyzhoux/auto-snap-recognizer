package dev.averyzhoux.recognizer

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.averyzhoux.recognizer.ui.theme.RecognizerTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.runtime.mutableStateListOf

/** 当前显示哪一屏 */
internal enum class Screen { Camera, Gallery, Detail, Datasets, DatasetEdit }

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
    // 正在编辑的数据集（null = 没进编辑页）。条目单独放一份可变的，
    // 每点一下标记就整份替换，避免原地改 List 导致 Compose 看不见变化。
    var editingMeta by remember { mutableStateOf<DatasetMeta?>(null) }
    var editingEntries by remember { mutableStateOf<List<Entry>>(emptyList()) }
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

    // ---------- 数据集：切换 / 导入 / 编辑 ----------

    // 进编辑页：把条目读出来放进内存
    val openEditor: (DatasetMeta) -> Unit = { meta ->
        scope.launch {
            val entries = withContext(Dispatchers.IO) { datasetStore.entriesOf(meta.id) }
            editingMeta = meta
            editingEntries = entries
            screen = Screen.DatasetEdit
        }
    }

    // 编辑页点一下某一项的标记框
    val toggleMark: (Int) -> Unit = { index ->
        val meta = editingMeta
        val current = editingEntries
        if (meta != null && index in current.indices) {
            val updated = current.toMutableList().also {
                it[index] = it[index].copy(marked = !it[index].marked)
            }
            editingEntries = updated

            // ★ 改的如果正是「使用中」的那份表格，必须马上换掉匹配引擎并清帧缓存：
            //   匹配引擎里的 Entry 是旧实例（marked 还是老值），
            //   而帧缓存会让当前画面直接复用旧结果，两个都会让蓝色出不来。
            if (meta.id == activeDataset?.id) {
                matcherHolder.value = OcrMatcher(updated)
                lastSignature = null
                lastMatches = null
            }

            // 每次点击就落盘：文件才几十 KB，写完即走，
            // 比「退出时统一保存」安全（中途被杀也不会丢标记）
            scope.launch {
                withContext(Dispatchers.IO) { datasetStore.saveEntries(meta.id, updated) }
            }
        }
    }

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
            // 只有停在拍摄页才处理帧：相册 / 详情 / 数据集都是本 Activity 的
            // Compose 覆盖层，不触发 onStop，CameraX 不会自己停
            deliverFrames = screen == Screen.Camera,
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

        // 底部控件栏（自下而上）：
        //   比对结果面板（有结果时才出现）
        //   识别模式行（位置参照相机 App 的「专业 / 录像 / 人像」那一排）
        //   左缩略图 / 中快门 / 右模式切换（左右对称）
        // 刻意不加任何背景/渐变衬底 —— 控制栏直接透出取景画面，
        // 只做导航栏避让，让内容延伸到透明的导航栏后面。
        //
        // 结果面板和控件栏放在**同一个 Column**里，而不是各自 align(BottomCenter)
        // 再用一个写死的 bottom padding 错开：Column 的底边钉在屏幕底部，
        // 面板只会往上长，所以面板多高、模式行多高、导航栏多高都不会互相压住，
        // 不用维护「面板要抬高多少 dp」这种跟着布局变化的常量。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                // 版本号已经移出这个 Column、单独压在屏幕最底边了，
                // 所以这里的间距恢复成加版本号之前的值
                .padding(bottom = 18.dp)
        ) {
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
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp)
                )
            }

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

        // 版本号：压在屏幕最底边，在「系统导航键下沿 → 屏幕底边」这段里上下居中。
        //
        // ★ 刻意**不**避让 navigationBars —— 要的就是导航键（☰ ◻ ◁）下面那条空隙。
        //   实机量出来：导航栏高约 47dp、图标高约 14dp 且**在栏内居中**，
        //   所以图标下沿距底约 16.3dp，「图标下沿 → 屏幕底」这段的中点就是 8.15dp。
        //   字号 10sp / 行高 14dp 的行盒，垫 2.5dp 后文字墨迹中心落在约 8.3dp。
        //   —— 这几个数是按真机量出来的，**改字号或行高要一起重算**。
        //   换成手势导航时底部中间是那颗胶囊、会撞上，那时应改回避让 insets。
        //
        // 放在控件 Column **外面**：Column 底边虽然钉在屏幕底部，但它的内容
        // 从导航栏上沿才开始，所以两者不会抢位置——Column 的底部留白正好让给这一行。
        // 位置排在 `when (screen)` 之前，这样翻相册/数据集时会被那些覆盖层盖住，
        // 只在取景页出现。
        VersionLabel(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.5.dp)
        )

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
                onEdit = openEditor,
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

            Screen.DatasetEdit -> DatasetEditScreen(
                name = editingMeta?.name ?: "数据集",
                entries = editingEntries,
                onBack = {
                    editingMeta = null
                    editingEntries = emptyList()
                    screen = Screen.Datasets
                },
                onToggle = toggleMark,
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
