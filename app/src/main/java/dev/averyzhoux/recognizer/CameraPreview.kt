package dev.averyzhoux.recognizer

import android.content.Context
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutorService

/**
 * 相机预览 + 拍照用例。
 *
 * 关键点：
 * - [ProcessCameraProvider] 把 Preview / ImageCapture 绑定到 LifecycleOwner（这里是 Activity），
 *   CameraX 自动处理 onStart/onStop/onDestroy，不用手写开关相机。
 * - [PreviewView] 是传统 View，用 [AndroidView] 包进 Compose。
 * - [ImageCapture] 的 targetRotation 跟随屏幕旋转，拍出来的图才是正的。
 *
 * 流式（[Pipeline.Analysis]）用 [ImageAnalysis] 替代 ImageCapture：
 * KEEP_ONLY_LATEST + 上一帧 close 之后才推下一帧，所以是「识别完立刻拿最新的一帧」，
 * **不看**手动/自动开关——进了这条管线就一直处理。
 */
@Composable
fun CameraPreview(
    captureExecutor: ExecutorService,
    pipeline: Pipeline,
    /** 这一帧要不要交给上层处理（见下面 ★ 的说明） */
    deliverFrames: Boolean,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onFrame: (ImageProxy) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // ★ deliverFrames 是普通参数，而 analyzer 会被 DisposableEffect 捕获一次并长期持有
    //   （它的 key 只有 lifecycleOwner 和 pipeline，翻页时不会重建）。
    //   直接读参数会把「建 analyzer 那一刻」的布尔值冻进去，
    //   所以要走 rememberUpdatedState 读**当前值**。
    val currentDeliverFrames by rememberUpdatedState(deliverFrames)
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
                // D 流式：由相机推帧，KEEP_ONLY_LATEST 保证不排队堆积
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
                            // 流式就是「一直处理」：手动/自动开关对这条管线不生效。
                            //
                            // ★ 但翻相册 / 看数据集时要停下来。相册和数据集都是同一个
                            //   Activity 里的 Compose 覆盖层，**不会触发 onStop**，
                            //   所以 CameraX 不会解绑，analyzer 会一直推帧——
                            //   结果就是「一边翻相册，一边还在识别、还往相册里塞新照片」。
                            //   ImageCapture 那几条管线不用管：它们的自动循环有 screen 判断，
                            //   手动模式又点不到快门。
                            if (currentDeliverFrames) {
                                onFrame(image)
                            } else {
                                // 必须关，否则相机管线会卡住
                                image.close()
                            }
                        }
                    }

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } else {
                // A/B/C/基础：ImageCapture。C 走 ResolutionSelector 让 HAL 直接出小图
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

internal fun Context.openAppSettings() {
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
internal fun isCameraNotReady(exception: ImageCaptureException): Boolean {
    val message = exception.message ?: return false
    return message.contains("Not bound to a valid Camera", ignoreCase = true) ||
        message.contains("Camera is closed", ignoreCase = true)
}
