package com.branchbase.ui.profile

import androidx.annotation.StringRes
import com.branchbase.R
import com.branchbase.translate.ApiAddress

/**
 * 地址行的**显示名**（模块只给稳定 id，文案必须在 app 层，切英文才会跟着变）。
 *
 * 放在独立小文件里而不是某个页面的私有扩展：设置页（地址弹窗 / 协议弹窗）和
 * 正文页悬浮球都要显示「当前用的是哪家」，两处必须同源，否则会出现
 * 「设置里叫 DeepSeek 官方、气泡里叫 外部模型」。
 */
@get:StringRes
internal val ApiAddress.addressLabelRes: Int
    get() = when (id) {
        "openai" -> R.string.translate_address_openai
        "deepseek" -> R.string.translate_address_deepseek
        "anthropic" -> R.string.translate_address_anthropic
        "google" -> R.string.translate_address_google
        else -> R.string.translate_address_custom
    }
