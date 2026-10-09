# AGENTS.md

## 项目
Android 相机 OCR（CameraX + ML Kit 拉丁文 + 本地表格比对 + 应用内相册）。
Kotlin / Compose / 纯本地存储 / 无网络。

## 构建与验证
- 编译：JAVA_HOME=<AS 自带 jbr> ./gradlew :app:assembleDebug
- 看错误：... | grep -E "^e:|BUILD"
- 相机相关改动必须真机验证。
- **不要用 monkey 测试**（用户明确要求）：它在真机上乱点，设备私有目录里有真实
  数据集和相册，有误删风险。`adb shell input` 在 MIUI 上被挡，所以真机点选
  **只能等待人去手动点击**，不要试图绕过去自动化。
- ai agnet 可以在重装软件后自动把 app 重启打开，但不能操作其他事情，免得看起来像木马入侵。
- ai agent 创建的临时文件和临时目录如非必要，都从 temp 目录下去构建，而不是 tmp。

## 硬约束（改代码前必读）
1. rotationDegrees 必须传给 ML Kit
2. ImageProxy 必须 close
3. autoMode 默认 false
4. 帧指纹命中时跳过 ML Kit 并复用结果
5. 大 Bitmap 及时 recycle；LruCache 挤出项不要 recycle
6. 相册只存 480px 预览图，不存原图
7. GalleryStore 的 ID 由存储层分配
9. 相册/详情用 BackHandler，切屏暂停自动拍摄

## 关键文件
- MainActivity.kt / GalleryStore.kt / OcrMatcher / Dataset

## 风格
- 注释写为什么，中文为主，坑位标 ★
- Compose 组件私有拆小，命名 XxxScreen/Card/Button
- I/O 显式 Dispatchers.IO

## 常见任务
- 加 UI：对应 Screen/Card，用现有颜色常量
- 改识别：recognize()，回调 (lines, matches)，同步 saveToGallery
- 加存储字段：buildJson/parseJson，用 optXxx 兼容旧数据

## 不要做
- 不加网络、不加大型依赖
- 不删 rotationDegrees / image.close() / bitmap.recycle()
- **不要删除 `tmp/` 里的任何内容**（用户明确要求）：`tmp/` 被 `.gitignore` 忽略