package dev.averyzhoux.recognizer

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/**
 * 管线设置面板那些参数的落盘存储。
 *
 * 为什么要存：面板上的分析间隔 / 降采样长边 / 相册保存间隔 / 只存命中，
 * 以前只活在内存里——切一下管线、或者杀掉 App 重进，全回默认值。
 * 调好的参数下次开机还在，才算是「设置」。
 *
 * **是整个 App 一份，不按管线分**：面板里那些字段本来就是一条数据类
 * （[PipelineSettings]），只是不同管线显示其中几项。按管线存反而要引入
 * `Map<Pipeline, PipelineSettings>`，读取点全得跟着改，收益却只是
 * 「换个模式参数也跟着换」——目前没这个需求。
 *
 * 落盘位置（Android 按名字自动建，不用自己 mkdir）：
 * ```
 * /data/data/dev.averyzhoux.recognizer/shared_prefs/pipeline_settings.xml
 * ```
 * 和数据集 / 相册放在 `filesDir` 下的手写 JSON 不同：那边是列表型数据，
 * 得自己设计结构；这里就 5 个标量，`SharedPreferences` 正合适，
 * 也省得再写一套「文件损坏怎么办」的回退逻辑。
 */
internal class PipelineSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 落盘用的**单线程**队列。
     *
     * `commit()` 是同步写磁盘，放在 Compose 的点击回调里就是写在 UI 线程上；
     * 也不能直接甩给 `Dispatchers.IO`——那个池是多线程的，连点两下标签
     * 两次写盘会并发，落盘顺序不保证，最后留在磁盘上的可能是先点的那次。
     * 单线程串行，既离开 UI 线程，又保证「最后一次点的赢」。
     */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "pipeline-settings-io")
    }

    // ---------- 读 ----------

    /**
     * 读回全部设置。
     *
     * 每个字段的兜底值**直接取 [PipelineSettings] 里声明的默认值**，
     * 而不是在这里再抄一遍数字：以后调默认值、或者加新字段，
     * 只要在数据类上改一行，这里自动跟上，旧版本升级过来也能读
     * （`SharedPreferences` 缺 key 就返回传进去的默认值，不用做版本迁移）。
     */
    fun load(): PipelineSettings = PipelineSettings(
        analysisIntervalMs = prefs.getLong(KEY_ANALYSIS_INTERVAL_MS, 0L),
        downscaleLongEdge = prefs.getInt(KEY_DOWNSCALE_LONG_EDGE, Pipeline.DOWNSCALE_LONG_EDGE),
        captureIntervalMs = prefs.getLong(KEY_CAPTURE_INTERVAL_MS, AUTO_CAPTURE_INTERVAL_MS),
        gallerySaveIntervalMs = prefs.getLong(KEY_GALLERY_SAVE_INTERVAL_MS, GALLERY_SAVE_INTERVAL_MS),
        saveHitsOnly = prefs.getBoolean(KEY_SAVE_HITS_ONLY, false)
    )

    // ---------- 写 ----------

    /**
     * 整份覆盖。5 个标量不值得做增量更新。
     *
     * ★ 用 `commit()` 而不是 `apply()`：
     *   `apply()` 是异步落盘，得等进程还活着才能把改动刷出去。这里要的正是
     *   「重启之后还在」，所以老老实实同步写——但同步发生在 [io] 那个后台线程上，
     *   调用方（点击回调）立刻返回，UI 不会被磁盘卡住。
     */
    fun save(settings: PipelineSettings) {
        io.execute {
            val ok = prefs.edit()
                .putLong(KEY_ANALYSIS_INTERVAL_MS, settings.analysisIntervalMs)
                .putInt(KEY_DOWNSCALE_LONG_EDGE, settings.downscaleLongEdge)
                .putLong(KEY_CAPTURE_INTERVAL_MS, settings.captureIntervalMs)
                .putLong(KEY_GALLERY_SAVE_INTERVAL_MS, settings.gallerySaveIntervalMs)
                .putBoolean(KEY_SAVE_HITS_ONLY, settings.saveHitsOnly)
                .commit()
            // 写失败只可能是磁盘满之类的极端情况：值在内存里已经生效，
            // 下次启动会回默认值，不拦着用户继续用，留个日志够查了。
            if (!ok) Log.w(TAG, "管线设置落盘失败，本次修改重启后会丢")
        }
    }

    private companion object {
        const val TAG = "PipelineSettingsStore"
        const val PREFS_NAME = "pipeline_settings"

        // key 用下划线小写：这些名字会出现在 XML 里，将来拿 adb 看的时候好认
        const val KEY_ANALYSIS_INTERVAL_MS = "analysis_interval_ms"
        const val KEY_DOWNSCALE_LONG_EDGE = "downscale_long_edge"
        const val KEY_CAPTURE_INTERVAL_MS = "capture_interval_ms"
        const val KEY_GALLERY_SAVE_INTERVAL_MS = "gallery_save_interval_ms"
        const val KEY_SAVE_HITS_ONLY = "save_hits_only"
    }
}
