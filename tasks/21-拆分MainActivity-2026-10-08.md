# 21 · 拆分 MainActivity.kt（①–⑧：纯搬家）

| | |
|---|---|
| **日期** | 2026-10-08 |
| **状态** | ✅ 完成；逐行无损已验证，真机启动通过。相机预览待人工确认 |
| **涉及文件** | `MainActivity.kt` 拆成 9 个文件（见下表） |

## 目标

`MainActivity.kt` 涨到 2554 行时讨论过怎么拆，结论是先把**已经独立、只是住错文件**的那部分
机械搬走（①–⑧），把真正难的 `CameraOcrScreen`（690 行）单独暴露出来，再决定 ⑨ 怎么做。
本任务是 ①–⑧ 的执行记录。

## 诊断：两个问题

拆之前先量了一遍，发现混着两个性质完全不同的问题：

| 问题 | 规模 | 性质 |
|------|------|------|
| 25 个互相独立的 composable 挤在一个文件里 | 约 1850 行 | **只是住错文件**，依赖关系本来就干净 |
| `CameraOcrScreen()` 一个函数 | 690 行 | 17 个 `remember` 状态 + 8 个回调 lambda + 4 个 `LaunchedEffect` + 1 个 `DisposableEffect` + 18 处 `Screen.` 分支，把权限、相机绑定、帧管线、自动循环、数据集/相册操作和整棵布局树全揉在一起 |

所以 ①–⑧ 只解决第一个。**搬家能拿到 90% 的可读性收益，只要 20% 的风险**；第二个留给 ⑨。

## 拆完的结果

| 新文件 | 行数 | 内容 |
|--------|------|------|
| `MatchColors.kt` | 30 | `XiaomiYellow` `MatchGreen` `MatchBlue` `matchColor()` |
| `CapturePipeline.kt` | 225 | `TAG` / 各种间隔常量 / `THUMBNAIL_*` / `Pipeline` 枚举 / `CapturedFrame` / `MatchResult` / `OcrStatus` / `signature()` / `scaledTo*()` / `recognize()` / `saveToGallery()` / `bitmapOrNull()` / `matchesOrNull()` |
| `CameraControls.kt` | 222 | `SHUTTER_SIZE` `THUMBNAIL_SLOT` / `ModeSwitch` `ShutterButton` `PipelineModeRow` `Thumbnail` `PermissionRationale` |
| `CameraPreview.kt` | 159 | `CameraPreview` / `openAppSettings` / `isCameraNotReady` |
| `GalleryScreen.kt` | 446 | `CARD_ROWS` / `GalleryScreen` `HitDivider` `CardLine` `GalleryCard` `rememberGalleryBitmap` `ImagePlaceholder` `GalleryDetailScreen` |
| `DatasetScreen.kt` | 433 | `DatasetChip` `DatasetRow` `RowAction` `DatasetScreen` `DatasetEditScreen` `EntryMarkRow` `MarkBox` |
| `ImportDialogs.kt` | 206 | `PendingImport` / `PasteDialog` `ImportPreviewDialog` `queryDisplayName` |
| `ResultPanel.kt` | 208 | `StatusBanner` `ResultPanel` `MatchRow` |
| **`MainActivity.kt`** | **781** | 只剩 `MainActivity`（16 行）+ `Screen` 枚举 + **`CameraOcrScreen`（697 行）** |

> **和当初的预估有出入，这里更正一下**：讨论时说的「做完 ①–⑧ 后 MainActivity 约 60 行」
> 是错的——那个数字把 `CameraOcrScreen` 也算进搬走的部分了。
> ⑧ 里的 `RecognizerApp.kt`（根 composable + `Screen` 枚举 + 导航）**做不出来**，
> 因为根 composable 就是 `CameraOcrScreen` 本身，`when (screen)` 导航也写在它内部，
> 拆不开。所以 `RecognizerApp.kt` 只能等 ⑨ 一起做。
> 真正做完 ①–⑧ 是 **2554 → 781 行**，其中 697 行是那一个函数。

## 怎么搬的

写了脚本按**顶层声明边界**切，而不是手抄。步骤：

1. 解析出所有顶层声明及其 KDoc 起始行（要跳过声明和 KDoc 之间的注解行，
   否则 `@Composable` 会把 KDoc 和函数切开，`Screen` 的 KDoc 就是这么差点丢的）
2. 按「声明 → 目标文件」分组；没分到组的留在 `MainActivity.kt`
3. 顶层 `private ` → `internal `（`private` 顶层是**文件私有**，跨文件就看不见了）
4. 每个文件重算 import
5. **用 git 里的原版做逐行校验**（见下）

### 两个只有靠工具才发现的点

**① KDoc 被注解挡住。** 第一次解析时 `@Composable` 当成独立声明，
导致前一个函数的 `end` 吞掉了后一个函数的 KDoc。跳过注解行重算才正确。

**② import 自动筛选不能只按名字匹配。** 两轮才收敛：

- 第一轮按「简单名出现在正文里」选，全塞进去（`MainActivity` 110 个 import 一个没少）
- 第二轮改成「小写名字（基本都是函数/属性扩展）必须看到调用 `name(` 或 `.name`」，
  结果**误删了 `remember`**——它用的是尾随 lambda：`remember { ... }`，没有括号
- 第三轮把 `name {` 也算上，`derivedStateOf { }`、`launch { }`、`setContent { }` 同理；
  另外 `mutableStateListOf<DatasetMeta>()` 是**泛型调用**，要额外允许 `name<`

这三类写法漏一个就是一片 `Unresolved reference`，靠编译器一个个揪出来。

## 验证

### 1. 逐行无损（最硬的一条）

从 git 取出拆分前的 `MainActivity.kt`（2554 行），和拆分后 9 个文件全部内容做比对：
去掉 `package` / `import` / 空行，并把行首 `private ` 归一成 `internal `，然后比**行多重集**。

```text
原版有效行 2274 → 拆分后 2274
丢失: 无
多出: 无
结论: ✅ 逐行无损，只有 private→internal 与文件归属变化
```

第一次跑这条校验时揪出了 1 行差异：`Screen` 的 KDoc `/** 当前显示哪一屏 */` 被前一步
修重复块时的行号偏移吃掉了，已补回。**没有这个校验就漏过去了**——它不影响编译，
只是悄悄少一行注释。

### 2. 声明完整性

56 个顶层声明，拆分前后**名称与数量完全一致**，无丢失、无重复。

### 3. 构建与真机

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` —— BUILD SUCCESSFUL，33 个单测全绿
- `adb install -r` 覆盖安装成功
- 启动日志正常，无 `FATAL EXCEPTION`：

```text
Recognizer: dataset loaded: '宝马展' 102 entries
Recognizer: gallery loaded 71 items from disk
```

### 4. 真机跑通（拆分后冷启动实测）

| 检查 | 结果 |
|------|------|
| 冷启动（`force-stop` 后重启） | 无 `FATAL EXCEPTION`，crash buffer **0 行** |
| 相机预览 | ✅ 实时画面正常（截图里是键盘，OCR 读到了 `F8`–`F12`） |
| 拍照 → 落盘 | ✅ 相册从 71 张涨到 75 张 |
| 相册详情页 | ✅ 正常渲染，精确命中有绿、近似有黄（`JS-3814 近似 83%`、`TA-8757A/B 近似 75%`） |
| 「流式分析」管线 | ✅ 8 帧，`pipeline=流式分析 in=1440x1080 ocr=MediaImage rot=90` |
| 「小图直出」管线 | ✅ `pipeline=小图直出 in=1200x1600 ocr=MediaImage rot=0`，说明 `ResolutionSelector` 生效 |
| 内存 | PSS 264MB / Java heap 28MB，长时间运行没有异常增长（ML Kit native 库本来就大） |

重点是 **`CameraPreview.kt` 的搬迁确认无问题**——相机能绑上、能推帧、能拍照。

### 还没覆盖到的

- [ ] 「标准 / 省内存 / 降采样」三条管线本次日志里没出现（清 logcat 后只点到流式分析和小图直出）
- [ ] 数据集编辑页的蓝色标记（相册那张照片里全是绿/黄，没看到蓝——说明还没勾过项，或者勾的项不在这张里）

## 备注

- 没有建子包（`camera/` `ui/`），9 个文件都在 `dev.averyzhoux.recognizer` 同一包下。
  同包意味着彼此引用**不需要 import**，搬迁风险小很多；文件多了再分也不迟
- 所有跨文件可见的东西现在是 `internal` 而不是 `private`，模块边界没放宽
- 搬完 `MainActivity.kt` 里剩下的 `CameraOcrScreen` 是 697 行的单个函数，
  ⑨ 要处理的就是它（方案 A 拆 UI 区块 / 方案 B 抽 state holder，讨论里倾向 B）
