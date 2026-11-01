// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ilyskyo.blancall.R
import com.ilyskyo.blancall.ui.theme.AppearanceStore
import com.ilyskyo.blancall.ui.theme.ColorMath
import com.ilyskyo.blancall.ui.theme.isBlancallDark
import com.qmdeve.liquidglass.widget.LiquidGlassView
import java.util.concurrent.atomic.AtomicReference

/**
 * 把「中性白光斑」染上当前主题色（**玻璃染色**）。
 *
 * 只改 RGB、保留原 alpha，且**四颗光斑用同一个颜色** —— 上一轮之所以把彩色光斑
 * 全部改成纯白，是因为当时每颗光斑色相不同（左上靛蓝、右下蓝），玻璃放大折射后
 * 变成「一边挂着色块、一边什么都没有」。整族同色就不会再出现这个问题：
 * 玻璃只是整体偏了一点主题色，不再有局部色块。
 *
 * 开关在设置 → 外观 → 玻璃染色（[AppearanceStore] 的 glassTint）。
 */
@Composable
private fun tintedSpots(isDark: Boolean, spots: List<LiquidBackdropCanvas.Spot>): List<LiquidBackdropCanvas.Spot> {
    val appearance by AppearanceStore.state.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    if (!appearance.glassTint) return spots
    // 浅色：主题色压到 30%（主题色本身偏深，直接上会把白玻璃染脏）
    // 深色：放到 55%（深色主题色本来就亮，权重低了完全看不出）
    val weight = if (isDark) 0.55f else 0.30f
    val tinted = ColorMath.blend(accent.toArgb(), ColorMath.WHITE, weight)
    return remember(spots, tinted) {
        spots.map { it.copy(color = (it.color and 0x00FFFFFF) or (tinted and 0xFFFFFF)) }
    }
}

/**
 * 弹窗兜底玻璃板：半透明底色 + 高光描边（液态玻璃的标志性边缘）。
 *
 * @param glassActive 真实液态玻璃是否存在。存在时底色压薄一点（质感交给折射层），
 *                    不存在时（API<33）用更实的底色撑住可读性。
 *
 * **2026-10-26/27 两次把不透明度提上来**：原先真实玻璃在场时只铺 40% 白，叠上 14% 的
 * tint 也只有约 48% —— 用户反馈「这个还是有点透」，弹窗背后的正文会直接透出来。
 * 第一次提到 72% + 45% ≈ 85%，模拟器实测截图里，弹窗下方那颗饱和的蓝色按钮仍能看见轮廓，
 * 于是再抬一档到 91% + 50% ≈ 95.5%：只剩一层极淡的底层色透出来（保住「这是玻璃」的暗示），
 * 任何高饱和度元素都不会再窜上来。系统级 ActionSheet 的材质同样在 95% 量级。
 */
@Composable
fun PopupGlassPlate(
    isDark: Boolean,
    glassActive: Boolean,
    modifier: Modifier = Modifier,
    cornerPx: Float? = null
) {
    // cornerPx 为像素值（与 LiquidGlassView.setCornerRadius 同源）；null 时沿用默认 20dp
    val shape = cornerPx?.let { RoundedCornerShape(it) } ?: RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    isDark -> if (glassActive) Color(0xE81C1C1E) else Color(0xF21A1A1A)
                    else -> if (glassActive) Color(0xE8FFFFFF) else Color(0xFAFFFFFF)
                }
            )
            .border(
                BorderStroke(1.dp, if (isDark) Color(0x59FFFFFF) else Color(0xE0FFFFFF)),
                shape
            )
    )
}

/**
 * 弹窗液态玻璃底（仅 API 33+）：自绘「氛围光斑」采样源 + 真实 LiquidGlassView 折射。
 *
 * 层次（自下而上）：光斑画布（采样源）→ 兜底玻璃板 → 折射玻璃 → 内容。
 *
 * **为什么不用页面做采样源**：本弹窗是页面内 overlay，拿不到像阅读模式那样独立的
 * 正文容器；若直接采样页面根布局，玻璃会采样到玻璃自身，形成逐帧反馈循环。
 * 因此沿用项目既有思路（[LiquidBackdropCanvas]）：自绘几颗主题色光斑作为 bind 采样源，
 * 折射 / 色散 / 模糊作用于这些光斑，液态玻璃质感依旧完整。
 *
 * **三个必须做的设置**（缺一就会出现"整块模糊盖住文字"）：
 * 1. `bind(采样源)` —— 不 bind 的 LiquidGlassView 无内容可折射，会渲染成一块糊斑
 * 2. `setTintAlpha` —— 控制玻璃自身不透明度，过重则完全遮住下层与文字
 * 3. `setDraggableEnabled/setElasticEnabled/setTouchEffectEnabled(false)` ——
 *    弹窗内的玻璃不该响应拖拽/弹性/触摸特效，否则会抢走列表项的点击
 */
@Composable
fun LiquidGlassPopupBackdrop(
    isDark: Boolean,
    cornerPx: Float,
    modifier: Modifier = Modifier,
    // 玻璃质感参数（默认=弹窗配方）。页面内操作栏等横向玻璃条应传导航栏基准：
    // blur 6 / dispersion 0.5 / 折射 20dp·70dp / 染色 暗0.25·亮0.12（重模糊会把玻璃糊脏）。
    blurRadius: Float = 24f,
    dispersion: Float = 0.45f,
    refractionHeightDp: Float = 26f,
    refractionOffsetDp: Float = 80f,
    // 弹窗配方：tint 比页面内的玻璃条重得多。页面条只需要「一层薄玻璃」，
    // 弹窗要在任何正文之上保证选项可读 —— 而且用户已经两次反馈「透」。
    tintAlphaDark: Float = 0.55f,
    tintAlphaLight: Float = 0.50f,
    // plate=false：跳过「底色+描边」兜底玻璃板（操作栏等纯玻璃观感场景传 false）
    plate: Boolean = true
) {
    val density = LocalDensity.current
    val glassRef = remember { AtomicReference<LiquidGlassView?>(null) }
    val sourceRef = remember { AtomicReference<LiquidBackdropCanvas?>(null) }

    val refractPx = with(density) { refractionHeightDp.dp.toPx() }
    val offsetPx = with(density) { refractionOffsetDp.dp.toPx() }

    // 采样源光斑：**全中性白色 + 四角镜像对称**。
    //
    // 这里原本是五点彩色光斑（左上靛蓝 / 右上淡紫 / 左下蓝紫 / 右下蓝 / 底部白）。
    // 真机读感是「左上角挂着一片淡蓝残留，右上角什么颜色都没有」——
    // 玻璃把采样源放大折射后，色相不同的几颗光斑变成局部色块，而不是均匀氛围光。
    //
    // 修法不是把 alpha 调小（那只是把色块变淡，仍然一边有一边没有），而是换掉两处不均匀：
    // 1) 色相：一律纯白，整块玻璃只剩明暗层次，物理上不可能再偏色；
    // 2) 位置：左右 x 严格镜像、alpha 左右完全一致，杜绝「一边有、一边没有」。
    // 上下给一点点差（上略亮）保留"顶面受光"的立体感，但左右同值，残色感消失。
    // 浅色下白斑几乎不可见（玻璃读作统一的磨砂白），深色下是柔和的环境高光。
    val baseSpots = remember(isDark) {
        if (isDark) {
            listOf(
                LiquidBackdropCanvas.Spot(0x18FFFFFF, 0.20f, 0.16f, 0.62f),   // 左上 — 冷白
                LiquidBackdropCanvas.Spot(0x18FFFFFF, 0.80f, 0.16f, 0.62f),   // 右上 — 与左上完全同值
                LiquidBackdropCanvas.Spot(0x0EFFFFFF, 0.20f, 0.84f, 0.62f),   // 左下
                LiquidBackdropCanvas.Spot(0x0EFFFFFF, 0.80f, 0.84f, 0.62f)    // 右下
            )
        } else {
            listOf(
                LiquidBackdropCanvas.Spot(0x14FFFFFF, 0.20f, 0.16f, 0.62f),   // 左上 — 冷白
                LiquidBackdropCanvas.Spot(0x14FFFFFF, 0.80f, 0.16f, 0.62f),   // 右上 — 与左上完全同值
                LiquidBackdropCanvas.Spot(0x0AFFFFFF, 0.20f, 0.84f, 0.62f),   // 左下
                LiquidBackdropCanvas.Spot(0x0AFFFFFF, 0.80f, 0.84f, 0.62f)    // 右下
            )
        }
    }
    val spots = tintedSpots(isDark, baseSpots)

    Box(modifier) {
        // ── a) 采样源：自绘氛围光斑（最底层，供玻璃折射）──
        AndroidView(
            factory = { ctx ->
                LiquidBackdropCanvas(ctx).apply { sourceRef.set(this) }
            },
            modifier = Modifier.matchParentSize(),
            update = { canvas -> canvas.setSpots(spots) }
        )

        // ── b) 兜底玻璃板：半透明底色 + 高光描边（圆角与折射层同源）──
        // plate=false 时跳过该层（避免整条底色块+描边大矩形的观感）
        if (plate) {
            PopupGlassPlate(
                isDark = isDark,
                glassActive = true,
                modifier = Modifier.matchParentSize(),
                cornerPx = cornerPx
            )
        }

        // ── c) 真实液态玻璃：折射 + 色散 + 模糊 ──
        AndroidView(
            factory = { ctx ->
                LiquidGlassView(ctx).apply {
                    setCornerRadius(cornerPx)
                    setRefractionHeight(refractPx)
                    setRefractionOffset(offsetPx)
                    setBlurRadius(blurRadius)
                    setDispersion(dispersion)
                    if (isDark) {
                        // 深色：暖黑玻璃，略微提透明度撑起质感
                        setTintColorRed(0f); setTintColorGreen(0f); setTintColorBlue(0f)
                        setTintAlpha(tintAlphaDark)
                    } else {
                        // 浅色：白玻璃轻染，让"玻璃"本身可见而不喧宾夺主
                        setTintColorRed(1f); setTintColorGreen(1f); setTintColorBlue(1f)
                        setTintAlpha(tintAlphaLight)
                    }
                    setDraggableEnabled(false)
                    setElasticEnabled(false)
                    setTouchEffectEnabled(false)
                    glassRef.set(this)
                    // 延迟到布局完成后再 bind：挂载帧内同步 bind 会与 measure pass 竞争
                    post {
                        if (isAttachedToWindow && getTag(R.id.lg_bound_tag) == null) {
                            sourceRef.get()?.let { bind(it) }
                            setTag(R.id.lg_bound_tag, true)
                        }
                    }
                }
            },
            modifier = Modifier.matchParentSize(),
            update = { view ->
                view.applyGlassThemeTint(
                    isDark,
                    darkTintIsBlack = true,
                    darkTintAlpha = tintAlphaDark,
                    lightTintAlpha = tintAlphaLight,
                )
                if (view.getTag(R.id.lg_bound_tag) == null) {
                    view.post {
                        if (view.isAttachedToWindow && view.getTag(R.id.lg_bound_tag) == null) {
                            sourceRef.get()?.let { view.bind(it) }
                            view.setTag(R.id.lg_bound_tag, true)
                        }
                    }
                }
            }
        )
    }

    // 退出组合：解绑采样（bind(null) → 库内 recycle），清除残留绘制监听
    DisposableEffect(Unit) {
        onDispose { runCatching { glassRef.get()?.bind(null) } }
    }
}

/**
 * 页面内液态玻璃条：自绘极淡中性光斑为采样源，纯透明无色折射。
 *
 * 用于文章详情页操作栏等场景——需要液态玻璃质感但不想要 `LiquidGlassPopupBackdrop`
 * 的自绘彩色光斑底色。
 *
 * **为什么不能 bind 页面宿主**：本组件位于 NavHost 的 ComposeView 内，页面宿主
 * （AppNavigation 的 pageHost）是它的祖先容器。bind 祖先会在每帧绘制时经
 * ViewTreeObserver 的 PreDraw 监听形成「祖先绘制 → 玻璃 invalidate → 祖先重绘」的
 * 反馈循环，RenderThread 栈溢出闪退（真机复现）。故与弹窗/阅读模式同架构：采样源
 * 必须是玻璃的兄弟节点——这里用组件内自绘的中性光斑画布。
 *
 * @param cornerDp 圆角大小（dp）
 * @param modifier 外部修饰符
 */
@Composable
fun LiquidGlassPageBar(
    modifier: Modifier = Modifier,
    cornerDp: Int = 24,
    dispersion: Float = 0.5f,
    tintAlphaLight: Float = 0.12f,
    tintAlphaDark: Float = 0.25f
) {
    val density = LocalDensity.current
    val cornerPx = with(density) { cornerDp.dp.toPx() }
    val isDark = isBlancallDark()
    val glassRef = remember { AtomicReference<LiquidGlassView?>(null) }
    val sourceRef = remember { AtomicReference<LiquidBackdropCanvas?>(null) }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // 采样源：中性白光斑（可被「玻璃染色」开关整族染成主题色），
        // 只在 update 里 setSpots，避免开关切换时画布保持旧色。
        val barSpots = tintedSpots(
            isDark,
            if (isDark) listOf(
                LiquidBackdropCanvas.Spot(0x1AFFFFFF, 0.15f, 0.30f, 0.60f),
                LiquidBackdropCanvas.Spot(0x12FFFFFF, 0.50f, 0.55f, 0.45f),
                LiquidBackdropCanvas.Spot(0x14FFFFFF, 0.85f, 0.80f, 0.50f)
            ) else listOf(
                LiquidBackdropCanvas.Spot(0x10FFFFFF, 0.15f, 0.30f, 0.60f),
                LiquidBackdropCanvas.Spot(0x0CFFFFFF, 0.50f, 0.55f, 0.45f),
                LiquidBackdropCanvas.Spot(0x0EFFFFFF, 0.85f, 0.80f, 0.50f)
            )
        )
        Box(modifier) {
            // ── a) 采样源：自绘中性光斑（无色相，仅供玻璃折射，不产生彩色底色）──
            AndroidView(
                factory = { ctx ->
                    LiquidBackdropCanvas(ctx).apply { sourceRef.set(this) }
                },
                modifier = Modifier.matchParentSize(),
                update = { canvas -> canvas.setSpots(barSpots) }
            )

            // ── b) 真实液态玻璃：折射 + 色散 + 模糊（bind 兄弟画布，非祖先）──
            AndroidView(
                factory = { ctx ->
                    LiquidGlassView(ctx).apply {
                        // bind 前兜底背景：液态层未采样前给玻璃条底色
                        setBackgroundColor(
                            if (isDark) android.graphics.Color.argb(230, 26, 26, 26)
                            else android.graphics.Color.argb(230, 255, 255, 255)
                        )
                        setCornerRadius(cornerPx)
                        // 折射参数对齐导航栏基准
                        setRefractionHeight(with(density) { 20.dp.toPx() })
                        setRefractionOffset(with(density) { 70.dp.toPx() })
                        setBlurRadius(6f)
                        setDispersion(dispersion)
                        if (isDark) {
                            setTintColorRed(0f); setTintColorGreen(0f); setTintColorBlue(0f)
                            setTintAlpha(tintAlphaDark)
                        } else {
                            setTintColorRed(1f); setTintColorGreen(1f); setTintColorBlue(1f)
                            setTintAlpha(tintAlphaLight)
                        }
                        setDraggableEnabled(false)
                        setElasticEnabled(false)
                        setTouchEffectEnabled(false)
                        glassRef.set(this)
                        // 延迟 bind：等挂载完成后再绑定采样画布
                        post {
                            if (isAttachedToWindow && getTag(R.id.lg_bound_tag) == null) {
                                sourceRef.get()?.let { bind(it) }
                                setTag(R.id.lg_bound_tag, true)
                                // bind 成功即清除兜底背景：玻璃已开始折射采样画布，
                                // 保留 90% 不透明底色会透出成「矩形框」（用户要求无框）
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        }
                    }
                },
                modifier = Modifier.matchParentSize(),
                update = { view ->
                    view.applyGlassThemeTint(
                        isDark,
                        darkTintIsBlack = true,
                        darkTintAlpha = tintAlphaDark,
                        lightTintAlpha = tintAlphaLight,
                    )
                    val bound = view.getTag(R.id.lg_bound_tag) != null
                    if (!bound) view.applyGlassThemeBackground(isDark, 230)
                    if (!bound) {
                        view.post {
                            if (view.isAttachedToWindow && view.getTag(R.id.lg_bound_tag) == null) {
                                sourceRef.get()?.let { view.bind(it) }
                                view.setTag(R.id.lg_bound_tag, true)
                                // 同上：bind 后清掉兜底底色，只留圆角玻璃与阴影
                                view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            }
                        }
                    }
                }
            )
        }
    } else {
        // API<33 降级：实底玻璃板
        Box(
            modifier
                .clip(RoundedCornerShape(cornerDp.dp))
                .background(if (isDark) Color(0xE61A1A1A) else Color(0xF2FFFFFF))
                .border(1.dp, if (isDark) Color(0x59FFFFFF) else Color(0xE0FFFFFF), RoundedCornerShape(cornerDp.dp))
        )
    }

    // 退出组合：解绑采样
    DisposableEffect(Unit) {
        onDispose { runCatching { glassRef.get()?.bind(null) } }
    }
}
