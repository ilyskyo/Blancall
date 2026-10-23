// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.widget.FrameLayout

/**
 * 液态玻璃的「合成背景画布」（对应 Kyant0 Backdrop 的 CanvasBackdrop 思路）：
 *
 * 底部导航栏玻璃无法直接采样页面内容——玻璃与页面同挂在一棵 ComposeView 树内，
 * 采样含玻璃自身的视图会形成逐帧反馈循环（玻璃折射上一帧的玻璃）。因此改为
 * 一张自绘「氛围光斑」画布：多层径向渐变按主题取色，作为 [com.qmdeve.liquidglass.widget.LiquidGlassView]
 * 的 bind 采样源；折射 / 色散 / 模糊作用于这些光斑，液态玻璃质感依旧完整。
 */
class LiquidBackdropCanvas(context: Context) : FrameLayout(context) {

    /** 一颗径向光斑：颜色 + 相对位置（0..1）+ 半径（相对画布长边） */
    data class Spot(val color: Int, val cx: Float, val cy: Float, val radius: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var spots: List<Spot> = emptyList()

    // shader 只跟「尺寸 + 光斑列表」有关；onDraw 每帧重建 RadialGradient 是纯浪费
    // （两个 int[]/float[] + 渐变对象，光斑多时每帧十几次分配）。
    private var shaderW = -1f
    private var shaderH = -1f
    private var shaderSpots: List<Spot> = emptyList()
    private var shaders: List<Shader> = emptyList()

    init {
        // FrameLayout 默认跳过 onDraw（willNotDraw=true），必须显式打开才能绘制光斑
        setWillNotDraw(false)
    }

    fun setSpots(value: List<Spot>) {
        if (value == spots) return
        spots = value
        shaders = emptyList()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaders = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (spots.isEmpty()) return
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        if (shaders.isEmpty() || shaderW != w || shaderH != h || shaderSpots != spots) {
            val longSide = maxOf(w, h)
            shaders = spots.map { s ->
                RadialGradient(
                    s.cx * w,
                    s.cy * h,
                    s.radius * longSide,
                    intArrayOf(s.color, s.color and 0x00FFFFFF),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            shaderW = w
            shaderH = h
            shaderSpots = spots
        }
        for (shader in shaders) {
            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }
}