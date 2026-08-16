// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 应用统一图标集（单色线/面风格，呼应 [com.ilyskyo.blancall.ui.home.SettingsGearIcon]
 * 的描边几何语言）。
 *
 * 标准字形取 Material Icons Outlined（部分需 `material-icons-extended`）；
 * 品牌 Logo 自绘 Canvas 矢量。
 */
enum class AppIconKind {
    Logo, Close, Edit, Inbox, Celebrate, ArrowForward, OpenInFull, Check,
    Home, Articles, Insights, MoreVert, SwapHoriz, TrackChanges, ViewAgenda, Pdf, Share
}

/** 将存储 key 解析为 [AppIconKind]（未知 / 空 → [AppIconKind.Logo]） */
fun appIconKindFromKey(key: String): AppIconKind = when (key.lowercase()) {
    "celebrate" -> AppIconKind.Celebrate
    "edit" -> AppIconKind.Edit
    "inbox" -> AppIconKind.Inbox
    "arrowforward" -> AppIconKind.ArrowForward
    "openinfull" -> AppIconKind.OpenInFull
    "check" -> AppIconKind.Check
    "home" -> AppIconKind.Home
    "articles" -> AppIconKind.Articles
    "insights" -> AppIconKind.Insights
    else -> AppIconKind.Logo
}

/** 将 [AppIconKind] 转为持久化 key（与 [appIconKindFromTheOther] 约定一致的小写形式） */
fun iconKeyFromKind(kind: AppIconKind): String = kind.name.lowercase()

/**
 * 统一图标渲染入口。
 *
 * @param kind 图标种类
 * @param modifier 尺寸 / 布局修饰
 * @param tint 单色描边/填充色，默认取当前主题 `onSurface`
 */
@Composable
fun AppIcon(
    kind: AppIconKind,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    val image = when (kind) {
        AppIconKind.Close -> Icons.Outlined.Close
        AppIconKind.Edit -> Icons.Outlined.Edit
        AppIconKind.Inbox -> Icons.Outlined.Inbox
        AppIconKind.Celebrate -> Icons.Outlined.Celebration
        AppIconKind.ArrowForward -> Icons.AutoMirrored.Outlined.ArrowForward
        AppIconKind.OpenInFull -> Icons.Outlined.OpenInFull
        AppIconKind.Check -> Icons.Outlined.Check
        AppIconKind.Home -> Icons.Outlined.Home
        AppIconKind.Articles -> Icons.AutoMirrored.Outlined.Article
        AppIconKind.Insights -> Icons.Outlined.Insights
        AppIconKind.MoreVert -> Icons.Outlined.MoreVert
        AppIconKind.SwapHoriz -> Icons.Outlined.SwapHoriz
        AppIconKind.TrackChanges -> Icons.Outlined.TrackChanges
        AppIconKind.ViewAgenda -> Icons.Outlined.ViewAgenda
        AppIconKind.Pdf -> Icons.Outlined.PictureAsPdf
        AppIconKind.Share -> Icons.Outlined.Share
        AppIconKind.Logo -> null
    }
    if (image != null) {
        Icon(imageVector = image, contentDescription = null, tint = tint, modifier = modifier)
    } else {
        BlancallLogoIcon(modifier = modifier, tint = tint)
    }
}

/**
 * 自绘品牌 Logo（书本线稿，单色描边，呼应 [com.ilyskyo.blancall.ui.home.SettingsGearIcon]
 * 的几何风格）：中央书脊 + 左右两页。
 */
@Composable
fun BlancallLogoIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(modifier = modifier) {
        val stroke = 1.7.dp.toPx()
        val cx = center.x
        val topY = size.height * 0.24f
        val botY = size.height * 0.80f
        val leftX = size.width * 0.14f
        val rightX = size.width * 0.86f
        val s = Stroke(width = stroke, cap = StrokeCap.Round)

        // 左页
        val left = Path().apply {
            moveTo(cx, topY)
            quadraticTo(cx - size.width * 0.20f, topY - size.height * 0.02f, leftX, topY + size.height * 0.05f)
            lineTo(leftX, botY)
            quadraticTo(cx - size.width * 0.16f, botY + size.height * 0.02f, cx, botY - size.height * 0.03f)
            close()
        }
        drawPath(left, color = tint, style = s)

        // 右页（镜像）
        val right = Path().apply {
            moveTo(cx, topY)
            quadraticTo(cx + size.width * 0.20f, topY - size.height * 0.02f, rightX, topY + size.height * 0.05f)
            lineTo(rightX, botY)
            quadraticTo(cx + size.width * 0.16f, botY + size.height * 0.02f, cx, botY - size.height * 0.03f)
            close()
        }
        drawPath(right, color = tint, style = s)

        // 中央书脊
        drawLine(
            color = tint,
            start = Offset(cx, topY),
            end = Offset(cx, botY - size.height * 0.03f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}
