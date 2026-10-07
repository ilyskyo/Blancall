// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 动效词表的三条规则测试（见 [Motion] / [MotionFade]）：
 * 位移用弹簧、alpha 与色彩用补间、系统关掉动画时一律坍缩为 snap。
 *
 * 这里刻意断言**参数关系**而不是具体毫秒数 —— 数字会被调，关系不会：
 * 进场页永不过冲、退场永远不慢于进场，这两条一旦破掉就是回归。
 */
class MotionSpecTest {

    private lateinit var savedAnimators: () -> Boolean

    /**
     * JVM 单测里 `isReturnDefaultValues = true`，真实的
     * [android.animation.ValueAnimator.areAnimatorsEnabled] 会返回 false，
     * 等于所有测试都跑在「减动效」下 —— 所以两个分支都要显式注入。
     */
    @Before
    fun 开启动画() {
        savedAnimators = Motion.animatorsEnabled
        Motion.animatorsEnabled = { true }
    }

    @After
    fun 还原开关() {
        Motion.animatorsEnabled = savedAnimators
    }

    @Test
    fun `位移类谱子在开启动画时都是弹簧`() {
        assertTrue(Motion.pageEnter() is SpringSpec<*>)
        assertTrue(Motion.pageExit() is SpringSpec<*>)
        assertTrue(Motion.itemSlide() is SpringSpec<*>)
        assertTrue(Motion.sheet() is SpringSpec<*>)
        assertTrue(Motion.cardSettle() is SpringSpec<*>)
        assertTrue(Motion.press() is SpringSpec<*>)
        assertTrue(Motion.lift() is SpringSpec<*>)
        assertTrue(Motion.toggleTravel() is SpringSpec<*>)
        assertTrue(Motion.sliderIndex() is SpringSpec<*>)
        assertTrue(Motion.reveal() is SpringSpec<*>)
    }

    @Test
    fun `进场页必须不过冲`() {
        // 过冲会把滑入的页面推过终点、在 trailing 边缘露出窗口底色（一次白闪）。
        val enter = Motion.pageEnter() as SpringSpec<*>
        assertEquals(Spring.DampingRatioNoBouncy, enter.dampingRatio, 0f)
    }

    @Test
    fun `退场永远不慢于进场`() {
        // 刚度越大收敛越快：旧页要赶紧离开，新页可以慢慢落位。
        val enter = Motion.pageEnter() as SpringSpec<*>
        val exit = Motion.pageExit() as SpringSpec<*>
        assertTrue(exit.stiffness >= enter.stiffness)
        assertEquals(Spring.DampingRatioNoBouncy, exit.dampingRatio, 0f)
    }

    @Test
    fun `alpha 与色彩走补间不走弹簧`() {
        assertTrue(MotionFade.alpha(MotionFade.enter) is TweenSpec<*>)
        assertTrue(MotionFade.color(MotionFade.highlight) is TweenSpec<*>)
    }

    @Test
    fun `关掉系统动画时全部坍缩为 snap`() {
        Motion.animatorsEnabled = { false }
        assertTrue(Motion.reduced)
        assertTrue(Motion.pageEnter() is SnapSpec<*>)
        assertTrue(Motion.pageExit() is SnapSpec<*>)
        assertTrue(Motion.press() is SnapSpec<*>)
        assertTrue(Motion.toggleTravel() is SnapSpec<*>)
        assertTrue(MotionFade.alpha(MotionFade.enter) is SnapSpec<*>)
        assertTrue(MotionFade.color(MotionFade.exit) is SnapSpec<*>)
    }

    @Test
    fun `减动效时退场不该被等待`() {
        // 延后撤销的调用方必须靠这个标志跳过等待，而不是等一个不会到来的回调。
        Motion.animatorsEnabled = { false }
        assertFalse(Motion.exitsAreAnimated)
        Motion.animatorsEnabled = { true }
        assertTrue(Motion.exitsAreAnimated)
    }

    @Test
    fun `列表错峰按 index 递进并封顶`() {
        assertEquals(0, staggerDelay(0))
        assertEquals(90, staggerDelay(3))
        assertEquals(MotionFade.staggerCap, staggerDelay(20))
        assertEquals(MotionFade.staggerCap, staggerDelay(1000))
    }

    @Test
    fun `按压有轻微回弹`() {
        // 法则四的重量：阻尼小于 1 才会在松手时回一下。
        val press = Motion.press() as SpringSpec<*>
        assertTrue(press.dampingRatio < Spring.DampingRatioNoBouncy)
    }

    @Test
    fun `手势取消的回位是弹簧而不是硬跳`() {
        // 预测性返回被取消时，界面停在半途；这里必须是会动的谱子，
        // 否则就是「跳一下」；也不能高过冲，撤销不是揭示。
        val recover = Motion.gestureRecover() as SpringSpec<*>
        assertTrue(recover.dampingRatio <= Spring.DampingRatioLowBouncy)
        Motion.animatorsEnabled = { false }
        assertTrue(Motion.gestureRecover() is SnapSpec<*>)
    }

    @Test
    fun `返回手势缩放在 0 到 1 进度间单调且不出界`() {
        assertEquals(1f, Motion.backShrinkScale(0f), 0f)
        assertEquals(1f - Motion.predictiveBackShrink, Motion.backShrinkScale(1f), 0f)
        assertTrue(Motion.backShrinkScale(0.4f) > Motion.backShrinkScale(0.9f))
        // 进度为负只会来自过冲：缩放不得越过 1，否则页面在手指下鼓出去
        assertTrue(Motion.backShrinkScale(-0.1f) > 1f)
    }

    @Test
    fun `落位与拖出量必须是同一条弹簧`() {
        // 松手不瞬移的全部前提：卡片位置 = 槽位(弹簧到新格) + 拖出量(弹簧到 0)。
        // 只有两条 spec 参数一致，相加才在 t=0 恰好等于手指离开点、t=1 恰好等于新格。
        // 谁改了其中一条而没改另一条，这个式子就静默失效 —— 屏幕上就是「松手跳一下」。
        val travel = Motion.slotTravel() as SpringSpec<*>
        val settle = Motion.slotSettle() as SpringSpec<*>
        val settleF = Motion.slotSettleFloat() as SpringSpec<*>
        assertEquals(travel.dampingRatio, settle.dampingRatio, 0f)
        assertEquals(travel.stiffness, settle.stiffness, 0f)
        assertEquals(settle.dampingRatio, settleF.dampingRatio, 0f)
        assertEquals(settle.stiffness, settleF.stiffness, 0f)
        // 允许一次极轻过冲（卡片落到格子那一下该有重量），但绝不能高过冲
        assertTrue(settle.dampingRatio > Spring.DampingRatioMediumBouncy)
        assertTrue(settle.dampingRatio < Spring.DampingRatioNoBouncy)
    }

    @Test
    fun `落位类谱子在减动效下同样坍缩`() {
        // 这三条都喂给 animateDpAsState/animateFloatAsState，但 Animatable 那条路
        // 框架不读 MotionDurationScale，只能本层判 —— 漏一条就是关了动画还在动。
        Motion.animatorsEnabled = { false }
        assertTrue(Motion.slotTravel() is SnapSpec<*>)
        assertTrue(Motion.slotSettle() is SnapSpec<*>)
        assertTrue(Motion.slotSettleFloat() is SnapSpec<*>)
    }

    @Test
    fun `淡入永远比撤出长`() {
        // 状态面切换（StateSwap）与卡片入场的读法都建立在这条上：
        // 新内容要「浮现」，旧内容要「撤得快」，反过来会糊成一团。
        assertTrue(MotionFade.enter > MotionFade.exit)
        assertTrue(MotionFade.itemEnter > MotionFade.enter)
        // 整页换肤与启动屏让位都是「溶」，不该比一个控件的显隐更快
        assertTrue(MotionFade.themeSwitch >= MotionFade.enter)
        assertTrue(MotionFade.splashExit >= MotionFade.enter)
    }

    /**
     * 词表的**全覆盖**守卫：不靠手写清单，而是把 `Motion` / `MotionFade` 里每一个
     * 返回 `FiniteAnimationSpec` 的方法都反射出来逐条判。
     *
     * 为什么必须反射：上面那几条测试是点名式的，谁新加一条谱子却忘了写减动效分支，
     * 点名测试照样全绿 —— 而「关了系统动画还在动」正是这个计划里最贵的一类回归
     * （延后撤销的浮层会等一个不会到来的完成信号）。反射把「漏一条」变成不可能。
     * 泛型参数在 JVM 上被擦除，所以返回类型只能看到 `FiniteAnimationSpec`，
     * 这恰好就是「这是一条谱子」的判据。
     */
    @Test
    fun `词表每一条谱子都遵守两条法则，且没有一条漏网`() {
        val displacement = Motion::class.java.specFactories()
        val fades = MotionFade::class.java.specFactories()
        // 守卫本身不许是空转：词表被清空时这里必须先红
        assertTrue("Motion 里没找到任何谱子（反射失效？）", displacement.isNotEmpty())
        assertTrue("MotionFade 里没找到任何谱子（反射失效？）", fades.isNotEmpty())

        for (m in displacement) {
            assertTrue("${m.name} 是位移类，必须是弹簧", m.callOn(Motion) is SpringSpec<*>)
        }
        for (m in fades) {
            assertTrue("${m.name} 是 alpha/色彩类，必须是补间", m.callOn(MotionFade) is TweenSpec<*>)
        }

        Motion.animatorsEnabled = { false }
        for (m in displacement + fades) {
            assertTrue("${m.name} 在减动效下没有坍缩，浮层会被卡在中间态", m.callOn(owner(m)) is SnapSpec<*>)
        }
    }

    private fun Class<*>.specFactories(): List<java.lang.reflect.Method> =
        methods.filter {
            it.returnType.name == "androidx.compose.animation.core.FiniteAnimationSpec" &&
                !it.name.contains('$')     // 默认参数生成的合成方法
        }

    private fun owner(m: java.lang.reflect.Method): Any =
        if (m.declaringClass == MotionFade::class.java) MotionFade else Motion

    private fun java.lang.reflect.Method.callOn(target: Any): Any? = invoke(
        target,
        *parameterTypes.map { t ->
            (when (t) {
                java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> 0
                java.lang.Float.TYPE -> 1f
                java.lang.Double.TYPE -> 1.0
                else -> error("词表新增了带未知参数的谱子 ${name}，请显式加进测试")
            }) as Any
        }.toTypedArray()
    )
}
