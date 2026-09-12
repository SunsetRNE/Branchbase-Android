package com.branchbase.editor

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 代码编辑器（Sora Editor 封装）—— 本模块**唯一对外暴露**的组件。
 *
 * 设计意图：
 * - 主应用只依赖这个函数，不直接依赖 Sora Editor 的任何类型 —— 换库/升级只改本模块；
 * - 移除时删本模块 + `settings.gradle.kts` 的 include + 关于页的痕迹行即可。
 *
 * ## 配色（本次修复点）
 *
 * Sora 的默认色板 `TEXT_NORMAL` 是 `#FF333333` 且**不随明暗切换**，于是亮色下发灰、
 * 深色下几乎看不见。这里改成显式配色（见 [EditorPalette]）：亮色 = 深黑、深色 = 亮白。
 * 配色用 `setColorScheme` 整体替换而不是只改一个字段，理由是背景/行号/选区也必须成对切换，
 * 否则深色模式下会留一块白底。
 *
 * @param text 当前文本（受控）
 * @param onTextChange 文本变化回调（编辑器内部修改时触发，程序化 setText 不会回调）
 * @param readOnly 只读模式（用于「查看」态，仍带行号与高亮）
 * @param darkTheme 是否深色。默认跟随系统；App 有「跟随系统 / 浅色 / 深色」三档时，
 *   调用方应传自己的生效值（如 `LocalIsDarkTheme.current`），否则用户在设置里锁定浅色、
 *   而系统是深色时，编辑器会与页面其它部分不一致。
 */
@Composable
fun BranchbaseCodeEditor(
    text: String,
    onTextChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val latestText by rememberUpdatedState(text)
    val latestOnChange by rememberUpdatedState(onTextChange)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CodeEditor(ctx).apply {
                isEditable = !readOnly
                applyEditorPalette(darkTheme)
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
            // 明暗切换：整份配色重设（背景 / 正文 / 行号一起走），不能只改正文色
            if (editor.colorScheme.isDark() != darkTheme) {
                editor.applyEditorPalette(darkTheme)
            }
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

/** 把 [EditorPalette] 落到编辑器上（配色 + View 底色，避免切换主题时闪一下白块）。 */
private fun CodeEditor.applyEditorPalette(dark: Boolean) {
    val palette = EditorPalette.forDark(dark)
    setColorScheme(BranchbaseEditorColorScheme(dark))
    setBackgroundColor(palette.background)
}

/**
 * Sora 色板的「显式覆盖版」。
 *
 * 两个必须记住的点：
 * 1. **`applyDefault()` 会在父类构造器里被回调**（Sora 的设计），因此本函数只能读
 *    `isDark()`，**不能碰子类自己的字段** —— 那时子类字段还没初始化；
 * 2. `EditorColorScheme(boolean)` 是 `protected`，但 `dark` 标志只有它能设 ——
 *    直接 `EditorColorScheme()` 得到的永远是「浅色」实例，`isDark()` 会撒谎，
 *    所以这里必须以子类形式继承它。
 */
internal class BranchbaseEditorColorScheme(dark: Boolean) : EditorColorScheme(dark) {

    override fun applyDefault() {
        // 先铺库的默认值（语法高亮有几十项，不重复造），再覆盖「会发灰」的那些
        super.applyDefault()
        val palette = EditorPalette.forDark(isDark())
        setColor(TEXT_NORMAL, palette.textNormal)
        setColor(WHOLE_BACKGROUND, palette.background)
        setColor(LINE_NUMBER, palette.lineNumber)
        setColor(LINE_NUMBER_BACKGROUND, palette.lineNumberBackground)
        setColor(LINE_NUMBER_CURRENT, palette.lineNumber)
        setColor(CURRENT_LINE, palette.currentLine)
        setColor(SELECTED_TEXT_BACKGROUND, palette.selectionBackground)
        setColor(LINE_DIVIDER, palette.divider)
        setColor(SCROLL_BAR_THUMB, palette.scrollBar)
        setColor(KEYWORD, palette.keyword)
        setColor(COMMENT, palette.comment)
        setColor(LITERAL, palette.literal)
        setColor(OPERATOR, palette.operator)
        setColor(FUNCTION_NAME, palette.functionName)
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
