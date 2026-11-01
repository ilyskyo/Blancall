// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import com.ilyskyo.blancall.ui.theme.isBlancallDark
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 底部面板容器（无渐变版）：半透明染色 + 16dp 顶部圆角 + 黑色 32% scrim。
 * 已移除顶部高光 verticalGradient 装饰，回归纯色面板。
 *
 * 面板颜色只来自 containerColor 的染色，不做真实 backdrop 模糊（Compose 的 RenderEffect 只能模糊
 * 自己的图层内容，模糊不到身后那页；要"透过面板看见内容"得用 AndroidView 采样，见 LiquidGlassView）。
 *
 * 形态全局统一（原导出面板定稿后回灌成默认值）：
 * - 顶部圆角 16dp —— iOS sheet 的圆角明显小于 M3 默认 28dp，沿用大圆角一眼就看出是 Android；
 * - 抓手是 36×5 的短横线（[GlassSheetGrabber]），全应用只有这一种，调用方不再自绘；
 * - 浅色底为菜单级白（0.93），深色为 #1C1C1E（0.68）。
 *
 * 替换点：PracticeScreen×3 / ModePickerPopup / ReadingModeScreen 设置面板 等全部弹出面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    dragHandle: @Composable (() -> Unit)? = { GlassSheetGrabber() },
    scrimColor: Color = Color.Black.copy(alpha = 0.32f),
    // 可外部持有的 sheetState：调用方需要「程序化收起后再执行动作」时传入。
    // 本组件已经把「先 hide() 播完退场、再通知调用方」做在内部（见 requestDismiss），
    // 外部只需在 wantToDismiss 时把 onDismissRequest 交给我们即可，不必自己再调 hide()。
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    // 面板底色默认沿用面板玻璃染色；分组表单（如 iOS 风格导出面板）需要
    // 「浅一层的面板 + 更亮的行卡片」两档层次，由调用方显式指定整块底色。
    containerColor: Color? = null,
    // 面板内容是否整体可滚。默认 true（短内容面板跟着内容一起滚，小屏也不裁）；
    // 列表型面板（iOS 表单 sheet）要传 false：头部与底部按钮必须钉住，
    // 只有中间的列表自己滚 —— 否则一滑列表整张面板就跟着动，手势跟 iOS 不一样。
    contentScrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isBlancallDark()
    // 容器染色：面板常叠在正文上，只要透一点点，底下的字就会从面板里窜上来。
    // 用户已经连着几轮反馈「透」，这一版压到几乎不透：浅色 0.98、深色 0.92。
    // 深色原来是卡片级 0.68 —— 叠在蓝色文章卡上会整体发蓝（实测），一并压住。
    // 剩下那 2%~8% 只为了保住「面板是一层材质」的暗示，任何高饱和度底色都不会再透出来。
    val stain = if (isDark) {
        Color(0xFF1C1C1E).copy(alpha = 0.92f)
    } else {
        Color.White.copy(alpha = 0.98f)
    }

    // ── 退场要真的播 ──
    // 两种关闭：
    // - 面板自己发起（点遮罩、返回、下滑）：requestDismiss 先 hide() 播完 M3 原生退场；
    // - 调用方按钮直接改 flag：外层 AnimatedOverlayPresence 把 LocalOverlayExitRequested
    //   置 true，本组件同样先 hide() 再回执，宿主收到回执才移除子树。
    // 减动效下直接回执，不等一个不会到来的完成信号。
    val scope = rememberCoroutineScope()
    var dismissing by remember { mutableStateOf(false) }
    val externalExit = LocalOverlayExitRequested.current
    val notifyExitFinished = LocalOverlayExitFinished.current
    // 关闭回执：面板是被手势「收走」的，手指需要知道这一下被收到了。
    // 只有用户发起的关闭走这里（点遮罩、按返回、拖到底），
    // 面板内按钮翻 flag 的那条路不经过本函数 —— 也就不会和按钮自己的回执叠成两下。
    val dismissHaptic = rememberHaptic(HapticTier.Toggle)
    val requestDismiss: () -> Unit = {
        if (!dismissing) {
            dismissing = true
            dismissHaptic()
            if (!Motion.exitsAreAnimated) {
                onDismissRequest()
                notifyExitFinished?.invoke()
            } else {
                scope.launch {
                    try {
                        // suspend：动画结束（或被取消）才返回；本版本的 hide() 已不再返回 Deferred，
                        // 不需要也不应该再用 invokeOnCompletion 那套旧写法。
                        sheetState.hide()
                        onDismissRequest()
                        notifyExitFinished?.invoke()
                    } catch (_: CancellationException) {
                        // 退场中途被打断（例如面板又被抓住）→ 放行，允许再次关闭。
                        // 但回执必须照发：不发就等于宿主永远留着这层壳，
                        // 而 Hidden 的 ModalBottomSheet 窗口照样吃触摸 —— 整页会「看得见、点不动」。
                        dismissing = false
                        notifyExitFinished?.invoke()
                    }
                }
            }
        }
    }

    // 外部状态翻转（按钮/成功页/副作用把 flag 置 false）时，由本组件把 M3 的退场播完，
    // 再通知宿主移除子树。不回调 onDismissRequest：调用方的状态已经是 false 了。
    LaunchedEffect(externalExit) {
        if (!externalExit || dismissing) return@LaunchedEffect
        dismissing = true
        if (!Motion.exitsAreAnimated) {
            notifyExitFinished?.invoke()
        } else {
            try {
                sheetState.hide()
                notifyExitFinished?.invoke()
            } catch (_: CancellationException) {
                // 不能等一个被打断的 hide：宿主必须收到回执，否则面板永久留在组合里
                notifyExitFinished?.invoke()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = requestDismiss,
        modifier = modifier,
        shape = shape,
        containerColor = containerColor ?: stain,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        // 直接展开到内容高度；避免部分展开（半屏）时出现大片留白
        sheetState = sheetState,
        content = {
            // wrap 高度 + 可滚动，让面板高度恰好包住内容（不出现大块留白）。
            // 这里曾有一层 glassSurface(18f) 的"backdrop 模糊"：它包着的 AmbientBackground 什么都不绘制，
            // 于是每次开面板都为一层空像素付全屏 GPU 模糊，而面板颜色本来就来自 containerColor。
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (contentScrollable) Modifier.verticalScroll(scrollState) else Modifier
                    ),
                content = content
            )
        }
    )
}

/**
 * 面板顶部抓手：36×5 的短横线，上下留白 6 / 11。
 *
 * 全应用唯一形态（原先各面板自绘 36×4、留白 10 的细条，或干脆不画），
 * 由 [GlassModalBottomSheet] 作为默认 dragHandle 统一供给；不承担读屏语义，关闭仍走下滑 / 遮罩 / 返回。
 */
@Composable
fun GlassSheetGrabber() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        )
    }
}
