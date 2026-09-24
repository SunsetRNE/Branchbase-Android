package com.branchbase.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 在 `@Composable` 里解析 [LocalizedText]，省掉每个调用点一行
 * `val context = LocalContext.current`。
 *
 * ## 为什么单独一个文件
 *
 * [LocalizedText] 本体是纯数据类，`resolve(context)` 只认 [android.content.Context] ——
 * 这样它才能留在**纯 JVM 单测**里被断言（`ActivityFeedTest` 的 `assertDetail` 就是这么做）。
 * `@Composable` 依赖 Compose runtime，混进同一个文件会让「模型类型」和「渲染便利函数」
 * 绑在一起；分开放，谁需要谁 import。
 *
 * ## 用在哪
 *
 * 相对时间、活动流这类文案的渲染点很密（`shortTime` 一个函数就有 13 处），
 * 每处都取一次 context 是纯样板。取一次就够：
 *
 * ```kotlin
 * Text(shortTime(item.createdAt).resolve())
 * ```
 */
@Composable
fun LocalizedText.resolve(): String = resolve(LocalContext.current)
