// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT
//
// 手写单字预处理：与 Ismantic/Handwritten (Apache-2.0) 的 src/cpp/preprocess.c 保持一致。
// 训练侧（Python）与推理侧（Android）共用同一份预处理实现，避免预处理漂移。
//
// 流程：
//   1. 在灰度图上找前景（像素 < HCCR_FG_THRESHOLD）的包围盒
//   2. 裁剪包围盒
//   3. 长边缩放到 HCCR_CONTENT_SIZE（双线性，等价 PIL）
//   4. 居中贴到 HCCR_CANVAS_SIZE 的白底画布
//   5. 反相并归一化到 [0,1]

#ifndef BLANCALL_HCCR_PREPROCESS_H
#define BLANCALL_HCCR_PREPROCESS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** 前景阈值：灰度值小于该值的像素被视为笔画 */
#define HCCR_FG_THRESHOLD 220

/** 缩放置入的内容边长（像素） */
#define HCCR_CONTENT_SIZE 56

/** 最终画布边长（像素），即模型输入尺寸 */
#define HCCR_CANVAS_SIZE 64

/* —— Latin（EMNIST 47 类）模型 —— */

/** Latin 模型输入边长（像素） */
#define HCCR_LATIN_CANVAS_SIZE 28

/**
 * Latin 缩放置入的内容边长。
 *
 * EMNIST 官方 28×28 图里，手写字符的实际占位约 20×20（四周各留 4px）。
 * 端侧先把字符裁到包围盒再放大，所以这里取 20 才能还原到同一尺度 ——
 * 取 24 会让字符比训练分布大两成。
 */
#define HCCR_LATIN_CONTENT_SIZE 20

/**
 * 把灰度手写图预处理成 Latin（EMNIST）模型输入。
 *
 * 与中文版的差异（都必须与训练侧一致，否则静默掉精度）：
 *   - 画布 28×28、内容 20×20（EMNIST 口径：字符四周各 4px 空白）
 *   - 画布是**黑底白字**（0 = 背景，255 = 笔画）—— EMNIST 数据集的极性
 *   - 归一化到 [-1,1]：(v/255 - 0.5) / 0.5，等价 torchvision Normalize((0.5,),(0.5,))
 *   - 无需旋转：训练侧已把 EMNIST 的存储朝向矫正成自然书写朝向
 *
 * @param out  输出缓冲，长度 >= 28*28
 * @return 1 成功；0 整图无前景
 */
int hccr_preprocess_latin(const uint8_t* gray, int w, int h, float* out);

/**
 * 把灰度手写图预处理成模型输入。
 *
 * @param gray 灰度图数据，长度 w*h，白底黑字（0 = 黑笔画，255 = 白底）
 * @param w    图宽
 * @param h    图高
 * @param out  输出缓冲，长度必须 >= HCCR_CANVAS_SIZE * HCCR_CANVAS_SIZE，
 *             写入归一化后的 float（0 = 白底，1 = 笔画）
 * @return 1 表示找到前景并成功处理；0 表示整图无前景（out 已清零）
 */
int hccr_preprocess(const uint8_t* gray, int w, int h, float* out);

#ifdef __cplusplus
}
#endif

#endif  // BLANCALL_HCCR_PREPROCESS_H
