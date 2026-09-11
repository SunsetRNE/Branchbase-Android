package com.branchbase.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 代码编辑器（Sora Editor 封装）—— 本模块**唯一对外暴露**的组件。
 *
 * 设计意图：
 * - 主应用只依赖这个函数，不直接依赖 Sora Editor 的任何类型 —— 换库/升级只改本模块；
 * - 移除时删本模块 + `settings.gradle.kts` 的 include + 关于页的痕迹行即可。
 *
 * @param text 当前文本（受控）
 * @param onTextChange 文本变化回调（编辑器内部修改时触发，程序化 setText 不会回调）
 * @param readOnly 只读模式（用于「查看」态，仍带行号与高亮）
 */
@Composable
fun BranchbaseCodeEditor(
    text: String,
    onTextChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    val latestText by rememberUpdatedState(text)
    val latestOnChange by rememberUpdatedState(onTextChange)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CodeEditor(ctx).apply {
                isEditable = !readOnly
                setText(latestText)
                // 只在「用户编辑」时回传，避免程序化 setText 触发循环
                subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                    if (event.action == ContentChangeEvent.ACTION_INSERT ||
                        event.action == ContentChangeEvent.ACTION_DELETE
                    ) {
                        latestOnChange(this.text.toString())
                    }
                }
            }
        },
        update = { editor ->
            editor.isEditable = !readOnly
            // 外部文本变化（例如切换文件）才同步，避免打断输入
            if (editor.text.toString() != latestText) {
                editor.setText(latestText)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose { /* CodeEditor 由 AndroidView 管理生命周期，无需额外释放 */ }
    }
}

/**
 * 本模块使用的第三方库信息 —— 供「关于」页展示，作为「项目里加了什么」的痕迹。
 *
 * 改库时同步这里即可；移除模块时这行也随之消失。
 */
object EditorModuleInfo {
    const val NAME = "Sora Editor"

    /**
     * 上游版本号：来自版本目录 `libs.versions.toml` 的 `soraEditor`，
     * 经 `build.gradle.kts` 注入成 BuildConfig。**不要在这里手写版本号** ——
     * 手写副本不会随依赖升级而变，且编译/测试都发现不了，只有关于页会悄悄说谎。
     */
    val VERSION: String get() = BuildConfig.SORA_VERSION
    const val MODULE = ":editor"
    const val LICENSE = "LGPL-2.1"
    const val HOMEPAGE = "https://github.com/Rosemoe/sora-editor"
}
