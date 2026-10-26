// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.graphics.StrokeJoin
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
    Logo, Add, Close, Edit, Inbox, Celebrate, ArrowForward, OpenInFull, Check,
    Home, Articles, Insights, MoreVert, SwapHoriz, TrackChanges, ViewAgenda, Pdf, Share, Library, ChevronRight,
    SearchHint,
    /** 撤销 / 重做（编辑器顶栏用；AutoMirrored 保证 RTL 下箭头方向正确） */
    Undo, Redo,
    /** 手写笔（切换手写输入 / 键盘输入） */
    Stylus,
    /** 标签（文章标签系统：设置入口 / 管理页） */
    Tag,
    /** 筛选（句子卡片抽句范围按标签筛选） */
    Filter,
    /** 拖拽手柄（标签管理页拖动排序用） */
    DragHandle,
    /** 复习（句子卡片：用户主动发起再学一轮） */
    Refresh,
    /** 语言（设置页「界面语言」标题后紧跟的「文A」合体字形，兼作视觉锚点） */
    Language
}

/**
 * 统一图标渲染入口。
 *
 * @param kind 图标种类
 * @param modifier 尺寸 / 布局修饰
 * @param tint 单色描边/填充色，默认取当前主题 `onSurface`
 * @param contentDescription 无障碍内容描述。仅当该图标本身承载读屏用户必须知道的
 *   独立信息（如独立按钮/可点击元素）时传入；纯装饰性或与旁边文字重复的图标保持 `null`。
 */
@Composable
fun AppIcon(
    kind: AppIconKind,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null
) {
    val image = when (kind) {
        AppIconKind.Add -> Icons.Outlined.Add
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
        AppIconKind.Library -> Icons.Outlined.AutoStories
        AppIconKind.ChevronRight -> Icons.Outlined.ChevronRight
        AppIconKind.SearchHint -> Icons.Outlined.Search
        AppIconKind.Undo -> Icons.AutoMirrored.Outlined.Undo
        AppIconKind.Redo -> Icons.AutoMirrored.Outlined.Redo
        AppIconKind.Tag -> Icons.AutoMirrored.Outlined.Label
        AppIconKind.Filter -> Icons.Outlined.FilterAlt
        AppIconKind.DragHandle -> Icons.Outlined.DragHandle
        AppIconKind.Refresh -> Icons.Outlined.Refresh
        AppIconKind.Stylus -> null
        AppIconKind.Logo -> null
        AppIconKind.Language -> null
    }
    if (image != null) {
        Icon(imageVector = image, contentDescription = contentDescription, tint = tint, modifier = modifier)
    } else when (kind) {
        AppIconKind.Stylus -> StylusIcon(modifier = modifier, tint = tint)
        AppIconKind.Language -> LanguageGlyph(modifier = modifier, tint = tint)
        else -> BlancallLogoIcon(modifier = modifier, tint = tint)
    }
}

/**
 * 自绘「文A」语言字形：左「文」右「A」，同一基线上并排，笔画粗细与 [StylusIcon] 同源。
 *
 * 为什么要自绘：Material 图标集里没有语言类字形（`Translate` 是球体 + 文字，
 * `material-icons-extended` 又只为一个小图标不划算）。用户找不到语言入口时，
 * 靠「文A」这个跨语种通用符号认位置比靠那四个汉字可靠。
 *
 * 「文」的骨架按正文字体里的真实字形采样：点在上中部、横画近全宽、
 * 撇捺分别从横画的左段与右段落笔、在横下交叉后分向左右下角——不是
 * 「亠」加一个「人」。A 缩到「文」的约 3/4 高并与它底对齐，是语言符号的通行写法。
 *
 * 视框高 24 单位、宽 28 单位（调用方须按 28:24 给宽高）。绘制时按高度等比缩放、
 * 再把 24 单位方框水平居中，所以 18sp 的字形约 13dp 高、笔画约 1.2dp，
 * 与相邻正文的视觉重量相当。
 */
@Composable
fun LanguageGlyph(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(modifier = modifier) {
        val box = minOf(size.width, size.height)
        val sc = box / 24f
        val ox = (size.width - box) / 2f
        val oy = (size.height - box) / 2f
        fun x(v: Float) = ox + v * sc
        fun y(v: Float) = oy + v * sc
        val stroke = Stroke(
            width = 1.55f * sc,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawPath(
                Path().apply {
                    moveTo(x(x1), y(y1))
                    lineTo(x(x2), y(y2))
                },
                color = tint,
                style = stroke
            )
        }
        fun curve(x1: Float, y1: Float, c1x: Float, c1y: Float, c2x: Float, c2y: Float, x2: Float, y2: Float) {
            drawPath(
                Path().apply {
                    moveTo(x(x1), y(y1))
                    cubicTo(x(c1x), y(c1y), x(c2x), y(c2y), x(x2), y(y2))
                },
                color = tint,
                style = stroke
            )
        }
        // 「文」：点 → 横 → 捺、撇（撇捺从横下交叉，交叉点在横下约 3/4 高处）
        seg(5.54f, 3.00f, 7.48f, 5.97f)
        seg(-0.80f, 7.00f, 13.60f, 7.00f)
        curve(1.81f, 7.22f, 3.40f, 12.70f, 8.80f, 14.70f, 13.15f, 20.49f)
        curve(9.82f, 7.22f, 8.90f, 12.50f, 3.00f, 15.40f, -0.62f, 20.49f)
        // 「A」：两撇 + 横杠（底对齐，高约为「文」的 3/4；横杠端点落在两撇中线上）
        seg(20.00f, 7.40f, 14.80f, 20.60f)
        seg(20.00f, 7.40f, 25.20f, 20.60f)
        seg(18.21f, 16.05f, 21.79f, 16.05f)
    }
}

/**
 * 自绘手写笔图标：45° 斜置笔杆 + 笔尖 + 底部书写基线。
 * Material 核心图标集没有笔形，引入 `material-icons-extended` 只为一个小图标不划算，
 * 且自绘能与其他自绘图标（[BlancallLogoIcon]、设置齿轮）保持同一套描边语言。
 */
@Composable
fun StylusIcon(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w * 0.09f).coerceAtLeast(1.2f)
        val s = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // 笔杆：从右上到左下的一段粗线（留出笔尖）
        val tipX = w * 0.22f
        val tipY = h * 0.78f
        val tailX = w * 0.78f
        val tailY = h * 0.22f
        // 笔尖（下半段）实心三角，视觉上交代「尖」
        val path = Path().apply {
            moveTo(tailX, tailY)
            lineTo(tipX, tipY)
        }
        drawPath(path, tint, style = s)
        // 笔尖小三角
        val nib = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX + w * 0.10f, tipY - h * 0.03f)
            lineTo(tipX + w * 0.03f, tipY - h * 0.10f)
            close()
        }
        drawPath(nib, tint)
        // 书写基线
        val ly = h * 0.90f
        drawPath(
            Path().apply { moveTo(w * 0.12f, ly); lineTo(w * 0.88f, ly) },
            tint,
            style = Stroke(width = stroke * 0.8f, cap = StrokeCap.Round)
        )
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
