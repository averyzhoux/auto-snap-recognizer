# 27 · 版本号 / 版本名统一到 build.gradle.kts

| | |
|---|---|
| **日期** | 2026-10-08 |
| **状态** | ✅ 已完成，构建 + 42 个单测通过；workflow 待真跑（要新 tag） |
| **涉及文件** | `app/build.gradle.kts`、`.github/workflows/release.yml`、`README.md`、`tasks/14` |

## 目标

> 需求原话：「app 打包的版本号是从 build.gradle.kts 读取，但是版本名同样也是呀。」

**版本号和版本名只有一处来源：`app/build.gradle.kts` 的 `defaultConfig`。**
CI 不从 tag 推、也不用 `-P` 注入。

```kotlin
defaultConfig {
    versionCode = 300                      // 给系统判断新旧用，单调递增
    versionName = "v0.3.0-Hephaestus"      // 给人看的，系统「应用信息」页显示的就是它
}
```

发新版：改这两行 → 提交 → 打 tag。

## 之前是什么样，为什么会歪

原来是**两套值**：

```kotlin
// 旧：CI 注入，本地兜底
val injectedVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()
versionCode = injectedVersionCode ?: 3              // ← 本地写死 3
versionName = injectedVersionName ?: "v0.2.1-Athena"
```

```yaml
# 旧：CI 从 tag 算出来，再 -P 注入
- run: |
    IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION"
    CODE=$(( MAJOR * 10000 + MINOR * 100 + PATCH ))
```

三个坑，第 1 个是这轮真踩到的：

1. **本地默认值和 CI 算出来的差一个量级**：本地 `3`、CI 从 tag 算是 `201`/`300`。
   装过 CI 版之后再 `install -r` 本地版会被 Android 拒
   （`INSTALL_FAILED_VERSION_DOWNGRADE`），唯一的补救是卸载重装——
   数据集和相册都在私有目录里，一卸就没
2. **`major*10000 + minor*100 + patch` 的位宽会撞**：`patch` 只占两位，
   一过 99 就进位到 `minor`——`v0.1.100` 和 `v0.2.0` 算出**同一个 versionCode 200**
3. **代号传不进发布版**：CI 解析时 `PATCH="${PATCH%%[!0-9]*}"` 会把 `-Hephaestus` 剥掉，
   所以「这一版叫什么」只在本地构建里看得到

## 改了什么

**`app/build.gradle.kts`**：删掉 `injectedVersionName` / `injectedVersionCode` 两个 provider，
两个值直接写死。

**`release.yml`**：
- 删掉 `Compute version` 这一步（连同 `-PversionName` / `-PversionCode` 注入）
- 删掉 `workflow_dispatch` 的 `version` 输入（填了也没用了）
- 新增 `Read version from the built APK`：**构建之后**用 `aapt2 dump badging` 把
  打进包里的 `versionName` / `versionCode` 读出来，只用于命名 artifact 和写 Release 说明

> 为什么不直接用 tag 名？——因为万一 tag 和 `build.gradle.kts` 对不上，
> Release 上显示 tag 会**掩盖**这个不一致；显示包里真实的值则会**暴露**它。

**屏幕那行**：`Recognizer<versionCode> <versionName>`，例如 `Recognizer300 v0.3.0-Hephaestus`。
两个都显示——它们回答的不是同一个问题：前者是系统判断新旧用的整数，后者是人认得出的「哪一版」。

## 删掉了一个测试

`VersionCodeTest` 是上一轮为「tag → versionCode 的计算」写的（解析 `v300`、
拒绝旧式 semver、断言 versionName 等于 versionCode）。**那套计算已经不存在了**，
测的对象没了，留着只会误导，所以删掉。

现在两个值都是 `build.gradle.kts` 里的字面量，没有计算、没有跨文件一致性要求，
没有值得单测的东西。真要防的话，该防的是「tag 和 build.gradle.kts 漂移」——
那在 CI 里比 tag 和 APK 里的值更合适，目前没做（release 说明里会显示真实值，
漂了一眼能看出来）。

单测数：45 → **42**。

## 验证

- `./gradlew :app:testDebugUnitTest :app:assembleDebug` —— 构建通过，42 个单测全绿
- 生成的 `BuildConfig`：`VERSION_CODE = 300` / `VERSION_NAME = "v0.3.0-Hephaestus"`
  （改之前是 `300` / `"300"`——版本名那一栏在系统「应用信息」页会显示成一个光秃秃的数字）
- workflow 真跑：要等下一个 tag

## 备注

- `tasks/14-发布流程` 里还留着旧的 `major*10000 + minor*100 + patch` 说明，
  加了指向本文件的提示，没改原文（那是当时的记录）
- 旧算法那两条坑（位宽撞位、代号被剥）现在都不存在了：版本号是手写的，
  tag 只当触发器和 git 标记用
