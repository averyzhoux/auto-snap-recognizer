package com.example.recognizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

/** 缩略图宽度，避免把全分辨率 Bitmap（十几 MB）留在内存里 */
private const val THUMBNAIL_WIDTH = 160

/** 摄像头拍到的原始帧（已是给 ML Kit 用的方向），bitmap 是缩小过的缩略图。 */
private data class CapturedFrame(val bitmap: Bitmap, val rotationDegrees: Int)

/** 一次识别 + 比对的结果。 */
private data class MatchResult(val matches: List<LineMatch>, val fromCache: Boolean)

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

    // 默认自动
    var autoMode by remember { mutableStateOf(true) }

    // 同一时刻只允许一帧在识别；1 秒一帧时识别可能还没结束，靠它跳过这一拍
    val busy = remember { AtomicBoolean(false) }
    // 上一帧的“像素指纹”，用于判断画面是否变化
    var lastSignature by remember { mutableStateOf<Long?>(null) }
    var lastMatches by remember { mutableStateOf<List<LineMatch>?>(null) }

    // 相机还没绑定完成就拍照时会报错，记下时间让自动循环稍后重试。
    // 手动模式下没有循环接应，所以这种情况直接忽略，不弹错误。
    var transientErrorAt by remember { mutableStateOf<Long?>(null) }

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
                        val cached = lastMatches
                        val reuse = signature == lastSignature && cached != null

                        if (reuse) {
                            // 画面没变，直接复用上次的比对结果，省掉一次 ML Kit 调用
                            Log.d(TAG, "frame unchanged, reuse cached result")
                            lastSignature = signature
                            status = OcrStatus.Recognized(bitmap, cached, fromCache = true)
                            busy.set(false)
                        } else {
                            val thumb = bitmap.scaledToWidth(THUMBNAIL_WIDTH)
                            status = OcrStatus.Recognizing(thumb, cached)
                            recognize(textRecognizer, matcher, bitmap, rotationDegrees) { result ->
                                lastSignature = signature
                                lastMatches = result
                                status = OcrStatus.Recognized(thumb, result, fromCache = false)
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
    LaunchedEffect(autoMode, imageCapture, transientErrorAt) {
        val useCase = imageCapture ?: return@LaunchedEffect
        if (!autoMode) return@LaunchedEffect

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
            // 左侧：最近一帧缩略图（没有就是空占位，保证快门居中）
            Box(
                modifier = Modifier.size(THUMBNAIL_SLOT),
                contentAlignment = Alignment.Center
            ) {
                val bitmap = status.bitmapOrNull()
                if (bitmap != null) Thumbnail(bitmap = bitmap)
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
    onResult: (List<LineMatch>) -> Unit
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

            onResult(matches)
        }
        .addOnFailureListener { error ->
            Log.e(TAG, "OCR failed", error)
            // 识别失败不算致命：自动模式下下一帧会重试，这里保留上一批结果
            onResult(emptyList())
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

/** 左下角最近一帧缩略图。 */
@Composable
private fun Thumbnail(bitmap: Bitmap, modifier: Modifier = Modifier) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "最近一帧",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(THUMBNAIL_SLOT)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
    )
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
