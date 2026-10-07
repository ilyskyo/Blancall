// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.animation.ValueAnimator
import com.ilyskyo.blancall.ui.common.Motion
import com.ilyskyo.blancall.ui.common.MotionFade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridItemScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 全局动效词表。两条纪律，别处不再写数字：
 *
 * 1. **位移用弹簧，alpha 与颜色用 tween**（见 [MotionFade]）。
 *    弹簧表达的是「被物理带走」，而淡入淡出没有位置可言，用弹簧只会在终点附近抖一下。
 * 2. 弹簧一律用 [Spring.DampingRatio] / [Spring.Stiffness] 具名常量表达。
 *    裸 float 的 380f、420f、700f 读不出快慢，也无法横向比较。
 *    注意这些常量的名字与数值**不成正比**，调参时看数字别看名字：
 *    StiffnessMedium = 1500f 而 StiffnessMediumLow 反而只有 400f；
 *    DampingRatioHighBouncy = 0.2f（阻尼越小越弹），NoBouncy = 1.0f；
 *    spring() 的默认刚度是 StiffnessMedium(1500f) + NoBouncy，也就是「快且几乎不过冲」。
 */
object Motion {

    /**
     * 动画开关可注入，
     * 让 JVM 单测能覆盖减动效的坍缩分支。
     *
     * 判据用 [ValueAnimator.areAnimatorsEnabled] 而不是直接读
     * `Settings.Global.ANIMATOR_DURATION_SCALE`：前者能反映
     * `adb shell settings put global animator_duration_scale 0`，后者不能，
     * 而那条命令正是用来验「关掉动画后不卡中间态」的。
     *
     * 本项目锁定的 Compose 1.10.4 既没有 LocalMotionDurationScale，
     * Animatable 也不读 MotionDurationScale —— 框架层没有可挂载的坍缩点，只能在本层做。
     */
    internal var animatorsEnabled: () -> Boolean = { ValueAnimator.areAnimatorsEnabled() }

    /** 系统关掉动画时为 true：所有位移与淡入淡出退化为瞬时切换。 */
    val reduced: Boolean get() = !animatorsEnabled()

    /**
     * 退场动画是否值得等。
     *
     * 凡「先播退场再 dismiss」的地方（Dialog/Sheet 的延后撤销）都必须用它分叉：
     * 减动效下要**跳过等待**，而不是等一个不会到来的完成回调 ——
     * 后者会把界面永久卡在「正在退出」里，比没有动画更糟。
     */
    val exitsAreAnimated: Boolean get() = !reduced

    // ── 页面转场 ──

    /**
     * 新页面滑入。
     *
     * **进场页必须 NoBouncy**：滑动的终点是 x=0，任何过冲都会把页面推过终点、
     * 在 trailing 边缘露出窗口底色 —— 整宽的页面滑动上就是一次白闪，
     * 而且恰好闪在用户返回时正要看的这一侧。
     * 「有重量」的过冲因此给退出页的缩放，不给进场页的平移。
     *
     * StiffnessLow(200f) + NoBouncy 的收敛时间约 4.6/√200 ≈ 325ms，
     * 与原来的 300ms tween 同级，但起步更利索、尾段更软。
     */
    fun pageEnter(): FiniteAnimationSpec<IntOffset> =
        offsetSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessLow)

    /** 旧页面滑出：反正要离开屏幕，可以比进场更硬（约 230ms 收敛）。 */
    fun pageExit(): FiniteAnimationSpec<IntOffset> =
        offsetSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    /**
     * 滑动的视差幅度：新页只走容器宽度的这一成，剩下靠淡入补足。
     *
     * 满幅位移读起来像「一张纸被推走」，四分之一读起来像「一层被掀开」，
     * 后者更接近系统的 push，也把可见错位的时间压到最短。
     */
    const val slideParallax: Float = 0.25f

    // ── 弹层与卡片落位 ──

    /** 底部面板落位：行程短，允许一点点过冲。 */
    fun sheet(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)

    /**
     * 底部面板**滑入滑出**的位移谱子（`slideIn/OutVertically` 要 IntOffset，与上面的 Float 版不能混用）。
     *
     * 这里刻意 NoBouncy：悬浮底栏的终点贴着屏幕下缘，向上过冲会在底栏和屏幕边之间
     * 露出一条空隙（玻璃条下方直接看见页面），那不是「有重量」，那是漏底。
     */
    fun sheetSlide(): FiniteAnimationSpec<IntOffset> =
        offsetSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    /** 卡片在网格或列表里换槽落位。 */
    fun cardSettle(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)

    /**
     * 句卡**提交飞离**。松手速度由调用点作为 `initialVelocity` 传给 `animateTo`
     * —— 甩得越快飞得越快，这是改造前 `tween(300)` 永远给不出的「有重量」：
     * 不带初速度的飞离只像定时器，一次用力的短促 flick 和一次缓慢拖拽得到同样的离场速度。
     * NoBouncy：终点在屏幕外，过冲虽看不见，但会让最后一帧反向抖一下。
     */
    fun cardFly(): FiniteAnimationSpec<Float> =
        if (reduced) snap()
        else spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    /**
     * 句卡**回弹**（没达到提交条件，卡片回到原位）。
     *
     * 允许一点过冲：像松开的橡皮筋。同样由调用点传入初速度，
     * 所以「拖到一半松手」和「快速顶到边界又缩回」的回弹手感不一样。
     */
    fun cardRebound(): FiniteAnimationSpec<Float> =
        if (reduced) snap()
        else spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)

    /**
     * 列表项入场的上滑。
     *
     * 保持 NoBouncy + StiffnessMedium 是刻意的：改造前这里根本没传 spec，
     * 走的正是库默认 spring()（同参数），换掉就等于顺手改了观感。
     */
    fun itemSlide(): FiniteAnimationSpec<IntOffset> =
        offsetSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)

    /**
     * flick 提交的速度线，单位 dp/s。
     *
     * 纵向翻卡与横向标题切换共用这**一条**：两处的「甩一下就切」必须是同一种力度，
     * 各写一个数字的话，用户永远会先撞见较迟钝的那一个。
     * 低于它仍按距离判提交，高于它就不要求拖完（法则一：速度是手指的意图）。
     */
    const val flingCommitDpPerSec: Float = 600f

    /**
     * 列表项换位（`animateItem` 的 placementSpec）。
     *
     * NoBouncy：一行滑向相邻行时，过冲会短暂压住上下两行的边，读起来像叠字而不是移动。
     * 刚度取 StiffnessMediumLow —— 与库默认同一档，所以这次收拢不改观感，
     * 改的是「谁规定这个数」：词表里那份能被减动效坍缩成 snap，库默认那份不能。
     */
    fun listPlacement(): FiniteAnimationSpec<IntOffset> =
        offsetSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    // ── 按压与抬起 ──

    /**
     * 按压缩放的谱子。
     *
     * MediumBouncy(0.5f) + StiffnessMedium(1500f)：首次过峰约
     * π/(ω₀·√(1-ζ²)) ≈ 93ms —— 按下时「到达」很快，松手时带一次极小的回弹。
     * 回弹是有意留的：看不见、摸得着，就是法则四说的重量。
     */
    fun press(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)

    /** 卡片被拿起时的抬起缩放，比按压更柔，避免高频交互里发抖。 */
    fun lift(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)

    /** 开关拇指的短距离平移（约 20dp 行程），要利落。 */
    fun toggleTravel(): FiniteAnimationSpec<Dp> =
        dpSpring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)

    /**
     * 全屏浮层进出场那 14dp 上下位移（`animateDpAsState` 要 Dp 版弹簧）。
     *
     * NoBouncy：浮层终点贴着屏幕边，过冲会在边沿露出底下的页面 ——
     * 那一次「露底」比动画本身显眼得多。
     */
    fun dialogTravel(): FiniteAnimationSpec<Dp> =
        dpSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    /**
     * 悬浮元素避让底栏的那段 padding 位移。
     *
     * 这条动画**驱动的是布局**（padding 会重测），所以比其他位移更该安静：
     * NoBouncy —— 让按钮先越过落点再退回来，正好落在用户盯着看的那一下。
     */
    fun barAvoidTravel(): FiniteAnimationSpec<Dp> =
        dpSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    /**
     * 底部导航栏滑块按 index 落位，一格 tab 宽的行程。
     *
     * LowBouncy + StiffnessMediumLow 是从改造前的裸 float
     * `spring(0.6f, 380f)` 平移过来的（400f ≈ 380f）—— 底栏是每次切页都要露面的东西，
     * 收拢成词表时**不许**顺手改手感，所以这里保留原参数而不是复用别的档。
     */
    /**
     * 首页卡片换槽的落位位移。
     *
     * 改造前是 `animateDpAsState(slotX, tween(220))`：一条与手指无关的定时补间，
     * 读起来是"被系统搬过去"而不是"落回格子里"。允许一次极轻的过冲（LowBouncy）——
     * 全 app 只有这一处允许：卡片落到新格子时那半格回弹正是 iOS 主屏给的反馈。
     */
    fun slotTravel(): FiniteAnimationSpec<Dp> =
        dpSpring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)

    /**
     * 松手后把「拖出量」弹回零。**必须与 [slotTravel] 是同一条弹簧**：
     * 卡片位置 = 槽位(弹簧到新格) + 拖出量(弹簧到 0)，两条同 spec 相加才等于
     * 从手指离开的那一点平滑落到新格 —— 分开写、或其中一条用定时补间，就会在松手那一帧跳。
     *
     * 拖拽进行中这条弹簧要被 `snap()` 顶掉（让它逐帧跟住手指），松手翻回本 spec 才从
     * "手指离开点"起算 —— 见 HomeCardCanvas 的 settleX/settleY。
     */
    fun slotSettle(): FiniteAnimationSpec<Dp> =
        dpSpring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)

    /**
     * [slotSettle] 的像素版：标签行那种直接写进 `graphicsLayer.translationY` 的位移是 Float，
     * 不是 Dp，所以只能另开一个同名参数的谱子。**两条弹簧的参数必须与 [slotSettle] 一致**，
     * 改了这边要改那边 —— 连续性的推导（t=0 落在手指离开点）依赖它们同一条曲线。
     */
    fun slotSettleFloat(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioLowBouncy, Spring.StiffnessMedium)

    /**
     * animateContentSize 这类「布局真的要变」的尺寸补间。
     * 走弹簧（位移类），减动效下同样坍缩成 snap。
     */
    fun contentSize(): FiniteAnimationSpec<IntSize> =
        if (reduced) snap() else spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    fun sliderIndex(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)

    /**
     * 预测性返回手势被取消时，界面从半途回位。
     *
     * 必须是弹簧而不是直接归零：手势解除那一刻界面停在进度对应的中间态，
     * 硬写回 0 会跳一下 —— 而「跳」读起来像丢帧，不像「东西回来了」。
     * 也不给过冲以外的弹性：回位是撤销，不是揭示。
     */
    fun gestureRecover(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)

    /**
     * 首页下拉揭示区的回弹（松手后从拉出的行程收回 0）。
     *
     * NoBouncy：收回的终点是屏幕顶边，过冲会把品牌区往**下**再顶一下，
     * 读起来像没停住；这一条要的是「吸回去」而不是「弹回来」。
     */
    fun pullRecoil(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    /**
     * 弹层放大的缩放谱子（ModePickerPopup 一族）。
     *
     * 用 NoBouncy：页面此刻还没被认作「已经打开」，末尾多摆一下会被读成掉帧。
     */
    fun reveal(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

    /**
     * 浮起/弹出容器**收走**时的进度推进。
     *
     * 与 [reveal] 同族但更硬一档：这里推的是 0..1 的进度值，不是位移本身，
     * 所以两条都必须 NoBouncy —— 进度一旦过冲越过 1，下游用它换算出的缩放会大于 1，
     * 容器会被弹得比目标尺寸还大（那是「弹过头」而不是「有重量」）。
     * 退场比进场快，遵循「旧东西要赶紧离开」这条。
     */
    fun revealOut(): FiniteAnimationSpec<Float> =
        floatSpring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)

    /**
     * 缩放幅度按元素尺寸分档：越大的东西按下去收得越少，
     * 否则整屏跟着抖，而小图标按下去又几乎看不出。
     */
    /**
     * 预测性返回手势进行中，页面被「掀走」的收缩比例。
     *
     * 进度 0→1 对应倍数 1→(1-[predictiveBackShrink])。给手势做缩放而不是平移：
     * 平移要跟手指算距离，缩放只需进度，且和系统的 scale-by-95% 返回动画同一语言。
     */
    const val predictiveBackShrink: Float = 0.08f

    /** 由返回手势进度算出当前缩放倍数（[predictiveBackShrink] 的换算，避免各页各写系数）。 */
    fun backShrinkScale(progress: Float): Float = 1f - predictiveBackShrink * progress

    object Scale {
        /** 面板内条目、按钮这类中小容器。 */
        const val container = 0.97f

        /** 信息卡片。 */
        const val card = 0.96f

        /** 圆形图标按钮：行程短，收得多一点才看得见。 */
        const val icon = 0.90f

        /** 悬浮底栏滑块：改成**缩小**，与「选中」用的放大区分开。 */
        const val slider = 0.95f

        /** 卡片被拿起时的抬起倍数（大于 1）。 */
        const val lifted = 1.03f


        /** 对话框入场从多大开始（配 [Motion.press]）。 */
        const val enterFrom = 0.90f

        /** 弹层退场缩到多少。 */
        const val dismissTo = 0.96f
    }

    /**
     * 按下时的亮度：缩放在走、亮度同时压一点，两者是**一个动作**。
     *
     * 只给图标/按钮这类小元素用（见 pressFeedback），整屏面不适用。
     */
    const val pressedAlpha: Float = 0.92f

    /** 悬停或键盘焦点下的亮度：比按下轻，但足以认出「焦点在这一格上」。 */
    const val hoverAlpha: Float = 0.96f

    /** 卡片被拿起时的轻微淡出，与 [Motion.Scale.lifted] 一起表意「这张浮起来了」。 */
    const val liftAlpha: Float = 0.96f

    /**
     * 首页卡片进入管理/编辑态时压到多暗。
     *
     * 压暗是为了让角上的白色操作钮成为视觉重心；但它是**状态切换**而不是位移，
     * 所以要补间着过去（alpha=tween）—— 布尔硬切会让一叠卡片「唰」地暗掉，像掉帧。
     */
    const val editDimAlpha: Float = 0.5f

    // ── 内部工厂：三种位移类型各一个，减动效时返回 snap ──

    private fun offsetSpring(dampingRatio: Float, stiffness: Float): FiniteAnimationSpec<IntOffset> =
        if (reduced) snap() else spring(dampingRatio, stiffness)

    private fun floatSpring(dampingRatio: Float, stiffness: Float): FiniteAnimationSpec<Float> =
        if (reduced) snap() else spring(dampingRatio, stiffness)

    private fun dpSpring(dampingRatio: Float, stiffness: Float): FiniteAnimationSpec<Dp> =
        if (reduced) snap() else spring(dampingRatio, stiffness)
}

/**
 * 透明度与色彩的时长表。
 *
 * 这里**故意**继续用 tween：alpha 与色彩没有位移，弹簧无从表达重量，
 * 用弹簧反而会在终点附近抖 —— 法则三排除的正是这一类。
 *
 * 原先散在 19 处的数字按角色收拢：入场比退场长（看得清来处、不留残影），
 * 图表的数据揭示不属于交互、允许更慢。
 */
object MotionFade {
    /** 根 tab 之间的交叉淡出。它不是动效，是为了杀掉新旧两页同帧叠加出的标题残影。 */
    const val tabCross = 100

    /** 图标按钮按压时的亮度衰减：要短于弹簧的到达时间，否则先亮一下再暗。 */
    const val pressAlpha = 110

    /** 弹层退场淡出。 */
    const val exit = 120


    /** 标准淡入：控件显隐、覆盖层出现。 */
    const val enter = 180

    /** 列表项与下拉揭示区的淡入，比控件显隐稍长，配合上滑才不像一闪。 */
    const val itemEnter = 220

    /**
     * 冷启动启动屏的退场时长。播这段时间时首帧已经画上屏了，启动屏只是淡走让位，
     * 所以它必须短于「启动已完成」的感知阈值 —— 长了像在拖延。
     */
    const val splashExit = 260

    /**
     * 主题/强调色切换时整页底色的溶开时长。
     * 系统栏图标极性在它的一半处翻转 —— 早了字在浅色底上变白看不见，晚了像两个动画。
     */
    const val themeSwitch = 260

    /** 拖拽命中高亮这类「需要被看见但不该打扰」的色彩变化。 */
    const val highlight = 600

    /**
     * 图表数据揭示（雷达、衰减曲线、条形），非交互，允许慢。
     * 三个图表以前各写各的（700 / 800 / 900），同一类「数据长出来」的动作有三种节奏。
     */
    const val chartReveal = 800

    /** 填空提示的「慢慢亮起来」：故意慢到近乎不觉，只为了让眼睛跟上被遮住的那个字。 */
    const val hintLinger = 5000

    /** 练习计分那一类的揭示：比整页图表快一档，因为它跟在手指后面。 */
    const val scoreReveal = 400

    /** 切 tab 时给取色层的第一段：先压一档（配合 [switchSettle] 回满）。 */
    const val switchDip = 70

    /** 列表项按 index 递进的间隔。 */
    const val staggerStep = 30

    /** 递进的上限：超过它就同步出现，否则长列表末尾要等两秒。 */
    const val staggerCap = 320

    /**
     * 数值型补间：alpha 与「进度比例」这类没有位移可言的 0..1 量都走它。
     * 减动效时坍缩成 snap（同一条判据，别处不用各自判断）。
     */
    fun number(durationMs: Int, delayMillis: Int = 0): FiniteAnimationSpec<Float> =
        if (Motion.reduced) snap() else tween(durationMs, delayMillis)

    fun alpha(durationMs: Int, delayMillis: Int = 0): FiniteAnimationSpec<Float> =
        number(durationMs, delayMillis)

    fun color(durationMs: Int): FiniteAnimationSpec<Color> =
        if (Motion.reduced) snap() else tween(durationMs)
}

/** 列表项按 index 递进的延迟毫秒数，封顶在 [MotionFade.staggerCap]。 */
fun staggerDelay(index: Int): Int =
    minOf(index * MotionFade.staggerStep, MotionFade.staggerCap)

/**
 * Lazy 列表项的统一动效：入场淡入 tween、换位位移弹簧、退场淡出 tween。
 *
 * 用这个而不是各页直接写 `Modifier.animateItem()`：
 * 库默认三件套全是弹簧（连淡入淡出也是），而词表要求 alpha 走 tween；
 * 更要紧的是减动效（`animator_duration_scale = 0`）下要能由我们自己坍缩成 snap。
 * 三处列表（搜索 / 文章 / 统计）用同一份，删除与重排的节奏才彼此一致。
 */
fun LazyItemScope.animateListItem(): Modifier = Modifier.animateItem(
    fadeInSpec = MotionFade.alpha(MotionFade.itemEnter),
    placementSpec = Motion.listPlacement(),
    fadeOutSpec = MotionFade.alpha(MotionFade.exit),
)

/**
 * 交错网格项的同一条三件套。
 *
 * 单独一个重载不是凑数：`LazyStaggeredGridItemScope` 是另一个 receiver，
 * 它的 `animateItem` 与 LazyList 的那个没有继承关系 ——
 * 双列文章列表（交错网格）和单列（LazyColumn）必须共用同一组 spec，
 * 否则切到平板横屏时删除/筛选的节奏会和手机上不一样。
 */
fun LazyStaggeredGridItemScope.animateListItem(): Modifier = Modifier.animateItem(
    fadeInSpec = MotionFade.alpha(MotionFade.itemEnter),
    placementSpec = Motion.listPlacement(),
    fadeOutSpec = MotionFade.alpha(MotionFade.exit),
)

/** 状态面切换用的溶开：进场淡入 + 退场淡出，两条都是 tween。 */
fun stateSwap(): ContentTransform =
    fadeIn(MotionFade.alpha(MotionFade.enter)) togetherWith fadeOut(MotionFade.alpha(MotionFade.exit))

/**
 * 状态面切换（空态 ⇄ 内容、加载 ⇄ 就绪、模式 A ⇄ 模式 B）的整块溶开。
 *
 * 只有淡入淡出、不给位移：这两块内容讲的是同一件事的两种状态，
 * 滑过去会读成"换了一页"；而 alpha 按词表本来就该走 tween。
 * 进场（180）比退场（120）长 —— 新内容要"浮现"，旧内容要"撤得快"，反过来会糊成一团。
 * 减动效下两条 spec 各自坍缩成 snap，切换仍然是瞬时的，不会停在半透明。
 *
 * 不要用在：
 * - LazyColumn 的 item 内部 —— 那里 [animateListItem] 已经在管节奏，两层动画会打架；
 * - 包着 AndroidView 玻璃采样的子树 —— 转场期间新旧两份同时组合会打断 bind（见 GlassPlate 注释）。
 *
 * 两条 AnimatedContent 的尺寸约束（套之前先确认分支形状，别硬套）：
 * 1. **`weight` 在它里面是失效的**：`LayoutWeightNode` 只实现 `modifyParentData`，
 *    而 AnimatedContent 不是 Row/Column，没有谁去消费这个 weight。
 *    所以原来靠 `.weight(1f)` 撑满剩余高度的分支，要把 weight **上提到本函数的 modifier 参数**上；
 *    上提之后 AnimatedContent 拿到的就是精确高度约束，它会原样传给每个状态根节点，
 *    分支里那行失效的 weight 留着不影响观感（但别再指望它撑高度）。
 * 2. **一个分支只能有一个根**：AnimatedContent 把每个状态的根节点叠放在同一个盒子里，
 *    分支写成 `Spacer + Content` 这种两个兄弟节点时，间距会凭空消失、两块还会重叠。
 *    这类站点要么先把分支收进单个容器再套，要么就别套。
 */
@Composable
fun <T> StateSwap(
    targetState: T,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = { stateSwap() },
        modifier = modifier,
        label = label,
        content = content,
    )
}
