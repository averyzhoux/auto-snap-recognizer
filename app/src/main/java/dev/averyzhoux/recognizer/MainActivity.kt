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
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.TorchState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.lifecycle.Observer
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

    // 观察相机 LiveData（手电筒状态）要它
    val lifecycleOwner = LocalLifecycleOwner.current

    // ---------- 手电筒 ----------
    // 相机对象（绑定时非空）。手电筒是相机的功能，没相机就没得开。
    var camera by remember { mutableStateOf<Camera?>(null) }
    // ★ 这个值**跟着相机的实际状态走**，不是我们自己记的：见下面的 torchState 观察。
    //   自己记会有个说不上来的毛病——把 App 切到后台时相机被关掉、手电筒跟着灭，
    //   但自己记的变量还停在「开」，回来时图标亮着而灯是灭的。
    var torchOn by remember { mutableStateOf(false) }

    val setTorch: (Boolean) -> Unit = { on ->
        // enableTorch 是异步的，结果由 torchState 的观察回填，这里不直接改 torchOn
        camera?.cameraControl?.enableTorch(on)
    }

    // 相机的实际手电筒状态 → 图标状态。
    // 用 observe 而不是自己记：切后台时相机会被关掉、灯跟着灭，
    // 自己记的变量不会知道，回来就会「图标亮着、灯是灭的」。
    DisposableEffect(camera) {
        val cam = camera
        if (cam == null) {
            torchOn = false
            onDispose { }
        } else {
            val observer = Observer<Int> { state ->
                torchOn = state == TorchState.ON
                Log.d(TAG, "torch ${if (torchOn) "on" else "off"}")
            }
            cam.cameraInfo.torchState.observe(lifecycleOwner, observer)
            onDispose { cam.cameraInfo.torchState.removeObserver(observer) }
        }
    }

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

    // 当前识别管线。默认「小型图」：从 HAL 源头就出小图，省内存也省时间，日常扫标签够用
    var pipeline by remember { mutableStateOf(Pipeline.SmallCapture) }

    // 流式专用：相机一直在推帧，需要一个停下来的开关（见右下角那个圆钮）。
    // 进流式时默认就是暂停——先让人有机会调设置，再点「继续」开跑。
    var analysisPaused by remember { mutableStateOf(false) }

    // ---------- 「命中某颜色就自动停」的两个状态 ----------
    //
    // ★ lastHitColor 是**边沿触发**用的：只有「上一帧没命中色 → 这一帧有」才停一次。
    //   不做边沿的话，停下之后画面里那个绿色还在，用户一点「继续」就立刻又被停住，
    //   等于永远恢复不了。
    // ★ 按用户要求**不做「离开一段时间才算新一次」的宽限**：恢复后命中色仍在画面里
    //   就不再触发，想再停一次得先把它移开（移开后 lastHitColor 变 null，又能触发）。
    var lastHitColor by remember { mutableStateOf<HitColor?>(null) }

    // ★ 这次暂停是不是「因为命中」自动停的。单独记一个，是因为 autoMode=false
    //   和「暂停」在界面上是同一个样子：用户自己按掉的自动模式不能显示绿色提示环。
    //   绿色环的判据是 `暂停中 && pausedByHit`。
    var pausedByHit by remember { mutableStateOf(false) }

    // 各条管线的可调参数（分析间隔 / 降采样长边 / 相册保存间隔 / 只存命中的帧）。
    // 从磁盘读回来，所以上次调好的值重启后还在；读不到就走数据类里的默认值。
    val settingsStore = remember { PipelineSettingsStore(context.applicationContext) }
    var pipelineSettings by remember { mutableStateOf(settingsStore.load()) }
    // 设置面板是否展开（再点一次已选中、且可配置的那条管线展开）
    var settingsOpen by remember { mutableStateOf(false) }
    // 上一次真正处理帧的时刻，用来实现「分析间隔」限速
    val lastAnalysisAt = remember { AtomicLong(0L) }

    /**
     * 离开取景页时，把「条件暂停」留下的两个状态清掉。
     *
     * ★ `keepPreviousState` 这个中间变量是为了**区分「第一次进来」和「真的切屏」**：
     *   不记上一值的话，首次组合也会当成一次切换，把状态白清一遍。
     *
     * 流式的 `analysisPaused` **不动**——它有自己的语义（用户明确按过「继续」才算跑），
     * 清掉会让翻完相册回来就自己跑起来。这里只撤掉提示相关的两个值。
     */
    var keepPreviousState by remember { mutableStateOf(screen) }
    LaunchedEffect(screen) {
        if (screen == keepPreviousState) return@LaunchedEffect
        keepPreviousState = screen
        pausedByHit = false
        lastHitColor = null
    }

    /**
     * 是不是「相机一直在推帧」。
     *
     * 流式进来自动连续处理，**完全不看**手动/自动开关——
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
    val shouldSaveToGallery: (Long, Int) -> Boolean = { signature, hitCount ->
        if (!continuousCapture) {
            true
        } else if (pipelineSettings.saveHitsOnly && hitCount == 0) {
            // 只存命中的帧：一行都没对上就不留
            false
        } else if (signature == lastSavedSignature.get()) {
            false
        } else {
            val now = System.currentTimeMillis()
            if (now - lastSavedAt.get() < pipelineSettings.gallerySaveIntervalMs) {
                false
            } else {
                lastSavedSignature.set(signature)
                lastSavedAt.set(now)
                true
            }
        }
    }

    /**
     * 条件暂停：命中指定颜色就停下来。
     *
     * 每条管线各有一组勾选的颜色（面板里那三个色块），空集就是没开这个功能。
     * 连续运行时才会被调用——手动模式本来就不连续抓帧，没有「停」可言。
     *
     * ★ 判据走 [hitColorOf]，是**显示颜色**而不是 MatchKind：一个「已标记 + 精确命中」
     *   的项在面板上是蓝的，勾了绿色就不会停它。看起来像漏判，其实是照看到的颜色说话。
     *
     * 这个函数在 ML Kit 的回调里被同步调用。和它上面那几行更新 `status` 的代码一样——
     * 识别早就跑在后台线程上，这里只做赋值，不碰相机、不碰位图。
     */
    val maybeAutoPause: (List<LineMatch>) -> Unit = { result ->
        val watched = pipelineSettings.autoPauseColors[pipeline].orEmpty()
        // 当前这一帧里出现的颜色；取「最优先」的那个，用户勾了那个就停
        val current = HitColor.entries.firstOrNull { it in watched && result.any { m -> hitColorOf(m) == it } }
        // 空集 / 画面里没勾选的颜色 → 都归成 null，等价于「这次没有命中色」
        if (current != null && lastHitColor == null) {
            // 流式：相机还在推帧，停的是 analyzer 那边（靠 deliverFrames 丢帧）
            if (pipeline.usesAnalysis) {
                analysisPaused = true
            } else {
                // 其余四条：停掉自动抓帧循环。**不清 lastSignature / lastMatches**——
                // 手动点「自动」关掉时会清缓存，但这里是自动停的，
                // 结果面板上那行命中正被用户盯着看，清了会闪一下再重算。
                autoMode = false
            }
            pausedByHit = true
            Log.d(TAG, "条件暂停：命中${current.label}色（pipeline=${pipeline.label}）")
        }
        lastHitColor = current
    }

    /**
     * 处理一帧。5 条管线最终都走这里，区别只在 [pipeline] 决定的取图 / 喂图方式。
     *
     * 位图策略：
     * - 基础 / 降采样：位图既喂 ML Kit，也拿来做缩略图
     * - 省内存 / 小型图 / 流式：位图**只用来做缩略图**，ML Kit 直接读 MediaImage（零拷贝）
     */
    val processFrame: (ImageProxy) -> Unit = frame@{ image ->
        // ★ 流式限速：距上一次真正处理不足「分析间隔」就把这帧丢掉。
        //   放在 toBitmap() **之前**丢——YUV→RGB 转换是这条路上最贵的一步，
        //   丢在这里才真的省下 CPU 和电，而不是转完再扔。
        //   image 必须 close，否则相机管线会卡住。
        if (pipeline.usesAnalysis && pipelineSettings.analysisIntervalMs > 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisAt.get() < pipelineSettings.analysisIntervalMs) {
                image.close()
                return@frame
            }
            lastAnalysisAt.set(now)
        }

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
            current == Pipeline.Downscaled -> full.scaledToLongEdge(pipelineSettings.downscaleLongEdge)
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
            if (shouldSaveToGallery(signature, cached.count { it.isHit })) {
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
            if (shouldSaveToGallery(signature, result.count { it.isHit })) {
                saveToGallery(
                    store = galleryStore,
                    gallery = gallery,
                    preview = thumb,
                    lines = lines,
                    matches = result
                )
            }
            maybeAutoPause(result)
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

    // 用 ImageCapture 抓一帧（基础 / 省内存 / 降采样 / 小型图 四条管线用）
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
        // 流式由相机连续推帧，跟手动/自动开关无关，这里不参与
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
            // 抓帧周期可以在「降采样设置」里改；0 表示用默认值
            // （不能真按 0 跑，那是个死循环式的连拍）
            val interval = pipelineSettings.captureIntervalMs
            delay(if (interval > 0) interval else AUTO_CAPTURE_INTERVAL_MS)
        }
    }

    val capture = imageCapture

    /**
     * 相机输出的「短边/长边」之比（4:3 竖屏 = 0.75），给降采样设置面板推算缩完的尺寸用。
     *
     * 比例是设备和传感器的属性，不该让使用者再填一遍，所以直接从相机的输出规格读。
     * ★ 相机绑好之前 [ImageCapture.getResolutionInfo] 是 null，这时按 4:3 估一个——
     * 绝大多数手机后摄就是 4:3，估错也只是面板上那行提示数字不准，不影响实际缩放。
     */
    val captureAspect: Float = runCatching {
        imageCapture?.resolutionInfo?.resolution
    }.getOrNull()?.let { size ->
        if (size.width > 0 && size.height > 0) {
            minOf(size.width, size.height).toFloat() / maxOf(size.width, size.height)
        } else {
            null
        }
    } ?: (3f / 4f)

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
            // 只有停在拍摄页、且流式没被暂停时才处理帧：
            // 相册 / 详情 / 数据集都是本 Activity 的 Compose 覆盖层，不触发 onStop，
            // CameraX 不会自己停
            deliverFrames = screen == Screen.Camera && !analysisPaused,
            onImageCaptureReady = { imageCapture = it },
            onCameraReady = { camera = it },
            onFrame = processFrame,
            modifier = Modifier.fillMaxSize()
        )

        // 顶部一行：命中情况在左，数据集入口在右。
        //
        // 状态胶囊放在一个 weight(1f) 的 Box 里、**靠左**对齐：
        //   - Box 拿到「左边缘 → 数据集胶囊」这整块区域（数据集胶囊不被压缩）
        //   - 胶囊**宽度随文字伸缩**（最小就是文字本身的宽度）
        //   - 超过区域宽度时文字省略
        // 「闪电 → 文字」的间隙和下面 Row 的 start 内边距**必须相等**，闪电左右才一样宽。
        // 推导见 [TorchButton] 的注释：两边留白 = 这个值 + 13.75dp（触摸区内边距 10 + 图案内缩 3.75）。
        // 取 2dp → 两边各 15.75dp，正好是原来 30dp 的一半。
        // end 保持 16dp，那是右边数据集入口的边距，跟闪电无关。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 12.dp, start = 2.dp, end = 16.dp)
        ) {
            Box(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    TorchButton(
                        on = torchOn,
                        enabled = camera != null,
                        onToggle = { setTorch(!torchOn) }
                    )
                    StatusBanner(status = status)
                }
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
                // 当前这条管线勾了哪几个「条件暂停」颜色，就在它标签下方点几个点。
                // 只传当前这条：圆点的作用是「一眼看出现在这条模式会因什么而停」，
                // 不是给五条管线各列一份配置。
                pauseColors = pipelineSettings.autoPauseColors[pipeline].orEmpty(),
                onSelect = { picked ->
                    if (picked == pipeline) {
                        // ★ 再点一次已经选中的那条管线 = 展开它的设置面板
                        //   （标签正上方有个自绘的上拉箭头示意）
                        settingsOpen = true
                    } else {
                        pipeline = picked
                        // 换管线要清帧缓存：不同管线画质不同，复用旧结果会误导
                        lastSignature = null
                        lastMatches = null
                        // 进流式**默认暂停**：先让人有机会调设置，再点「继续」开跑。
                        // 换成别的管线时这个值不影响它们（deliverFrames 只管 ImageAnalysis）。
                        analysisPaused = picked.usesAnalysis
                        // 换管线 = 换一组「条件暂停」设置，上一组的状态全部作废
                        pausedByHit = false
                        lastHitColor = null
                        settingsOpen = false
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
                    // 流式 / 自动模式下由相机驱动，快门只做状态指示，不参与点击
                    externallyDriven = continuousCapture,
                    onClick = { capture?.let(takePicture) }
                )

                Spacer(modifier = Modifier.weight(1f))

                // 右下角圆钮：普通管线切自动/手动，流式切暂停/继续。
                // 被「条件暂停」停掉时，这个钮改成绿色提示一下（见 highlight）。
                ModeSwitch(
                    label = if (pipeline.usesAnalysis) {
                        // 显示的是一直在跑时该按的动作：跑着显示「暂停」，停了显示「继续」
                        if (analysisPaused) "继续" else "暂停"
                    } else {
                        if (autoMode) "自动" else "手动"
                    },
                    active = if (pipeline.usesAnalysis) !analysisPaused else autoMode,
                    // ★ 只有「因为命中而停」才亮绿：用户自己按掉的自动模式不亮，
                    //   否则两条来路分不出来，提示就失去意义了
                    highlight = pausedByHit,
                    onClick = {
                        if (pipeline.usesAnalysis) {
                            // 只翻转这一个开关：analyzer 那边靠 deliverFrames 丢掉帧
                            analysisPaused = !analysisPaused
                        } else {
                            autoMode = !autoMode
                            // 切模式时清缓存，免得手动模式下看到自动模式的旧结果
                            lastSignature = null
                            lastMatches = null
                        }
                        // 人一动手，绿色提示就撤掉。★ lastHitColor **故意不在这里清**：
                        // 用户点「继续」时命中色多半还在画面里，不清才不会立刻又被停一次；
                        // 等它移开，lastHitColor 自己会变成 null，又能触发下一次。
                        pausedByHit = false
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

        // 流式设置：从底部上拉展开。
        // 遮罩先声明、面板后声明，这样面板在遮罩之上；点遮罩任意处收起。
        if (settingsOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(
                        // 只认点击，不能让手指划过也关掉
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { settingsOpen = false }
            )
        }
        AnimatedVisibility(
            visible = settingsOpen,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PipelineSettingsSheet(
                pipeline = pipeline,
                settings = pipelineSettings,
                sourceAspect = captureAspect,
                // 改一项就落一次盘（后台线程，不卡 UI）。点标签和自填数字都走这里。
                onChange = {
                    pipelineSettings = it
                    settingsStore.save(it)
                },
                onDismiss = { settingsOpen = false }
            )
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
                    // ★ 选完**不**自动退回取景页：切换只是把「使用中」挪个位置，
                    //   留在列表里才能一眼看见换成功没有，也方便接着编辑/再换。
                    //   （识别引擎和帧缓存由 switchDataset 负责刷新。）
                    switchDataset(meta)
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
