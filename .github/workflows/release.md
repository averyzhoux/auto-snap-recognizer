[中文](#识鉴相机-recognizer-v035) | [English](#recognizer-v035)

# 识鉴相机 Recognizer v0.3.5

拍照 → 自动识别 → 与本地表格比对 → 命中高亮。全部在手机本地完成，无需联网。

> ⚠️ 本安装包为**调试签名**，仅供自行安装体验，不适合上架或对外分发。  
> 覆盖安装前请确认签名一致；若需先卸载，请注意相册与数据集会一并清除。

- **版本名**：v0.3.5-Hephaestus
- **版本号**：305
- **系统要求**：Android 7.0 及以上
- **安装包**：`recognizer-v0.3.5-Hephaestus-debug.apk`

---

## 新增功能

- **取景页结果行尾「确认标记」按钮** — 取景页结果面板中，每行命中结果的行尾新增一个可点小方框：点一下把该项标记为「已识别」，再点一下取消。它**与数据集编辑页里的标记是同一个开关**，会立即落盘，下次扫到仍为蓝色，无需再进编辑页翻找。仅在手动模式、以及自动 / 流式被条件暂停停住时出现；连续识别时隐藏，避免手指刚瞄好的行被下一帧刷走。
- **命中颜色构成指示器** — 「命中 X / Y」右侧按颜色显示一个色点与数字（顺序为 **黄 → 蓝 → 绿**），说明本次命中里**模糊 / 已标记 / 精确**各几条。三组数字之和等于 X；某颜色为 0 时整组不显示。
- **条件暂停** — 设置面板新增开关：勾选颜色后，连续识别一旦扫到该颜色命中就**自动停下**（流式停止推帧，其余四种切回手动）。五种识别模式**各设各的、互不影响**，勾选状态重启后保留。
- **模式行颜色提示点** — 当前识别模式标签下方用小圆点标出该模式勾选了哪几个颜色，一眼看出它会因什么而停。

## 体验优化

- **「相册」更名为「快照」** — 相册纵览页顶栏的「相册 N 张」改为「快照 N 张」。这里记录的是**拍摄那一刻**的结果，不会因之后在数据集里修改标记而变化。
- **识别模式「标准」更名为「基础」** — 与它的实际行为更贴合。

## 修复

- 落地页手机示意图里的模式行一直被取景画面盖住（缺一个定位属性），从写进页面起就不可见，现已修复。

## 其他变更

- 版本号从 `304` / `v0.3.4-Hephaestus` 升级至 `305` / `v0.3.5-Hephaestus`。
- 新增单元测试 `EntryMarkTest`（10 个用例），覆盖标记翻转与结果实例重映射逻辑。
- `.gitignore` 补充 `temp/` 忽略规则，与 `AGENTS.md` 中临时目录约定保持一致。
- 任务文档新增 `33-取景页确认标记` 与 `34-命中颜色构成指示器`，`tasks/README.md` 同步。

## 已知特性

- 手动模式下按一次快门，若画面里有勾选颜色，「手动」圆钮会套上绿环并一直亮着，实际并未停下。这是已知的**显示误报**，不影响自动 / 流式下的实际暂停行为。

---

# Recognizer v0.3.5

Shoot → Auto-recognize → Match against local tables → Highlight hits. Everything runs on-device, no network required.

> ⚠️ This build is **debug-signed**, intended only for self-installation and evaluation. It is not suitable for distribution or publishing.  
> Before overwriting an existing install, make sure the signatures match. If you must uninstall first, note that the gallery and datasets will be removed as well.

- **Version name**: v0.3.5-Hephaestus
- **Version code**: 305
- **Requires**: Android 7.0 or later
- **Package**: `recognizer-v0.3.5-Hephaestus-debug.apk`

---

## New Features

- **Confirm-mark button at the end of each result row** — In the viewfinder result panel, every hit row now has a small tappable box at the end. Tap once to mark the entry as "recognized", tap again to unmark. It is **the same toggle as the mark in the dataset editor** and is persisted immediately, so the entry stays blue the next time it is scanned without digging through the editor. It appears only in manual mode and when auto / streaming capture is stopped by a conditional pause; it is hidden during continuous recognition so a row you are aiming at does not get swept away by the next frame.
- **Hit color composition indicator** — To the right of "Hits X / Y", a colored dot and number are shown per color (order: **yellow → blue → green**), indicating how many hits were **fuzzy / already marked / exact**. The three numbers add up to X; a color with zero hits is omitted entirely.
- **Conditional pause** — A new toggle in the settings panel: once a color is checked, continuous recognition **stops automatically** when a hit of that color appears (streaming halts frame push; the other four modes fall back to manual). Each of the five recognition modes has its **own independent setting**, and the checked state survives a restart.
- **Mode-row color hint dots** — Small dots under the current recognition mode label show which colors that mode has checked, so you can see at a glance what will stop it.

## Improvements

- **"Gallery" renamed to "Snapshot"** — The gallery overview header now reads "Snapshot N photos" instead of "Gallery N photos". It records the result **at capture time** and does not change when marks are later modified in the dataset.
- **Recognition mode "Standard" renamed to "Basic"** — A better fit for its actual behavior.

## Bug Fixes

- The mode row in the landing-page phone mockup was always covered by the viewfinder image (a missing positioning property), invisible since it was first written. Fixed.

## Chores

- Version bumped from `304` / `v0.3.4-Hephaestus` to `305` / `v0.3.5-Hephaestus`.
- Added unit tests `EntryMarkTest` (10 cases) covering mark toggling and result-instance remapping logic.
- `.gitignore` now ignores `temp/`, consistent with the temporary-directory convention in `AGENTS.md`.
- Added task docs `33-取景页确认标记` and `34-命中颜色构成指示器`, and synced `tasks/README.md`.

## Known Issues (not fixing for now)

- In manual mode, pressing the shutter once while a checked color is on screen causes the "Manual" round button to keep a green ring lit, even though nothing was actually stopped. This is a known **display false-positive** and does not affect the actual pause behavior in auto / streaming modes.