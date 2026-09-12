// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.algorithm

import java.security.MessageDigest

/**
 * 文章内容指纹（自定义配置锚定用）。
 * 配置保存时记录文章全文的 MD5 hex；再次加载配置时比对，
 * 不一致说明文章内容已被编辑，配置的「索引+区间」位置可能失准。
 */
object ContentFingerprint {

    fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
