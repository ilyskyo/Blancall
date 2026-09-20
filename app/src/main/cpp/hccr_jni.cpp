// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT
//
// 单字手写识别 JNI 桥接。
//
// 模型：Ismantic/Handwritten (Apache-2.0)，MobileNetV2 → NCNN INT8，
//       7356 类（HWDB1.0+1.2 全集：7185 汉字 + 171 字母数字符号），自训练 MobileNetV2。
// 推理：NCNN 纯 CPU，无 Google 服务、无网络请求，完全离线。
//
// 生命周期：NativeHandle 在 Kotlin 侧持有，load/net/oracle 的构造与析构配对，
//          避免每次识别都重新解析 param/bin。

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

// 通过 ncnn 官方 CMake 配置引入时，INTERFACE_INCLUDE_DIRECTORIES 已指向
// <abi>/include/ncnn，故直接 include <net.h>（不要再加 ncnn/ 前缀）
#include <net.h>

#include "preprocess.h"

#define LOG_TAG "BlancallHCCR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** 模型输入边长（与 preprocess 的 HCCR_CANVAS_SIZE 保持一致） */
constexpr int kInputSize = HCCR_CANVAS_SIZE;
/** Latin（EMNIST）模型输入边长 */
constexpr int kLatinInputSize = HCCR_LATIN_CANVAS_SIZE;
/** 模型输出类别数（HWDB 全集 7356 类） */
constexpr int kNumClasses = 7356;

/**
 * 识别器句柄。
 *
 * ncnn::Net 与 Extractor 都不是线程安全的，因此所有推理都在 Kotlin 侧
 * 单线程（Dispatchers.Default 单并发）串行调用；此处不做内部加锁。
 */
struct Recognizer {
    ncnn::Net net;
    bool loaded = false;
    std::string paramPath;
    std::string binPath;
};

/** 把 jstring 转成 UTF-8 std::string（不做异常检查，调用方保证非空） */
std::string jstringToUtf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ilyskyo_blancall_data_handwriting_HandwritingRecognizer_nativeCreate(
    JNIEnv* env, jobject /*thiz*/, jstring paramPath, jstring binPath
) {
    auto* rec = new Recognizer();
    rec->paramPath = jstringToUtf8(env, paramPath);
    rec->binPath = jstringToUtf8(env, binPath);

    // 单线程即可：本应用识别是「写完一个字立刻出结果」的短任务，
    // 多线程只会增加线程调度开销并让端侧功耗变差。
    rec->net.opt.num_threads = 2;
    rec->net.opt.use_vulkan_compute = false;  // 纯 CPU：避免部分设备 Vulkan 驱动的兼容问题
    rec->net.opt.use_fp16_packed = true;
    rec->net.opt.use_fp16_storage = true;
    rec->net.opt.use_fp16_arithmetic = true;

    int ret_param = rec->net.load_param(rec->paramPath.c_str());
    int ret_model = rec->net.load_model(rec->binPath.c_str());
    if (ret_param != 0 || ret_model != 0) {
        LOGE("load failed: param=%d model=%d (%s / %s)",
             ret_param, ret_model, rec->paramPath.c_str(), rec->binPath.c_str());
        delete rec;
        return 0;
    }

    rec->loaded = true;
    LOGI("recognizer ready: %s", rec->paramPath.c_str());
    return reinterpret_cast<jlong>(rec);
}

JNIEXPORT void JNICALL
Java_com_ilyskyo_blancall_data_handwriting_HandwritingRecognizer_nativeDestroy(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle
) {
    auto* rec = reinterpret_cast<Recognizer*>(handle);
    if (!rec) return;
    rec->net.clear();
    delete rec;
}

/**
 * 识别一张灰度手写图。
 *
 * @param handle    nativeCreate 返回的句柄
 * @param gray      灰度数据（白底黑字），长度 w*h
 * @param w,h       图尺寸
 * @param topK      要返回的候选数量
 * @return float 数组，长度 topK*2，形如 [index0, prob0, index1, prob1, ...]，
 *         按概率降序。识别失败返回空数组。
 */
JNIEXPORT jfloatArray JNICALL
Java_com_ilyskyo_blancall_data_handwriting_HandwritingRecognizer_nativeRecognize(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray gray, jint w, jint h, jint topK
) {
    auto* rec = reinterpret_cast<Recognizer*>(handle);
    if (!rec || !rec->loaded || !gray) return env->NewFloatArray(0);
    if (w <= 0 || h <= 0 || topK <= 0) return env->NewFloatArray(0);

    jsize len = env->GetArrayLength(gray);
    if (len < w * h) return env->NewFloatArray(0);

    // 拷到 native 缓冲（GetByteArrayElements 可能返回直接指针，但预处理需要连续可写内存）
    std::vector<uint8_t> pixels(static_cast<size_t>(w) * static_cast<size_t>(h));
    env->GetByteArrayRegion(gray, 0, w * h,
                            reinterpret_cast<jbyte*>(pixels.data()));

    // 1) 预处理成 64×64 归一化 float
    std::vector<float> input(static_cast<size_t>(kInputSize) * kInputSize);
    if (hccr_preprocess(pixels.data(), w, h, input.data()) != 1) {
        // 整图无前景（用户只点了一下没写）
        return env->NewFloatArray(0);
    }

    // 2) NCNN 前向
    ncnn::Extractor ex = rec->net.create_extractor();
    ncnn::Mat in(kInputSize, kInputSize, 1);
    memcpy(in.data, input.data(), input.size() * sizeof(float));
    ex.input("in0", in);

    ncnn::Mat out;
    if (ex.extract("out0", out) != 0 || out.empty()) {
        LOGE("extract failed");
        return env->NewFloatArray(0);
    }

    // 3) 取 top-K：out 可能是 7356 长度的概率（已 softmax）或 logits。
    //    两种情况下「取最大」的选择都一致，故无需区分。
    int n = out.w;
    if (n > kNumClasses) n = kNumClasses;
    topK = topK < n ? topK : n;

    std::vector<int> idx(n);
    for (int i = 0; i < n; i++) idx[i] = i;
    std::partial_sort(idx.begin(), idx.begin() + topK, idx.end(),
                      [&out](int a, int b) { return out[a] > out[b]; });

    std::vector<float> result(static_cast<size_t>(topK) * 2);
    for (int i = 0; i < topK; i++) {
        result[i * 2] = static_cast<float>(idx[i]);
        result[i * 2 + 1] = out[idx[i]];
    }

    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(result.size()));
    if (arr) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(result.size()), result.data());
    }
    return arr;
}

/**
 * Latin（EMNIST 47 类）识别：与 nativeRecognize 同一模型句柄，仅预处理不同
 * （28×28、黑底白字、归一化 [-1,1]，见 hccr_preprocess_latin）。
 */
JNIEXPORT jfloatArray JNICALL
Java_com_ilyskyo_blancall_data_handwriting_HandwritingRecognizer_nativeRecognizeLatin(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jbyteArray gray, jint w, jint h, jint topK
) {
    auto* rec = reinterpret_cast<Recognizer*>(handle);
    if (!rec || !rec->loaded || !gray) return env->NewFloatArray(0);
    if (w <= 0 || h <= 0 || topK <= 0) return env->NewFloatArray(0);

    jsize len = env->GetArrayLength(gray);
    if (len < w * h) return env->NewFloatArray(0);

    std::vector<uint8_t> pixels(static_cast<size_t>(w) * static_cast<size_t>(h));
    env->GetByteArrayRegion(gray, 0, w * h,
                            reinterpret_cast<jbyte*>(pixels.data()));

    std::vector<float> input(static_cast<size_t>(kLatinInputSize) * kLatinInputSize);
    if (hccr_preprocess_latin(pixels.data(), w, h, input.data()) != 1) {
        return env->NewFloatArray(0);
    }

    ncnn::Extractor ex = rec->net.create_extractor();
    ncnn::Mat in(kLatinInputSize, kLatinInputSize, 1);
    memcpy(in.data, input.data(), input.size() * sizeof(float));
    ex.input("in0", in);

    ncnn::Mat out;
    if (ex.extract("out0", out) != 0 || out.empty()) {
        LOGE("latin extract failed");
        return env->NewFloatArray(0);
    }

    int n = out.w;
    topK = topK < n ? topK : n;
    if (topK <= 0) return env->NewFloatArray(0);

    std::vector<int> idx(n);
    for (int i = 0; i < n; i++) idx[i] = i;
    std::partial_sort(idx.begin(), idx.begin() + topK, idx.end(),
                      [&out](int a, int b) { return out[a] > out[b]; });

    std::vector<float> result(static_cast<size_t>(topK) * 2);
    for (int i = 0; i < topK; i++) {
        result[i * 2] = static_cast<float>(idx[i]);
        result[i * 2 + 1] = out[idx[i]];
    }

    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(result.size()));
    if (arr) {
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(result.size()), result.data());
    }
    return arr;
}

}  // extern "C"
