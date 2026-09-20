// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.sqrt

/**
 * 指针来源判定与过滤工具。
 *
 * ## 为什么需要它
 * 平板 + 主动笔（S Pen / M-Pencil / 小米灵感笔）的真实使用姿态是：
 * **一只手扶住屏幕固定页面，另一只手拿笔书写**。此时屏幕上同时存在
 * 「手指」与「笔」两个触点，而原先所有捏合手势都用 `pressed.size >= 2`
 * 判定双指缩放 —— 扶屏的手指 + 落笔的手写笔被误判成双指捏合，字号会乱跳。
 *
 * 正确做法是按 [PointerType] 区分来源：
 * - 只有 **手指（[PointerType.Touch]）** 参与缩放/滚动等触摸手势；
 * - 手写笔（[PointerType.Stylus]）单独走书写通道，不触发触摸手势；
 * - 鼠标/触控板（[PointerType.Mouse]）按指针设备常规行为处理，不参与捏合。
 *
 * ## 关于掌拒（palm rejection）
 * 应用侧不需要自己实现掌拒算法 —— 支持手写笔的设备在系统/输入驱动层
 * 已处理手掌误触。应用侧只要做 PointerType 过滤，就能覆盖
 * 「落笔时手指误触」这一最常见场景。
 */

/** 事件中所有仍按下的触点。 */
fun PointerEvent.pressedChanges(): List<PointerInputChange> =
    changes.filter { it.pressed }

/**
 * 仅保留「手指」触点。
 *
 * 这是所有触摸手势（捏合缩放、拖动、点按）应当过滤的对象：
 * 手写笔与鼠标不应参与触摸手势判定。
 */
fun PointerEvent.pressedTouchChanges(): List<PointerInputChange> =
    changes.filter { it.pressed && it.type == PointerType.Touch }

/** 事件中是否有手写笔正在接触（已落下，非悬停）。 */
fun PointerEvent.hasPressedStylus(): Boolean =
    changes.any { it.pressed && it.type == PointerType.Stylus }

/** 事件中是否有手指正在接触。 */
fun PointerEvent.hasPressedTouch(): Boolean =
    changes.any { it.pressed && it.type == PointerType.Touch }

/**
 * 双指捏合缩放（**仅手指**）：仅当屏幕上有**恰好 2 个手指**触点时按缩放增量回调
 * [onZoomChange]；单指滑动完全交给下层滚动容器，不产生手势冲突。
 *
 * **必须是 Modifier 的扩展函数**（`Modifier.pinchZoomByTouch(...)`）：
 * 它内部用 `pointerInput` 挂在调用方已有的 Modifier 链上，直接当顶层函数调用会丢失
 * 链上前面所有的 Modifier（尺寸、裁剪等），且无法解析。
 *
 * ## 三重过滤（每一重都对应一类真机误触）
 * 1. **触点来源**：只有 [PointerType.Touch] 参与，手写笔与鼠标不参与；
 * 2. **笔在写时整体让位**：只要 [StylusActivity.isWriting] 或事件里存在按下的笔触点，
 *    这一次触摸手势就不做任何缩放 —— 写字时手掌搭在正文区**必然**产生 2 个以上手指触点
 *    （手掌与手腕边缘会同时接触到玻璃），只按来源过滤挡不住（它们是 Touch）；
 * 3. **触点数量必须正好是 2**：3 个及以上几乎只可能是大面积掌托
 *    （正常人手捏合用两根手指），此时也整体跳过。
 *
 * 第 2、3 重都**重置基准而不是消费事件**：手指点按、滚动等其它交互不受影响，
 * 只是不产生缩放 —— 用户写字中途仍能用手指去点「清空」等按钮。
 *
 * @param onZoomChange 缩放因子回调（>1 放大，<1 缩小）
 * @param key 额外的 pointerInput key，容器尺寸变化需重建手势时可传入
 */
fun Modifier.pinchZoomByTouch(
    key: Any? = Unit,
    onZoomChange: (Float) -> Unit
): Modifier = pointerInput(key) {
    awaitEachGesture {
        // requireUnconsumed = false：即便下层（滚动容器/翻页）已消费首个 down，
        // 也要能看到事件，否则捏合在部分容器里完全失效
        awaitFirstDown(requireUnconsumed = false)
        var prevDist = -1f
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break

            // 拦掌托：笔在屏上写字时，落在别处的手掌/手腕触点同样会进入这条手势路径
            // （Compose 按命中路径分发，两者互相看不见），所以必须读全局标记。
            if (StylusActivity.isWriting || event.hasPressedStylus()) {
                prevDist = -1f
                continue
            }

            val touch = event.pressedTouchChanges()
            // 恰好 2 指才算捏合：≥3 触点是大面积掌托，不是手势
            if (touch.size == 2) {
                val a = touch[0].position
                val b = touch[1].position
                val dx = a.x - b.x
                val dy = a.y - b.y
                val dist = sqrt(dx * dx + dy * dy)
                if (prevDist > 0f && dist > 0f) {
                    val factor = dist / prevDist
                    if (factor.isFinite()) onZoomChange(factor)
                }
                prevDist = dist
            } else {
                // 手指数量不足（例如只剩手写笔）：重置基准，避免松手再按时跳变
                prevDist = -1f
            }
        }
    }
}

/**
 * 判断某个触点是否为手写笔。
 * 用于点按类手势分流：笔走书写/预览通道，手指走原有交互通道。
 */
fun PointerInputChange.isStylus(): Boolean = type == PointerType.Stylus

/** 判断某个触点是否为手指。 */
fun PointerInputChange.isTouch(): Boolean = type == PointerType.Touch

/** 判断某个触点是否为鼠标（含触控板）。 */
fun PointerInputChange.isMouse(): Boolean = type == PointerType.Mouse

/**
 * 全局「手写笔正在书写」标记。
 *
 * ## 为什么必须是全局状态，而不能只在本层过滤事件
 * Compose 的指针事件是按**命中测试路径**分发的：笔落在手写板里，事件被手写板
 * 在 Initial pass 消费后就到此为止；而**扶屏的手指**落在别处（比如旁边另一个挖空、
 * 或阅读页空白），走的是另一条完全独立的命中路径，两者互相看不见对方的触点。
 * 因此「笔在写字时忽略手指」这条规则无法靠单个 `pointerInput` 内的
 * `PointerEvent` 过滤实现，必须有一个跨组合树的共享标记。
 *
 * 写入方：`HandwritingPanel` 起笔置 `true`、抬笔/手势取消置 `false`。
 * 读取方：[Modifier.tapGesturesPenAware]。
 */
object StylusActivity {
    /**
     * 是否有手写笔正在接触屏幕。
     * `@Volatile`：写入发生在 Compose 手势协程，读取发生在另一条协程，
     * 不加可见性保证时读方可能长期看到旧值（表现为「偶尔点不动」）。
     */
    @Volatile
    var isWriting: Boolean = false

    /** 最近一次「手指点按被笔书写挡下」的时间戳。仅用于调试定位，不参与任何逻辑判断。 */
    @Volatile
    var lastSuppressedAt: Long = 0L
}

/**
 * 点按手势（**笔在书写时忽略手指**）。
 *
 * 与 `detectTapGestures` 的唯一区别：当 [StylusActivity.isWriting] 为真时，
 * **手指**落下的点按会被整段吞掉（从 down 到 up 全部消费），不再触发回调、
 * 也不泄漏给下层；**手写笔自己的点按不受影响**——拿笔点选是明确的操作意图。
 *
 * 解决的正是平板书写最恼人的一类误触：挖空默写时用户一手扶屏、一手握笔，
 * 扶屏的手掌/手指搭在屏幕上，会把旁边的挖空点开、或把阅读控件切出来。
 *
 * 注意与 `Modifier.clickable` 的关系：命中路径不同层，两者互不影响；
 * 本函数只用于「大面积文字内容」这类由自己接管点按的区域。
 *
 * @param onTap 点按回调（回传按下位置）
 */
fun Modifier.tapGesturesPenAware(
    onTap: (Offset) -> Unit
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)

        // 笔在写 + 这一下是手指 ⇒ 判定为掌托误触。
        // 整段消费而不是只消费 down：否则手指中途抬起时，下层仍可能把它当成一次 click。
        if (down.isTouch() && StylusActivity.isWriting) {
            StylusActivity.lastSuppressedAt = System.currentTimeMillis()
            while (true) {
                val ev = awaitPointerEvent()
                ev.changes.forEach { it.consume() }
                if (ev.changes.none { it.pressed }) break
            }
            return@awaitEachGesture
        }

        // 常规点按：按下与抬起之间位移不超过 touchSlop 才算点击（超了就是滚动/拖拽）
        val start = down.position
        var moved = false
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if ((ch.position - start).getDistance() > slop) moved = true
            if (!ch.pressed) {
                if (!moved) onTap(start)
                break
            }
        }
    }
}

/**
 * **笔点即书写**：手写笔在控件上按下时立刻回调 [onPenDown]，并**消费整段手势** ——
 * 控件本身拿不到焦点，所以不会弹出软键盘。
 *
 * ## 为什么必须有这一层
 * 拿笔的人点「作答区」，期望的是**写字**；这时弹软键盘是最差路径
 * （真机日志证实：系统会去请求 IME 的手写模式，而多数输入法不支持，静默失败）。
 *
 * ## 交互约定（三个练习模式统一）
 * - **笔**落在目标上 → 进入书写（切到手写模式并弹出书写板）；
 * - **手指**点 → 完全不拦截，照常走原有交互（弹键盘、滚动）。
 *
 * `onPenDown` 用 [rememberUpdatedState] 兜住：`pointerInput` 只在 key 变化时重启，
 * 直接捕获回调会冻结成「首次组合那次」的实例（与 `HandwritingPanel` 里踩过的坑同源）。
 *
 * ⚠️ 与 [Modifier.tapGesturesPenAware] 的分工：后者管「笔在写时忽略手指」（掌托保护），
 * 本函数管「笔点即切书写」（入口分流）。两者互补，需要时都要挂。
 *
 * @param key 额外的 pointerInput key（例如所在卡片的空序号）
 * @param enabled 是否启用拦截
 * @param onPenDown 笔按下时的动作
 */
fun Modifier.penTapToHandwriting(
    key: Any? = Unit,
    enabled: Boolean = true,
    onPenDown: () -> Unit
): Modifier = composed {
    val callback = rememberUpdatedState(onPenDown)
    val on = rememberUpdatedState(enabled)
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = down.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
            if (pressed.type != PointerType.Stylus || !on.value) return@awaitEachGesture
            callback.value()
            // 吞掉这一笔的后续事件直到抬起：否则下层（输入框/可滚动容器）仍会拿到
            // 焦点或把这一下当成拖动 —— 真机现象就是「页面跟着笔一起滚」。
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                e.changes.forEach { it.consume() }
                if (e.changes.all { !it.pressed }) break
            }
        }
    }
}

/**
 * 掌托误触守卫（供 `clickable` 这类无法自定义「按下判定」的场景使用）。
 *
 * 在 `onClick` 开头调用；返回 `true` 表示这次点击应被丢弃、直接跳过动作。
 * 判定依据是「此刻手写笔仍处于书写中」。
 *
 * ⚠️ **局限（务必知道）**：判定发生在**抬手**这一刻。若手指按下时笔在写、
 * 但在手指抬起之前笔已离屏，这次点击会漏过。要完全规避请在 `pointerInput` 里
 * 用 [Modifier.tapGesturesPenAware]（它在 **down** 时刻就判定）。
 * 这里之所以仍用抬手判定，是为了保住 `clickable` 自带的涟漪与无障碍语义——
 * 对退格、切模式这类辅助控件，吃掉一次误触远比丢掉语义重要，取前者先。
 */
fun suppressAsPalmMisTouch(): Boolean {
    if (!StylusActivity.isWriting) return false
    StylusActivity.lastSuppressedAt = System.currentTimeMillis()
    return true
}

/**
 * 观察是否有手写笔悬浮（hover）在控件上方，用于「笔尖靠近即预览」类交互。
 *
 * 手写笔悬停不产生 down，只产生 [androidx.compose.ui.input.pointer.PointerEventType.Move]
 * 且 `pressed == false`，因此必须在 [PointerEventPass.Initial] 或 Main pass 上
 * 用 [awaitPointerEvent] 直接观察，而不能用 detectTapGestures。
 *
 * @param onHoverStart 笔进入控件范围（含笔尖靠近）
 * @param onHoverEnd 笔离开控件范围
 * @param onHoverMove 笔在控件上方移动，参数为相对位置与是否已按下
 */
fun Modifier.stylusHover(
    onHoverStart: () -> Unit = {},
    onHoverEnd: () -> Unit = {},
    onHoverMove: (position: Offset, pressed: Boolean) -> Unit = { _, _ -> }
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var hovering = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val stylus = event.changes.firstOrNull { it.isStylus() }
            if (stylus == null) {
                if (hovering) {
                    hovering = false
                    onHoverEnd()
                }
                continue
            }
            when (event.type) {
                PointerEventType.Enter -> {
                    if (!hovering) {
                        hovering = true
                        onHoverStart()
                    }
                }
                PointerEventType.Exit -> {
                    if (hovering) {
                        hovering = false
                        onHoverEnd()
                    }
                }
                else -> {
                    if (!hovering) {
                        hovering = true
                        onHoverStart()
                    }
                    onHoverMove(stylus.position, stylus.pressed)
                }
            }
        }
    }
}
