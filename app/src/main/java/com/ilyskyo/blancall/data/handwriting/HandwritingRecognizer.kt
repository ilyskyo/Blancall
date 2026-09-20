// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.data.handwriting

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * 端侧离线单字手写识别器。
 *
 * ## 引擎
 * [Ismantic/Handwritten](https://github.com/Ismantic/Handwritten) —— MobileNetV2 转 NCNN INT8，
 * 3755 类 GB2312 一级字，CASIA HWDB1.1 测试集 top-1 95.47% / top-10 99.58%，
 * 模型 4.1MB，Apache-2.0。
 *
 * ## 为什么不是 ML Kit
 * ML Kit 数字墨水的中文模型（`zh-Hani`，约 20MB）**必须运行时从 Google 服务器下载**，
 * 无法预置进 APK，与「完全单机、无网运行」的产品定位直接冲突。本方案模型随 APK 分发，
 * 首次使用无需联网。
 *
 * ## 线程与生命周期
 * `ncnn::Net` 非线程安全，所有识别串行执行。构造是懒加载的：只有用户真正切到手写输入
 * 时才触发模型加载，避免拖慢冷启动。
 *
 * ## 已知边界
 * 只覆盖 GB2312 一级字 3755 个。古文素材中的生僻字（如「夔」「蠡」）不在表内，
 * 此时识别结果不会命中正确答案 —— 调用方必须依据 [HandwritingResult.isConfident]
 * 决定是否自动判分，低置信时降级为「让用户从候选中点选」。
 */
/** 手写识别的文种：决定用哪套模型与字符表。 */
enum class HandwritingScript {
    /** 中文（GB2312 一级字 3755 类） */
    Chinese,

    /** 英文/拉丁（EMNIST Balanced 47 类：数字 + 大小写字母） */
    Latin;

    companion object {
        /** CJK 统一表意文字区起点：出现任一 CJK 字符即按中文识别 */
        private const val CJK_START = 0x2E80

        /**
         * 按挖空的标准答案自动选择文种。
         * 含任一 CJK 字符 → 中文；纯 ASCII/拉丁 → 英文；
         * **空答案（拿不到标准答案）→ 中文**（本应用默认语言，宁可走主模型）。
         */
        fun forAnswer(answer: String): HandwritingScript =
            if (answer.isEmpty() || answer.any { it.code >= CJK_START }) Chinese else Latin
    }
}

class HandwritingRecognizer private constructor(
    private val context: Context
) {

    // 每个文种一套句柄/字符表：模型与字符表互不相同
    @Volatile
    private var handles = LongArray(HandwritingScript.entries.size)

    @Volatile
    private var charsets: MutableList<List<Char>> =
        MutableList(HandwritingScript.entries.size) { emptyList() }

    private val loadLock = Any()
    private val inferLock = Any()

    /** 指定文种的模型是否已就绪（字符表 + native 句柄均已加载）。 */
    fun isReady(script: HandwritingScript): Boolean =
        handles[script.ordinal] != 0L && charsets[script.ordinal].isNotEmpty()

    /** 中文模型是否已就绪（兼容旧调用方）。 */
    val isReady: Boolean get() = isReady(HandwritingScript.Chinese)

    /**
     * 懒加载指定文种的模型与字符表。可重复调用，仅在首次真正执行。
     *
     * @return 是否加载成功。失败时 [isReady] 保持 false，调用方应回退到键盘输入。
     */
    fun ensureLoaded(script: HandwritingScript): Boolean {
        if (isReady(script)) return true
        synchronized(loadLock) {
            if (isReady(script)) return true
            return try {
                val charsetList = loadCharset(script)
                if (charsetList.isEmpty()) {
                    Log.e(TAG, "charset empty ($script), handwriting disabled")
                    return false
                }
                val dir = extractModelFiles(script)
                val h = nativeCreate(
                    File(dir, paramFileName(script)).absolutePath,
                    File(dir, binFileName(script)).absolutePath
                )
                if (h == 0L) {
                    Log.e(TAG, "nativeCreate failed ($script)")
                    return false
                }
                charsets[script.ordinal] = charsetList
                handles[script.ordinal] = h
                Log.i(TAG, "handwriting ready ($script): ${charsetList.size} classes")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "ensureLoaded failed ($script)", t)
                false
            }
        }
    }

    /** 兼容旧调用方：加载中文模型。 */
    fun ensureLoaded(): Boolean = ensureLoaded(HandwritingScript.Chinese)

    /**
     * 识别一张手写位图。
     *
     * @param bitmap 白底黑字的书写图像（画布导出的原始位图，无需预先缩放）
     * @param script 文种：决定用哪套模型与字符表
     * @param topK   返回候选数量
     * @return 识别结果；未加载或识别失败返回 null
     */
    fun recognize(
        bitmap: Bitmap,
        script: HandwritingScript,
        topK: Int = DEFAULT_TOP_K
    ): HandwritingResult? {
        if (!ensureLoaded(script)) return null
        val gray = toGrayscaleBytes(bitmap) ?: return null

        return synchronized(inferLock) {
            val t0 = System.nanoTime()
            val raw = if (script == HandwritingScript.Latin) {
                nativeRecognizeLatin(handles[script.ordinal], gray, bitmap.width, bitmap.height, topK)
            } else {
                nativeRecognize(handles[script.ordinal], gray, bitmap.width, bitmap.height, topK)
            }
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            if (raw.isEmpty()) return@synchronized null

            val classes = charsets[script.ordinal]
            val candidates = ArrayList<HandwritingResult.Candidate>(raw.size / 2)
            // raw 形如 [index0, prob0, index1, prob1, ...]
            var i = 0
            while (i + 1 < raw.size) {
                val idx = raw[i].toInt()
                val conf = raw[i + 1]
                val ch = classes.getOrNull(idx)
                if (ch != null) {
                    candidates += HandwritingResult.Candidate(index = idx, char = ch, confidence = conf)
                }
                i += 2
            }
            if (candidates.isEmpty()) return@synchronized null

            // 模型输出可能是 logits（未 softmax）：若所有值都不在 [0,1] 或总和远离 1，
            // 则做一次数值稳定的 softmax，保证 confidence 语义统一为概率。
            val normalized = normalizeIfLogits(candidates)
            HandwritingResult(candidates = normalized, elapsedMs = elapsed)
        }
    }

    /** 释放 native 资源。进程退出或手写功能被关闭时调用。 */
    fun release() {
        synchronized(loadLock) {
            HandwritingScript.entries.forEach { s ->
                if (handles[s.ordinal] != 0L) {
                    nativeDestroy(handles[s.ordinal])
                    handles[s.ordinal] = 0L
                }
            }
            charsets = MutableList(HandwritingScript.entries.size) { emptyList() }
        }
    }

    // ========== 内部实现 ==========

    /**
     * 把位图转成 native 预处理所需的灰度字节流（白底黑字）。
     *
     * 用户书写时画布是「白底深色笔画」，但导出位图可能带透明通道，
     * 因此先合成到白底再取灰度，避免透明像素被当成黑色导致整图被判为有前景。
     */
    private fun toGrayscaleBytes(bitmap: Bitmap): ByteArray? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        // 1) 统一到 ARGB_8888 并合成白底
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null

        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        val out = ByteArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // 合成到白底：result = c*a/255 + 255*(1-a/255)
            val rr = (r * a + 255 * (255 - a)) / 255
            val gg = (g * a + 255 * (255 - a)) / 255
            val bb = (b * a + 255 * (255 - a)) / 255
            // 亮度（Rec.601），与训练侧灰度化口径一致
            val lum = (rr * 299 + gg * 587 + bb * 114) / 1000
            out[i] = lum.coerceIn(0, 255).toByte()
        }
        return out
    }

    /**
     * 若模型直接输出 logits，转成概率，保证 confidence 可跨版本比较。
     *
     * 判据：最大值 > 1.0 或总和明显偏离 1（允许 0.9~1.1 的浮点/量化误差）。
     */
    private fun normalizeIfLogits(
        candidates: List<HandwritingResult.Candidate>
    ): List<HandwritingResult.Candidate> {
        val maxVal = candidates.maxOf { it.confidence }
        val sum = candidates.sumOf { it.confidence.toDouble() }
        val looksLikeProbability = maxVal <= 1.0f && sum in 0.5..1.5
        if (looksLikeProbability) return candidates

        // 数值稳定的 softmax（以 max 平移避免 exp 溢出）
        val exps = candidates.map { kotlin.math.exp((it.confidence - maxVal).toDouble()) }
        val total = exps.sum()
        if (total <= 0.0) return candidates
        return candidates.mapIndexed { i, c ->
            c.copy(confidence = (exps[i] / total).toFloat())
        }
    }

    /**
     * 读取字符表。格式见 [HandwritingCharset.parse]；中英两套字符表同构
     * （`char_to_idx` 显式映射），仅目录与类数不同。
     */
    private fun loadCharset(script: HandwritingScript): List<Char> {
        val text = context.assets.open("${assetDirName(script)}/$CHARSET_NAME")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val parsed = HandwritingCharset.parse(text)
        if (parsed == null) {
            Log.e(TAG, "charset.json 解析失败 ($script)，手写功能降级")
            return emptyList()
        }
        return parsed
    }

    /**
     * 把模型文件从 assets 解包到 filesDir。
     *
     * NCNN 的 `load_param` / `load_model` 需要真实文件路径（内部按 FILE* 读取），
     * 无法直接读 assets。解包只在版本变化或文件缺失时执行一次。
     */
    private fun extractModelFiles(script: HandwritingScript): File {
        val dir = File(context.filesDir, assetDirName(script))
        if (!dir.exists()) dir.mkdirs()

        listOf(paramFileName(script), binFileName(script)).forEach { name ->
            val target = File(dir, name)
            val assetPath = "${assetDirName(script)}/$name"
            val assetLen = context.assets.openFd(assetPath).use { it.length }
            if (!target.exists() || target.length() != assetLen) {
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
            }
        }
        return dir
    }

    private fun assetDirName(script: HandwritingScript): String =
        if (script == HandwritingScript.Latin) ASSET_DIR_EN else ASSET_DIR

    private fun paramFileName(script: HandwritingScript): String =
        if (script == HandwritingScript.Latin) "model_en.param" else PARAM_NAME

    private fun binFileName(script: HandwritingScript): String =
        if (script == HandwritingScript.Latin) "model_en.bin" else BIN_NAME

    // ========== native ==========

    private external fun nativeCreate(paramPath: String, binPath: String): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeRecognize(
        handle: Long, gray: ByteArray, w: Int, h: Int, topK: Int
    ): FloatArray

    /** Latin（EMNIST）专用：预处理为 28×28 黑底白字、归一化 [-1,1] */
    private external fun nativeRecognizeLatin(
        handle: Long, gray: ByteArray, w: Int, h: Int, topK: Int
    ): FloatArray

    companion object {
        private const val TAG = "BlancallHCCR"
        private const val ASSET_DIR = "hccr"

        /** Latin（EMNIST）模型的 assets 目录 */
        private const val ASSET_DIR_EN = "hccr_en"
        private const val PARAM_NAME = "model.ncnn.param"
        private const val BIN_NAME = "model.ncnn.bin"
        private const val PARAM_NAME_EN = "model_en.param"
        private const val BIN_NAME_EN = "model_en.bin"
        private const val CHARSET_NAME = "charset.json"
        private const val DEFAULT_TOP_K = 5

        private val nativeLoaded = AtomicBoolean(false)

        /** Latin 模型的 assets 是否随包分发（决定英文空能否走手写） */
        fun hasLatinModel(context: Context): Boolean =
            try {
                context.assets.list(ASSET_DIR_EN)?.contains(PARAM_NAME_EN) == true
            } catch (t: Throwable) {
                false
            }

        /** native 库是否可用。加载失败（如设备 ABI 不匹配）时手写功能整体降级。 */
        val isNativeAvailable: Boolean get() = nativeLoaded.get()

        @Volatile
        private var instance: HandwritingRecognizer? = null

        /** 进程级单例：模型加载代价高，且 ncnn::Net 本就应复用。 */
        fun getInstance(context: Context): HandwritingRecognizer {
            return instance ?: synchronized(this) {
                instance ?: HandwritingRecognizer(context.applicationContext).also {
                    instance = it
                    try {
                        System.loadLibrary("blancall_hccr")
                        nativeLoaded.set(true)
                    } catch (t: Throwable) {
                        Log.e(TAG, "loadLibrary failed; handwriting unavailable", t)
                        nativeLoaded.set(false)
                    }
                }
            }
        }
    }
}
