package com.example.recognizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
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
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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
import com.example.recognizer.ui.theme.RecognizerTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.Date
import java.io.File
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Recognizer"

/** 自动模式下两帧之间的间隔 */
private const val AUTO_CAPTURE_INTERVAL_MS = 1_000L

/** 快门按钮的直径 */
private val SHUTTER_SIZE = 76.dp

/** 缩略图槽位尺寸，左右各留一个保证快门在视觉上居中 */
private val THUMBNAIL_SLOT = 56.dp

/** 小米相机选中态的那个黄 */
private val XiaomiYellow = Color(0xFFFFC800)

/**
 * 存下来的预览图宽度。
 *
 * 480px 的 JPEG 约 40KB/张，写在磁盘上（见 [GalleryStore]）；
 * 解码进内存时用 RGB_565，约 600KB/张，且只有最近看过的
 * [THUMBNAIL_CACHE_SIZE] 张会被缓存。
 */
private const val THUMBNAIL_WIDTH = 480

/** 摄像头拍到的原始帧（已是给 ML Kit 用的方向），bitmap 是缩小过的缩略图。 */
private data class CapturedFrame(val bitmap: Bitmap, val rotationDegrees: Int)

/** 一次识别 + 比对的结果。 */
private data class MatchResult(val matches: List<LineMatch>, val fromCache: Boolean)

/** 内存里最多同时缓存几张已解码的预览图（每张约 600KB，12 张约 7MB） */
private const val THUMBNAIL_CACHE_SIZE = 12

/** 当前显示哪一屏 */
private enum class Screen { Camera, Gallery, Detail }

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
        val fromCache: Boolean
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
    val matcher = remember { OcrMatcher(Dataset.entries) }
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

    // 启动时把磁盘上已有的相册读进来（只读 JSON，不解码图片）
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { galleryStore.loadAll() }
        gallery.clear()
        gallery.addAll(loaded)
        Log.d(TAG, "gallery loaded ${loaded.size} items from disk")
    }

    // 抽成函数，给自动循环和手动按钮共用
    val takePicture: (ImageCapture) -> Unit = { useCase ->
        busy.set(true)
        useCase.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        // ★ 核心坑位：toBitmap() 是“传感器方向”的原始像素，
                        //   必须把 rotationDegrees 一路传给 ML Kit，否则图是躺着的。
                        val rotationDegrees = image.imageInfo.rotationDegrees
                        val bitmap = image.toBitmap()
                        val frame = CapturedFrame(
                            bitmap = bitmap,
                            rotationDegrees = rotationDegrees
                        )

                        val signature = frame.signature()
                        // 缩略图：状态栏和相册都只用它，12MB 的原图不进任何状态
                        val thumb = bitmap.scaledToWidth(THUMBNAIL_WIDTH)
                        val cached = lastMatches
                        val reuse = signature == lastSignature && cached != null

                        if (reuse) {
                            // 画面没变，直接复用上次的比对结果，省掉一次 ML Kit 调用
                            Log.d(TAG, "frame unchanged, reuse cached result")
                            lastSignature = signature
                            status = OcrStatus.Recognized(thumb, cached, fromCache = true)
                            saveToGallery(
                                store = galleryStore,
                                gallery = gallery,
                                preview = thumb,
                                lines = cached.map { it.rawText },
                                matches = cached
                            )
                            // 大图用完立刻回收，不再让它挂在闭包里等 GC
                            bitmap.recycle()
                            busy.set(false)
                        } else {
                            status = OcrStatus.Recognizing(thumb, cached)
                            recognize(textRecognizer, matcher, bitmap, rotationDegrees) { lines, result ->
                                lastSignature = signature
                                lastMatches = result
                                status = OcrStatus.Recognized(thumb, result, fromCache = false)
                                saveToGallery(
                                    store = galleryStore,
                                    gallery = gallery,
                                    preview = thumb,
                                    lines = lines,
                                    matches = result
                                )
                                // 识别已结束，原图可以回收
                                bitmap.recycle()
                                busy.set(false)
                            }
                        }
                    } catch (error: Throwable) {
                        Log.e(TAG, "handle captured image failed", error)
                        status = OcrStatus.Failed(error.message ?: "处理图片失败")
                        busy.set(false)
                    } finally {
                        // 一定要关，否则相机管线会卡住
                        image.close()
                    }
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
    LaunchedEffect(autoMode, imageCapture, transientErrorAt, screen) {
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
    // 小米相机式布局：整屏取景，控件浮在上面
    //   顶部：状态胶囊
    //   底部：模式文字 + 左侧缩略图 + 右侧大快门
    //   中间：比对结果面板
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            captureExecutor = captureExecutor,
            onImageCaptureReady = { imageCapture = it },
            modifier = Modifier.fillMaxSize()
        )

        // 顶部状态胶囊（保留状态栏，只做状态栏避让）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 12.dp, start = 16.dp, end = 16.dp)
        ) {
            StatusBanner(status = status)
        }

        // 比对结果面板：浮在快门栏上方
        if (displayMatches != null) {
            ResultPanel(
                matches = displayMatches,
                autoMode = autoMode,
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

        // 底部控件栏：左缩略图、中快门、右模式切换（左右对称）
        // 刻意不加任何背景/渐变衬底 —— 控制栏直接透出取景画面，
        // 只做导航栏避让，让内容延伸到透明的导航栏后面。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 18.dp, start = 28.dp, end = 28.dp)
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
                autoMode = autoMode,
                onClick = { capture?.let(takePicture) }
            )

            Spacer(modifier = Modifier.weight(1f))

            // 右侧：模式切换，和左侧缩略图对称的一个圆形按钮
            ModeSwitch(
                autoMode = autoMode,
                onToggle = { auto ->
                    autoMode = auto
                    // 切模式时清缓存，免得手动模式下看到自动模式的旧结果
                    lastSignature = null
                    lastMatches = null
                }
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
    bitmap: Bitmap,
    rotationDegrees: Int,
    onResult: (lines: List<String>, matches: List<LineMatch>) -> Unit
) {
    // 第二个参数就是旋转角度，交给 ML Kit 摆正
    val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)

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

            onResult(lines, matches)
        }
        .addOnFailureListener { error ->
            Log.e(TAG, "OCR failed", error)
            // 识别失败不算致命：自动模式下下一帧会重试，这里保留上一批结果
            onResult(emptyList(), emptyList())
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
 */
@Composable
private fun ModeSwitch(
    autoMode: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(THUMBNAIL_SLOT)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(
                width = 1.5.dp,
                color = if (autoMode) XiaomiYellow else Color.White.copy(alpha = 0.6f),
                shape = CircleShape
            )
            .clickable { onToggle(!autoMode) }
    ) {
        Text(
            text = if (autoMode) "自动" else "手动",
            color = if (autoMode) XiaomiYellow else Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/**
 * 小米相机式快门按钮：白色圆环 + 白色内圆。
 *
 * 自动模式下按钮不参与点击（由定时器驱动），半透明表示不可点；
 * 识别中时内圆变成一个小方块/进度指示。
 */
@Composable
private fun ShutterButton(
    status: OcrStatus,
    enabled: Boolean,
    autoMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busy = status is OcrStatus.Recognizing
    val clickable = enabled && !busy && !autoMode
    // 自动模式由定时器驱动，按钮只做状态指示，所以调暗
    val contentAlpha = if (autoMode) 0.35f else 1f

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
private fun CardLine(text: String, hit: Boolean, note: String? = null) {
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text = if (hit) "✓ $text" else text,
            color = if (hit) XiaomiYellow else Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (note != null) {
            Text(
                text = "→ $note",
                color = XiaomiYellow.copy(alpha = 0.75f),
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
                            CardLine(text = match.rawText, hit = true, note = match.entry?.name)
                        }
                        HitDivider()
                    }

                    // 分界线之下：照常列出全部识别结果（命中的也在里面）
                    item.lines.take(CARD_ROWS).forEach { line ->
                        CardLine(text = line, hit = false)
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
            status.matches.isEmpty() -> "没识别到文字"
            status.hitCount > 0 ->
                "命中 ${status.hitCount} / ${status.matches.size}" +
                    if (status.fromCache) "（画面未变化）" else ""
            else -> "识别到 ${status.matches.size} 行，但都没命中表格"
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
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * 比对结果面板：逐行显示，命中的行打勾并标出命中的表格项。
 */
@Composable
private fun ResultPanel(
    matches: List<LineMatch>,
    autoMode: Boolean,
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
                    text = "没有识别到文字，试试靠近一点、让文字占满画面",
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

            // 自动模式下持续在拍，“重拍”没有意义
            if (!autoMode) {
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
    // 命中用小米相机那个黄色，和整体风格统一
    val hitColor = XiaomiYellow
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (match.isHit) hitColor.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = if (match.isHit) "✓" else "·",
            color = if (match.isHit) hitColor else Color.White.copy(alpha = 0.35f),
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
                    color = hitColor,
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
 */
@Composable
fun CameraPreview(
    captureExecutor: ExecutorService,
    onImageCaptureReady: (ImageCapture) -> Unit,
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

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var boundCameraProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            boundCameraProvider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(previewView.display.rotation)
                .build()

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
            onImageCaptureReady(imageCapture)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // 页面销毁时解绑，避免相机被占用/回调打到已销毁的 View 上
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
