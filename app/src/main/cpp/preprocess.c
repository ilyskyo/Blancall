// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT
//
// 单字手写识别预处理。
// 实现与 Ismantic/Handwritten (Apache-2.0) 的 src/cpp/preprocess.c 等价，
// 保证训练侧 Python 与端侧 C 的输出一致（该仓库有一致性测试覆盖）。
//
// 改动此处逻辑必须同步核对上游 Python 实现，否则会出现
// 「训练时认得出、端上认不出」的静默精度损失。

#include "preprocess.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

/* ============== helpers ============== */

static int imax_i(int a, int b) { return a > b ? a : b; }
static int imin_i(int a, int b) { return a < b ? a : b; }
static float fmax_f(float a, float b) { return a > b ? a : b; }
static float fabs_f(float x) { return x < 0 ? -x : x; }

static int iround_f(float x) {
    /* round-half-up，与 Python round 行为接近 */
    return (int) (x + (x >= 0.0f ? 0.5f : -0.5f));
}

/* ============== separable bilinear pass（等价 PIL.Image.BILINEAR） ==============
 *
 * 对 in_dim → out_dim 重采样。三角核（linear 衰减）半宽 = max(1, scale)，
 * downscale 时拉宽，upscale 时固定 1。等价 PIL Resample.c::ImagingResample。
 *
 * horizontal=1：把 (srcW × srcH) 缩到 (dstW × srcH)；
 * horizontal=0：把 (srcW × srcH) 缩到 (srcW × dstH)。
 */
static void resample_pass_(
    const uint8_t* src, int srcW, int srcH,
    uint8_t* dst, int dstW, int dstH,
    int horizontal
) {
    int out_dim = horizontal ? dstW : dstH;
    int in_dim = horizontal ? srcW : srcH;
    float scale = (float) in_dim / (float) out_dim;
    float filterscale = scale > 1.0f ? scale : 1.0f;
    float inv_filterscl = 1.0f / filterscale;

    /* 预算 (xmin, xmax, weights)，内层循环只做 weighted-sum */
    int* xmin = (int*) malloc(sizeof(int) * (size_t) out_dim);
    int* xmax = (int*) malloc(sizeof(int) * (size_t) out_dim);
    int max_k = (int) (2.0f * filterscale + 2.0f);
    float* weights = (float*) malloc(sizeof(float) * (size_t) out_dim * (size_t) max_k);

    if (!xmin || !xmax || !weights) {
        free(xmin);
        free(xmax);
        free(weights);
        return;
    }

    for (int xx = 0; xx < out_dim; xx++) {
        float center = ((float) xx + 0.5f) * scale;
        int xm = imax_i(0, (int) (center - filterscale + 0.5f));
        int xM = imin_i(in_dim, (int) (center + filterscale + 0.5f));
        if (xM <= xm) xM = imin_i(in_dim, xm + 1);
        int len = xM - xm;
        float* w = weights + (size_t) xx * (size_t) max_k;
        float sum = 0.0f;
        for (int x = 0; x < len; x++) {
            float d = (((float) (xm + x)) + 0.5f - center) * inv_filterscl;
            float wv = fmax_f(0.0f, 1.0f - fabs_f(d));
            w[x] = wv;
            sum += wv;
        }
        if (sum > 0) {
            for (int x = 0; x < len; x++) w[x] /= sum;
        }
        xmin[xx] = xm;
        xmax[xx] = xM;
    }

    for (int y = 0; y < dstH; y++) {
        for (int xx = 0; xx < dstW; xx++) {
            int out_idx = horizontal ? xx : y;
            int src_row = horizontal ? y : xx;
            int xm = xmin[out_idx];
            int xM = xmax[out_idx];
            const float* w = weights + (size_t) out_idx * (size_t) max_k;
            float sum = 0.0f;
            for (int x = 0; x < (xM - xm); x++) {
                int v;
                if (horizontal) v = src[src_row * srcW + xm + x];
                else v = src[(xm + x) * srcW + src_row];
                sum += (float) v * w[x];
            }
            int v = iround_f(sum);
            if (v < 0) v = 0;
            if (v > 255) v = 255;
            dst[y * dstW + xx] = (uint8_t) v;
        }
    }

    free(xmin);
    free(xmax);
    free(weights);
}

/* ============== Latin（EMNIST 47 类） ============== */

int hccr_preprocess_latin(const uint8_t* gray, int w, int h, float* out) {
    if (!gray || !out || w <= 0 || h <= 0) return 0;

    /* 1. 前景 bbox（口径与中文版一致：< 220 为笔画） */
    int min_x = w, min_y = h, max_x = -1, max_y = -1;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int v = gray[y * w + x];
            if (v < HCCR_FG_THRESHOLD) {
                if (x < min_x) min_x = x;
                if (x > max_x) max_x = x;
                if (y < min_y) min_y = y;
                if (y > max_y) max_y = y;
            }
        }
    }
    if (max_x < 0) {
        memset(out, 0, sizeof(float) * (size_t) HCCR_LATIN_CANVAS_SIZE * (size_t) HCCR_LATIN_CANVAS_SIZE);
        return 0;
    }

    /* 2. 裁剪 bbox */
    int cropW = max_x - min_x + 1;
    int cropH = max_y - min_y + 1;
    uint8_t* cropped = (uint8_t*) malloc((size_t) cropW * (size_t) cropH);
    if (!cropped) return 0;
    for (int y = 0; y < cropH; y++) {
        memcpy(cropped + (size_t) y * (size_t) cropW,
               gray + (size_t) (min_y + y) * (size_t) w + (size_t) min_x,
               (size_t) cropW);
    }

    /* 3. 长边缩到 HCCR_LATIN_CONTENT_SIZE（EMNIST 口径：字符约占 20/28，四周各 4px 留白） */
    int max_dim = imax_i(cropW, cropH);
    float scale = (float) HCCR_LATIN_CONTENT_SIZE / (float) max_dim;
    int newW = imax_i(1, iround_f((float) cropW * scale));
    int newH = imax_i(1, iround_f((float) cropH * scale));

    uint8_t* tmp = (uint8_t*) malloc((size_t) newW * (size_t) cropH);
    uint8_t* resized = (uint8_t*) malloc((size_t) newW * (size_t) newH);
    if (!tmp || !resized) {
        free(cropped);
        free(tmp);
        free(resized);
        return 0;
    }
    resample_pass_(cropped, cropW, cropH, tmp, newW, cropH, 1);
    resample_pass_(tmp, newW, cropH, resized, newW, newH, 0);

    /* 4. 居中贴到 28×28 **黑底**画布：白字（255 = 笔画），与 EMNIST 极性一致。
     *
     * ⚠️⚠️ 这里**必须先反相再贴**，这是本项目最贵的一个坑：
     * 入参 gray 是「**白底黑字**」（与中文版同一来源：笔画 ≈ 0、纸面 ≈ 255），
     * 而 EMNIST 训练口径是「黑底白字」。若直接 memcpy，整幅图极性反了 ——
     * 模型看到的是「一张涂满的黑块 + 笔画处镂空」，
     * 输出退化成固定的错误类别，真机现象就是「英文字母怎么写都认不出」。
     *
     * 这个 bug 逃过了数值自检：verify_ncnn.py 是拿**已预处理好的张量**喂 ncnn，
     * 绕过了本函数；所以「转换无损 PASS」与「端上认不出」可以同时成立。
     * ⇒ 教训：涉及「训练/推理口径」的改动，自检必须**从原始输入**（灰度图）起步。
     */
    uint8_t canvas[HCCR_LATIN_CANVAS_SIZE * HCCR_LATIN_CANVAS_SIZE];
    memset(canvas, 0, sizeof(canvas));
    int off_x = (HCCR_LATIN_CANVAS_SIZE - newW) / 2;
    int off_y = (HCCR_LATIN_CANVAS_SIZE - newH) / 2;
    for (int y = 0; y < newH; y++) {
        for (int x = 0; x < newW; x++) {
            canvas[(size_t) (off_y + y) * HCCR_LATIN_CANVAS_SIZE + (size_t) (off_x + x)] =
                (uint8_t) (255 - resized[(size_t) y * (size_t) newW + (size_t) x]);
        }
    }

    /* 5. 归一化到 [-1,1]：等价 torchvision Normalize((0.5,),(0.5,)) */
    int total = HCCR_LATIN_CANVAS_SIZE * HCCR_LATIN_CANVAS_SIZE;
    for (int i = 0; i < total; i++) {
        out[i] = ((float) canvas[i] / 255.0f - 0.5f) / 0.5f;
    }

    free(cropped);
    free(tmp);
    free(resized);
    return 1;
}

/* ============== 主入口（中文 3755 类） ============== */

int hccr_preprocess(const uint8_t* gray, int w, int h, float* out) {
    if (!gray || !out || w <= 0 || h <= 0) return 0;

    /* 1. 找前景 bbox（像素 < 220） */
    int min_x = w, min_y = h, max_x = -1, max_y = -1;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int v = gray[y * w + x];
            if (v < HCCR_FG_THRESHOLD) {
                if (x < min_x) min_x = x;
                if (x > max_x) max_x = x;
                if (y < min_y) min_y = y;
                if (y > max_y) max_y = y;
            }
        }
    }
    if (max_x < 0) {
        memset(out, 0, sizeof(float) * (size_t) HCCR_CANVAS_SIZE * (size_t) HCCR_CANVAS_SIZE);
        return 0;
    }

    /* 2. 裁剪 bbox */
    int cropW = max_x - min_x + 1;
    int cropH = max_y - min_y + 1;
    uint8_t* cropped = (uint8_t*) malloc((size_t) cropW * (size_t) cropH);
    if (!cropped) return 0;
    for (int y = 0; y < cropH; y++) {
        memcpy(cropped + (size_t) y * (size_t) cropW,
               gray + (size_t) (min_y + y) * (size_t) w + (size_t) min_x,
               (size_t) cropW);
    }

    /* 3. 长边缩到 56，separable bilinear */
    int max_dim = imax_i(cropW, cropH);
    float scale = (float) HCCR_CONTENT_SIZE / (float) max_dim;
    int newW = imax_i(1, iround_f((float) cropW * scale));
    int newH = imax_i(1, iround_f((float) cropH * scale));

    uint8_t* tmp = (uint8_t*) malloc((size_t) newW * (size_t) cropH);
    uint8_t* resized = (uint8_t*) malloc((size_t) newW * (size_t) newH);
    if (!tmp || !resized) {
        free(cropped);
        free(tmp);
        free(resized);
        return 0;
    }
    resample_pass_(cropped, cropW, cropH, tmp, newW, cropH, 1);
    resample_pass_(tmp, newW, cropH, resized, newW, newH, 0);

    /* 4. 居中贴到 64×64 白底画布 */
    uint8_t canvas[HCCR_CANVAS_SIZE * HCCR_CANVAS_SIZE];
    memset(canvas, 255, sizeof(canvas));
    int off_x = (HCCR_CANVAS_SIZE - newW) / 2;
    int off_y = (HCCR_CANVAS_SIZE - newH) / 2;
    for (int y = 0; y < newH; y++) {
        memcpy(canvas + (size_t) (off_y + y) * HCCR_CANVAS_SIZE + (size_t) off_x,
               resized + (size_t) y * (size_t) newW,
               (size_t) newW);
    }

    /* 5. 反相 + 归一化 → float[64,64] */
    int total = HCCR_CANVAS_SIZE * HCCR_CANVAS_SIZE;
    for (int i = 0; i < total; i++) {
        out[i] = (float) (255 - canvas[i]) / 255.0f;
    }

    free(cropped);
    free(tmp);
    free(resized);
    return 1;
}
