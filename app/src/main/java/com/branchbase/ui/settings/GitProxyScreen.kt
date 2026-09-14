package com.branchbase.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.branchbase.ui.log.Logger
import com.branchbase.ui.profile.SubPageHeader
import com.branchbase.ui.theme.Primer
import com.branchbase.core.RustBridge

/**
 * Git 代理（设置 → 网络 → Git 代理）—— 二级输入页。
 *
 * ## 为什么从主页搬过来（`docs/specs/settings-design.md` §5.5 / §6.4）
 *
 * 改前：代理是设置主页上的一行，点开弹 `AlertDialog` 收 URL；而写入结果
 * （`proxyFeedback`）渲染在**主页最底部**。对话框一关，用户看到的是页尾一行小字 ——
 * 既没和刚才的操作建立联系，也可能被滚出视野。这是规范里点名的反面教材。
 *
 * 改后：输入框进二级页，校验结果**紧贴输入框下方**；失败时给的不只是「失败」，
 * 而是「哪里不对」（缺协议头 / 缺端口）。
 *
 * 落盘行为与改前完全一致（`RustBridge.setGitProxy` + `SettingsKeys.GIT_PROXY`），
 * 没有新增键、没有新增接口。
 */
@Composable
fun GitProxyScreen(onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入 Git 代理设置页", "Compose") }
    val context = LocalContext.current

    var draft by remember { mutableStateOf(gitProxy(context)) }
    var saved by remember { mutableStateOf(gitProxy(context)) }

    // 校验只在「用户动过输入框」后才显示错因 —— 刚进页面就报红是噪音
    val validation = validateGitProxy(draft)
    val showError = !validation.ok

    /** 落盘（与改前完全一致的路径：RustBridge + 同一个 prefs 键）。 */
    fun persist(value: String): Boolean {
        val v = value.trim()
        val ok = RustBridge.setGitProxy(context.cacheDir.absolutePath, v)
        if (ok) {
            SettingsKeys.prefs(context).edit().putString(SettingsKeys.GIT_PROXY, v).apply()
            saved = v
            draft = v
        }
        return ok
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SubPageHeader("Git 代理", onBack)

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {

            SettingsSection("代理地址") {
                SettingsField(
                    label = "libgit2 的 HTTP 代理",
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "http://127.0.0.1:7890 或 socks5://127.0.0.1:1080",
                    isError = showError,
                    feedback = {
                        // 反馈紧贴输入框：这是本页存在的全部理由
                        SettingsFieldFeedback(validation.message, validation.ok)
                    },
                    actions = {
                        TextButton(
                            onClick = { persist(draft) },
                            enabled = validation.ok && draft.trim() != saved,
                        ) { Text("保存", color = if (validation.ok) Primer.AccentText else Primer.TextTertiary) }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                draft = ""
                                persist("")
                            },
                            enabled = saved.isNotEmpty(),
                        ) { Text("清除", color = Primer.TextTertiary) }
                    },
                )
            }

            // 当前生效值：脱敏显示（规范 §6.5）—— 完整地址（可能含账号密码）不出现在列表里
            if (saved.isNotEmpty()) {
                SettingsSection("当前生效") {
                    InfoRow(
                        icon = Icons.Filled.Check,
                        name = "代理地址",
                        value = displayGitProxy(saved),
                        divider = false,
                    )
                }
            }

            SettingsSection("说明") {
                SettingsProse(
                    "仅作用于「本地仓库」的 clone / pull / push（libgit2），普通网页请求不走这里。\n" +
                        "地址里的用户名与密码不会显示出来 —— 列表里只保留 host:port。",
                    divider = false,
                )
            }
        }
    }
}
