package com.example.recognizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "Recognizer"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RecognizerTheme {
                CameraOcrScreen()
            }
        }
    }
}

/** 整体流程的状态机：拍照 -> OCR -> 出文字。 */
sealed interface OcrStatus {
    data object Idle : OcrStatus

    /** 已抓到帧，正在识别 */
    data class Recognizing(val bitmap: Bitmap) : OcrStatus

    /**
     * 识别完成。[matches] 是逐行跟本地表格比对的结果，
     * 命中的行 UI 上会高亮 + 打勾。
     */
    data class Recognized(
        val bitmap: Bitmap,
        val text: String,
        val lines: List<String>,
        val matches: List<LineMatch>
    ) : OcrStatus {
        val hitCount: Int get() = matches.count { it.isHit }
    }

    data class Failed(val message: String) : OcrStatus
}

/**
 * 相机页面：权限申请 -> 预览 -> 拍照 -> 英文 OCR -> 跟表格比对。
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
    // 拉丁文模型：英文 + 数字，模型体积比中文模型小很多
    val textRecognizer: TextRecognizer = remember {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    // 比对引擎：把 OCR 的每一行跟本地表格比一遍
    val matcher = remember { OcrMatcher(Dataset.entries) }
    DisposableEffect(Unit) {
        onDispose {
            textRecognizer.close()
            captureExecutor.shutdown()
        }
    }

    // 只在相机绑定成功后才非空；拍照按钮靠它判断“相机就绪”
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var status by remember { mutableStateOf<OcrStatus>(OcrStatus.Idle) }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            captureExecutor = captureExecutor,
            onImageCaptureReady = { imageCapture = it },
            modifier = Modifier.fillMaxSize()
        )

        val capture = imageCapture
        ShutterButton(
            status = status,
            enabled = capture != null,
            onClick = {
                val useCase = capture ?: return@ShutterButton
                useCase.takePicture(
                    captureExecutor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            // ★ 核心坑位：toBitmap() 返回的是“传感器方向”的原始像素，
                            //   必须把 rotationDegrees 一路传给 ML Kit，否则图片是躺着的，
                            //   中文识别结果会变成乱码或直接识别不到。
                            val rotationDegrees = image.imageInfo.rotationDegrees
                            val bitmap = image.toBitmap()
                            image.close()
                            Log.d(
                                TAG,
                                "captured ${bitmap.width}x${bitmap.height}, rotation=$rotationDegrees"
                            )
                            status = OcrStatus.Recognizing(bitmap)
                            recognize(textRecognizer, matcher, bitmap, rotationDegrees) { result ->
                                status = result
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "takePicture failed", exception)
                            status = OcrStatus.Failed(exception.message ?: "拍照失败")
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )

        StatusBanner(
            status = status,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp)
        )

        val bitmap = status.bitmapOrNull()
        if (bitmap != null) {
            Thumbnail(
                bitmap = bitmap,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 24.dp, bottom = 32.dp)
            )
        }

        val recognized = status as? OcrStatus.Recognized
        if (recognized != null) {
            ResultPanel(
                recognized = recognized,
                onRetake = { status = OcrStatus.Idle },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp, start = 16.dp, end = 16.dp)
            )
        }
    }
}

/**
 * 调 ML Kit 识别，结果通过 [onResult] 回传。
 *
 * 回调发生在 ML Kit 自己的线程上，但赋值的是 Compose 的 snapshot state，
 * 所以直接写就行，不需要手动切主线程。
 */
private fun recognize(
    recognizer: TextRecognizer,
    matcher: OcrMatcher,
    bitmap: Bitmap,
    rotationDegrees: Int,
    onResult: (OcrStatus) -> Unit
) {
    // 第二个参数就是旋转角度，交给 ML Kit 摆正
    val inputImage = InputImage.fromBitmap(bitmap, rotationDegrees)

    recognizer.process(inputImage)
        .addOnSuccessListener { visionText ->
            val lines = visionText.textBlocks
                .flatMap { block -> block.lines }
                .map { line -> line.text }
                .filter { it.isNotBlank() }

            // 逐行跟本地表格比对
            val matches = matcher.matchAll(lines)
            val hits = matches.count { it.isHit }

            Log.d(TAG, "recognized ${lines.size} lines, $hits hit the dataset")
            matches.forEach { match ->
                Log.d(
                    TAG,
                    "  [${if (match.isHit) "HIT" else " - "}] " +
                        "'${match.rawText}' -> ${match.entry?.name ?: "-"} " +
                        "(via '${match.matchedOn ?: "-"}', sim=${"%.2f".format(match.similarity)})"
                )
            }

            onResult(OcrStatus.Recognized(bitmap, visionText.text, lines, matches))
        }
        .addOnFailureListener { error ->
            Log.e(TAG, "OCR failed", error)
            onResult(OcrStatus.Failed(error.message ?: "文字识别失败"))
        }
}

private fun OcrStatus.bitmapOrNull(): Bitmap? = when (this) {
    is OcrStatus.Recognizing -> bitmap
    is OcrStatus.Recognized -> bitmap
    else -> null
}

/** 底部圆形拍照按钮。 */
@Composable
private fun ShutterButton(
    status: OcrStatus,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busy = status is OcrStatus.Recognizing
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = CircleShape,
        modifier = modifier.size(76.dp)
    ) {
        Text(
            text = if (busy) "识别中" else "拍照",
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/** 拍到的帧缩略图：用来肉眼确认“真的抓到了这一帧”。 */
@Composable
private fun Thumbnail(bitmap: Bitmap, modifier: Modifier = Modifier) {
    // 缩略图不需要原图，先缩放，省内存
    val previewBitmap = remember(bitmap) {
        val targetWidth = 200
        val height = (bitmap.height * (targetWidth.toFloat() / bitmap.width)).toInt()
        Bitmap.createScaledBitmap(bitmap, targetWidth, height, true)
    }
    Image(
        bitmap = previewBitmap.asImageBitmap(),
        contentDescription = "刚拍到的图片",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(2.dp, Color.White, RoundedCornerShape(12.dp))
    )
}

/** 顶部状态条。 */
@Composable
private fun StatusBanner(status: OcrStatus, modifier: Modifier = Modifier) {
    val text = when (status) {
        OcrStatus.Idle -> "对准目标，点下方按钮拍照识别"
        is OcrStatus.Recognizing -> "正在识别文字…"
        is OcrStatus.Recognized -> when {
            status.lines.isEmpty() -> "没识别到文字"
            status.hitCount > 0 -> "命中 ${status.hitCount} / ${status.lines.size} 项"
            else -> "识别到 ${status.lines.size} 行，但都没命中表格"
        }
        is OcrStatus.Failed -> "失败：${status.message}"
    }
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
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
 * 比对结果面板：逐行显示，命中的行前面打勾并标出命中了表格里的哪一项。
 *
 * 这是第 4 步的核心产出——“对比到的就先提示一下”。
 */
@Composable
private fun ResultPanel(
    recognized: OcrStatus.Recognized,
    onRetake: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "命中 ${recognized.hitCount} / ${recognized.matches.size}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )

            if (recognized.matches.isEmpty()) {
                Text(
                    text = "没有识别到文字，试试靠近一点、让文字占满画面",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    recognized.matches.forEach { match -> MatchRow(match) }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Button(onClick = onRetake) { Text("重拍") }
            }
        }
    }
}

/** 单行的比对结果：命中 = 绿色高亮 + 打勾，未命中 = 灰显。 */
@Composable
private fun MatchRow(match: LineMatch) {
    val hitColor = Color(0xFF3DDC84)
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
                // 拍照模式下用画质优先；追求速度可换 CAPTURE_MODE_MINIMIZE_LATENCY
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
