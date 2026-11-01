// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.compose.ui.graphics.toArgb
import com.ilyskyo.blancall.util.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 外观偏好的一次快照。
 *
 * 所有字段都参与「一键备份」：早前这些值分别躺在 `theme_prefs` / `app_prefs`
 * 两个 SharedPreferences 里，而备份包只收 filesDir 下的 json ——
 * 换机恢复后主题、强调色、米黄底、玻璃开关全部回默认。
 */
data class AppearanceSnapshot(
    /** 明暗模式：跟随系统 / 浅 / 深 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 预设强调色下标（[AccentPresets]） */
    val accentIndex: Int = 0,
    /** 自定义强调色（ARGB），0 = 未设置 */
    val customArgb: Int = 0,
    /** 是否使用自定义强调色（覆盖 [accentIndex]） */
    val useCustomAccent: Boolean = false,
    /** 浅色模式是否用米黄（纸张）底 */
    val beigeBackground: Boolean = false,
    /** 液态玻璃是否吸取主题色（关闭后玻璃保持中性白） */
    val glassTint: Boolean = true,
    /** 界面字体：serif / sans / xwwk */
    val uiFont: String = FONT_SERIF,
    /** 界面字号缩放（0.85..1.30） */
    val uiFontScale: Float = 1f,
    /** 跟随系统取色（Android 12+ Material You） */
    val materialYou: Boolean = false,
    /**
     * 页面底色是否与卡片同色（默认 true = 旧版观感）。
     *
     * 关掉后底色比卡片深一档，卡片靠明度差立起来；开着时整页一张纸，靠描边区分层次。
     * 默认同色是给从旧版本升上来的用户留的 —— 升级不该先看到「整页变灰」。
     */
    val flatBackground: Boolean = true,
) {
    /** 实际生效的强调色预设：自定义色优先于下标。 */
    fun accentPreset(): AccentPreset {
        if (useCustomAccent && customArgb != 0) return accentPresetFromArgb(customArgb)
        return AccentPresets.getOrElse(accentIndex) { AccentPresets[0] }
    }

    /** 马卡龙 / 玻璃用的色相偏移。 */
    fun hueShift(): Float = hueShiftFor(accentPreset().primaryLight.toArgb())

    companion object {
        const val FONT_SERIF = "serif"
        const val FONT_SANS = "sans"
        const val FONT_XWWK = "xwwk"
    }
}

/**
 * 外观偏好的唯一权威来源。
 *
 * **落盘规则**：`filesDir/appearance.json` 是权威，SharedPreferences 只作旧版本迁移源。
 * 这样「一键备份」才带得走外观（见 [com.ilyskyo.blancall.data.backup.BackupManager.ROOT_DATA_FILES]），
 * 与 ReminderPrefs 的 exam_goal.json 同一套做法。
 *
 * **为什么不复用 AppPrefs**：主题要在 `setContent` 之前被启动屏读到，
 * 而 AppPrefs 的 init 在 Application.onCreate 里；把外观单独拿出来，
 * 可以保证「先读外观 → 再决定启动底色」这条时序不被 AI 配置等重初始化拖慢。
 */
object AppearanceStore {

    private lateinit var prefs: SharedPreferences
    private lateinit var legacyPrefs: SharedPreferences
    private lateinit var file: File

    private val _state = MutableStateFlow(AppearanceSnapshot())
    val state: StateFlow<AppearanceSnapshot> = _state.asStateFlow()

    // 兼容旧调用点的投影流（ThemeManager / 各页面沿用原名字，避免大范围改签名）
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _accentIndex = MutableStateFlow(0)
    val accentColorFlow: StateFlow<Int> = _accentIndex.asStateFlow()

    private val _beige = MutableStateFlow(false)
    val lightBeigeBackgroundFlow: StateFlow<Boolean> = _beige.asStateFlow()

    fun init(context: Context) {
        val app = context.applicationContext
        prefs = app.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        legacyPrefs = app.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)
        // 字面量必须出现在 File(filesDir, "…") 里：LearningDataFileCensusTest 按源码文本
        // 反查「清单里的每个文件名都真的有人写」，写成常量会判成陈旧条目。
        file = File(app.filesDir, "appearance.json")
        val fromDisk = runCatching { read() }.getOrNull()
        val snapshot = fromDisk ?: migrateFromPrefs().also { write(it) }
        publish(snapshot)
    }

    /** 备份恢复后重读（[com.ilyskyo.blancall.data.backup.LearningDataReloader] 调用）。 */
    fun reload() {
        if (!::file.isInitialized) return
        val snapshot = runCatching { read() }.getOrNull() ?: return
        publish(snapshot)
    }

    fun update(transform: (AppearanceSnapshot) -> AppearanceSnapshot) {
        apply(transform, persist = true)
    }

    /**
     * 只改内存、不落盘。
     *
     * 滑杆拖动 / 色环拖动是**每帧**回调，走 [update] 会在一次拖动里写上百次盘
     * （[AtomicFiles] 每次 fsync + 备份轮换 + rename），既卡帧又伤闪存。
     * 结束动作（手指抬起 / 面板关闭）再调 [flush] 落一次。
     */
    fun preview(transform: (AppearanceSnapshot) -> AppearanceSnapshot) {
        apply(transform, persist = false)
    }

    /** 把内存里的当前值落盘一次。 */
    fun flush() {
        if (::file.isInitialized) write(_state.value)
    }

    private fun apply(transform: (AppearanceSnapshot) -> AppearanceSnapshot, persist: Boolean) {
        val next = transform(_state.value).normalized()
        publish(next)
        if (persist) write(next)
    }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }

    fun setAccentIndex(index: Int) = update {
        it.copy(accentIndex = index.coerceIn(0, AccentPresets.size - 1), useCustomAccent = false)
    }

    fun setCustomAccent(argb: Int) = update {
        it.copy(customArgb = argb or (0xFF shl 24), useCustomAccent = true)
    }

    /** 与旧 ThemeManager 同语义的三态轮换（跟随 → 浅 → 深）。 */
    fun toggleTheme(): ThemeMode {
        val next = when (_state.value.themeMode) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
        setThemeMode(next)
        return next
    }

    // ── 序列化 ──

    private fun publish(s: AppearanceSnapshot) {
        _state.value = s
        _themeMode.value = s.themeMode
        _accentIndex.value = s.accentIndex
        _beige.value = s.beigeBackground
    }

    private fun AppearanceSnapshot.normalized(): AppearanceSnapshot = copy(
        accentIndex = accentIndex.coerceIn(0, (AccentPresets.size - 1).coerceAtLeast(0)),
        uiFont = if (uiFont in setOf(AppearanceSnapshot.FONT_SERIF, AppearanceSnapshot.FONT_SANS, AppearanceSnapshot.FONT_XWWK)) uiFont else AppearanceSnapshot.FONT_SERIF,
        uiFontScale = uiFontScale.coerceIn(MIN_SCALE, MAX_SCALE),
    )

    private fun read(): AppearanceSnapshot? {
        if (!::file.isInitialized || !file.isFile) return null
        return decode(file.readText())
    }

    private fun write(s: AppearanceSnapshot) {
        runCatching { AtomicFiles.writeTextAtomic(file, encode(s)) }
        // 写盘失败不该阻断设置：内存值已生效，下次启动会重试
    }

    /** 旧版本迁移：theme_prefs 存明暗，app_prefs 存强调色与米黄。 */
    private fun migrateFromPrefs(): AppearanceSnapshot {
        val legacyTheme = if (::legacyPrefs.isInitialized) {
            legacyPrefs.getString("theme_mode", null)?.let {
                runCatching { ThemeMode.valueOf(it) }.getOrNull()
            }
        } else {
            null
        }
        return AppearanceSnapshot(
            themeMode = legacyTheme ?: ThemeMode.SYSTEM,
            accentIndex = prefs.getInt("accent_color", 0),
            beigeBackground = prefs.getBoolean("light_beige_background", false),
        ).normalized()
    }

    internal fun encode(s: AppearanceSnapshot): String = JSONObject()
        .put("version", 1)
        .put("themeMode", s.themeMode.name)
        .put("accent", s.accentIndex)
        .put("customArgb", s.customArgb)
        .put("useCustom", s.useCustomAccent)
        .put("beige", s.beigeBackground)
        .put("glassTint", s.glassTint)
        .put("uiFont", s.uiFont)
        .put("uiFontScale", s.uiFontScale.toDouble())
        .put("materialYou", s.materialYou)
        .put("flatBackground", s.flatBackground)
        .toString()

    internal fun decode(text: String): AppearanceSnapshot? = try {
        val o = JSONObject(text)
        AppearanceSnapshot(
            themeMode = runCatching { ThemeMode.valueOf(o.optString("themeMode", "SYSTEM")) }
                .getOrDefault(ThemeMode.SYSTEM),
            accentIndex = o.optInt("accent", 0),
            customArgb = o.optInt("customArgb", 0),
            useCustomAccent = o.optBoolean("useCustom", false),
            beigeBackground = o.optBoolean("beige", false),
            glassTint = o.optBoolean("glassTint", true),
            uiFont = o.optString("uiFont", AppearanceSnapshot.FONT_SERIF),
            uiFontScale = o.optDouble("uiFontScale", 1.0).toFloat(),
            materialYou = o.optBoolean("materialYou", false),
            flatBackground = o.optBoolean("flatBackground", true),
        ).normalized()
    } catch (_: Exception) {
        null
    }

    const val FILE_NAME = "appearance.json"
    const val MIN_SCALE = 0.85f
    const val MAX_SCALE = 1.30f

    /** 字号刻度间距：85/90/…/130%，共 10 个「有意义」的值 */
    const val FONT_SCALE_STEP = 0.05f

    /** 吸附半径（±0.6 个百分点的缩放值）：进入这个范围就吸到刻度上 */
    const val FONT_SCALE_SNAP_RADIUS = 0.006f

    /**
     * 字号滑杆的取值：连续拖动 + 关键刻度吸附。
     *
     * 刻度附近 ±[FONT_SCALE_SNAP_RADIUS] 吸到 5% 档（吸住感 + 轻触反馈），
     * 其余位置按 1% 取整 —— 85%..130% 共 45 个位置，滑杆上相邻档约 0.5dp，
     * 肉眼看仍是连续拖动。边界外先钳制再吸附，保证返回值永远在合法区间。
     */
    fun snapUiFontScale(raw: Float): Float {
        val clamped = raw.coerceIn(MIN_SCALE, MAX_SCALE)
        val index = ((clamped - MIN_SCALE) / FONT_SCALE_STEP).roundToInt()
        val stop = MIN_SCALE + index * FONT_SCALE_STEP
        val snapped = if (abs(stop - clamped) <= FONT_SCALE_SNAP_RADIUS) {
            stop
        } else {
            (clamped * 100f).roundToInt() / 100f
        }
        return snapped.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    /**
     * 拖动过程中的取值：刻度附近 ±[FONT_SCALE_SNAP_RADIUS] 吸住，其余**保留连续值**。
     *
     * 不能在拖动中按 1% 取整：85%..130% 映射到一根约 800px 的滑杆上，1% 就是十几像素，
     * 滑杆会一格一格跳（真机反馈「不能丝滑拖动」）。取整留到松手时做。
     */
    fun dragUiFontScale(raw: Float): Float {
        val clamped = raw.coerceIn(MIN_SCALE, MAX_SCALE)
        val index = ((clamped - MIN_SCALE) / FONT_SCALE_STEP).roundToInt()
        val stop = MIN_SCALE + index * FONT_SCALE_STEP
        return if (abs(stop - clamped) <= FONT_SCALE_SNAP_RADIUS) stop else clamped
    }

    /** 当前值是否正好落在 5% 刻度上（滑杆用来决定是否给吸附反馈） */
    fun isFontScaleStop(value: Float): Boolean {
        val steps = (value - MIN_SCALE) / FONT_SCALE_STEP
        return abs(steps - steps.roundToInt()) < 1e-3f
    }
}
