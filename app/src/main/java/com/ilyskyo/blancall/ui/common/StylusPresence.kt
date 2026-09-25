// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.common

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 手写笔设备存在性检测（进程级单例，供手写输入选择书写模式）。
 *
 * ## 检测方式与判定依据
 * 查询 [InputManager.getInputDeviceIds] 列出的全部输入设备，**只要存在一台
 * `sources` 含 [InputDevice.SOURCE_STYLUS] 位的设备**即判定「有笔」：
 * 主动式手写笔（S Pen / M-Pencil / USI 笔）在系统里会注册独立的数字化仪输入设备，
 * 其 source 类别就是 SOURCE_STYLUS；纯电容手指/被动触控笔不会产生该设备
 * （被动笔在系统眼里就是手指，无法区分，按「无笔」处理 —— 与真机能力边界一致）。
 *
 * ## 热插拔
 * 注册 [InputManager.InputDeviceListener]，设备增删/变化时重新扫描：
 * - 蓝牙笔（如 M-Pencil）靠近/配对、外接笔解锁插入、部分 EMR 笔离槽出范围，
 *   都会触发设备增删事件 → 判定随之更新；
 * - 判定结果同时发布到 [present]（Compose 订阅，用于文案/提示切换）与 [isPresent]
 *   （`@Volatile` 布尔，供 `InkBoardView.onTouchEvent` 在**落笔时刻**同步读取，
 *   不依赖重组时序）。
 *
 * ## 模式切换语义（与 InkBoardView 的约定）
 * 「落笔时刻」读一次 [isPresent] 决定这一笔的模式（无笔 ⇒ 手指书写 + 仿造笔迹；
 * 有笔 ⇒ 仅手写笔可写）。因此书写过程中插入/拔出笔**不会打断当前笔画**，
 * 下一次落笔起才切换到新模式。
 *
 * ## 挂载
 * [attach]/[detach] 引用计数，由 [HandwritingPanel] 在组合期挂载
 * （手写面板存在 = 有可能书写）；监听回调派发在主线程（注册线程的 Looper）。
 */
object StylusPresence {

    private val _present = MutableStateFlow(false)

    /** 是否存在手写笔设备（Compose 订阅用；随设备热插拔更新） */
    val present: StateFlow<Boolean> = _present.asStateFlow()

    /**
     * 同步快照（无重组依赖）：`InkBoardView` 在落笔时刻直接读取。
     * `@Volatile`：写入发生在主线程回调，读取也可能在事件回调线程。
     */
    @Volatile
    var isPresent: Boolean = false
        private set

    private var refCount = 0
    private var manager: InputManager? = null

    private val listener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refresh()

        override fun onInputDeviceRemoved(deviceId: Int) = refresh()

        override fun onInputDeviceChanged(deviceId: Int) = refresh()
    }

    /** 挂载（引用计数）：首个挂载者注册监听并立即扫描一次。 */
    fun attach(context: Context) {
        refCount++
        if (refCount > 1) return
        val im = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        manager = im
        if (im == null) {
            publish(false)
            return
        }
        im.registerInputDeviceListener(listener, null)
        refresh()
    }

    /** 卸载（引用计数）：最后一个卸载者移除监听；结果保留（避免瞬时读方看到抖动）。 */
    fun detach() {
        if (refCount == 0) return
        refCount--
        if (refCount > 0) return
        manager?.unregisterInputDeviceListener(listener)
        manager = null
    }

    /** 重新扫描全部输入设备并发布结果。 */
    fun refresh() {
        publish(scanStylusDevice(manager))
    }

    private fun scanStylusDevice(im: InputManager?): Boolean {
        val ids = runCatching { im?.inputDeviceIds }.getOrNull() ?: return isPresent
        for (id in ids) {
            val device = runCatching { im?.getInputDevice(id) }.getOrNull() ?: continue
            if (hasStylusSource(device.sources)) return true
        }
        return false
    }

    /** 判定依据（纯函数，单测覆盖）：`sources` 的 STYLUS 类别位是否置位。 */
    internal fun hasStylusSource(sources: Int): Boolean =
        (sources and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS

    private fun publish(value: Boolean) {
        isPresent = value
        _present.value = value
    }
}
