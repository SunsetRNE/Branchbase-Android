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
 * 深色下几乎看不见。这里改成显式配色（见 [EditorPalette]）：亮色 = 纯黑、深色 = 纯白。
 * 配色用 `setColorScheme` 整体替换而不是只改一个字段，理由是背景/行号/选区也必须成对切换，
 * 否则深色模式下会留一块白底。
 *
 * **编辑态与只读预览态共用同一份配色**（[readOnly] 只影响 `isEditable`，不参与配色决策）：
 * 上一版这里漏掉的那些颜色（选中文字、行号面板、内联提示、诊断提示、删除线……）恰恰在
 * 「只看不编辑」的预览里最常见，于是预览态看起来还是灰的。现在「哪些 id 必须纯黑 / 纯白」
 * 由 [EditorPalette.PureTextIds] 声明并由单测逐项钉住。
 *
 * @param text 当前文本（受控）
 * @param onTextChange 文本变化回调（编辑器内部修改时触发，程序化 setText 不会回调）
 * @param readOnly 只读 / **预览**模式（用于「查看」态，仍带行号与高亮；配色与编辑态完全一致）
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
                // ⚠️ 配色与「编辑态 / 只读预览态」**无关**：两种模式共用同一份显式色板。
                //    这里是唯一一处可能被写歪的地方（例如给只读态换一套「淡一点」的色），
                //    一旦分叉，预览态就会退回 Sora 默认的 #333333（发灰）。
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
            // 明暗切换：整份配色重设（背景 / 正文 / 行号一起走），不能只改正文色。
            // 判据用 colorScheme.isDark()（而不是记一个 flag）：编辑器实例由 AndroidView 托管，
            // 重组 / 重建后这里必须能从编辑器自身读回真实状态。
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
    // View 自身的底色也要跟着走：只设色板时，首帧 / 滚动露白处会闪一下默认背景
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
 *
 * 覆盖项**只从 [EditorPalette.assignments] 来**（单一真源）：上一版是在这里手写十来行
 * `setColor(...)`，漏掉的那些就退回库默认的灰 / 透明，而且只在只读预览态才露出来。
 * 现在「有哪些颜色、必须是什么值」全在 [EditorPalette] 里，单测逐项钉住。
 */
internal class BranchbaseEditorColorScheme(dark: Boolean) : EditorColorScheme(dark) {

    override fun applyDefault() {
        // 先铺库的默认值（语法高亮等几十项先用它铺底），再逐项覆盖成我们的显式配色
        super.applyDefault()
        EditorPalette.forDark(isDark()).assignments().forEach { (id, color) -> setColor(id, color) }
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
