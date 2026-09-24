package com.branchbase.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.AccountStore
import com.branchbase.core.RepoCredential
import com.branchbase.core.RepoCredentialStore
import com.branchbase.ui.LocalizedText
import com.branchbase.ui.log.Logger
import com.branchbase.ui.profile.SubPageHeader
import com.branchbase.ui.repository.JUST_NOW_MS
import com.branchbase.ui.repository.shortTime
import com.branchbase.ui.resolve
import com.branchbase.ui.theme.Primer
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 仓库凭据（设置 → 账户 → 仓库凭据）—— 二级管理页。
 *
 * ## 这是什么
 *
 * 「仓库级凭据」是用户**给某个当前账号打不开的私有仓库**单独配的一条令牌。产品口径（见
 * [RepoCredentialStore] 的文档，改之前先确认）：
 *
 * 1. **账号优先** —— 当前账号能打开这个仓库时一律走账号，仓库凭据不参与竞争；
 * 2. **打不开时才回退** —— 只有账号被拒（404 / 403）时，仓库页才改用这里的令牌；
 * 3. **回退后读与写都用它** —— 也就是说这个仓库里的动作身份（@login）**可能与当前账号不同**，
 *    所以仓库页在使用中必须有可见提示（那条横幅由仓库页负责，不在本页）。
 *
 * 本页只做两件事：**看清楚有哪些凭据**、**删掉不再需要的凭据**。
 * 添加不在这里 —— 入口是「打不开私有仓库」的失败页（那时才拿得到 owner/repo 与令牌原文）；
 * 这里放输入框等于逼用户手抄仓库名。
 *
 * ## 规范遵循（`docs/specs/settings-design.md`）
 *
 * - §4.1：行**只**来自 [SettingsRow] 的封闭集合（本页用了 `SettingsProse` / `DangerRow`），
 *   页面里没有一行自制 `Row { … }.clickable { }`；
 * - §7：删除是危险动作 —— `DangerRow` 只负责**打开确认**，确认框标题 = 动词 + 对象名（§4.6），
 *   正文写清「会发生什么 / 影响范围 / 能不能撤销」（§7.3），确认按钮写动词（`删除`）；
 * - §6.1：值列用「未设置」，不用「无」「空」；
 * - §6.5：**令牌永不显示** —— 本页只有 `owner/repo` 与 `@login`，`token` 字段一次都没读。
 */
@Composable
fun RepoCredentialsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 读盘进 remember：本页不进保活（PageSwitcher 每次进入重建），
    // 所以 remember 的时效性等价于「每次进入读一次」—— 与设置主页同一条约定。
    var items by remember { mutableStateOf(RepoCredentialStore.all(context)) }
    var deleteTarget by remember { mutableStateOf<RepoCredential?>(null) }
    // 回退时的身份差：把「当前账号」显式写进说明里，用户才知道会以谁的名义写
    val currentLogin = remember { AccountStore.current(context)?.login }

    LaunchedEffect(Unit) {
        // 页面加载锚点：条数足够定位「用户到底配过几条」，不足的部分靠删除那条日志补
        Logger.ui("进入仓库凭据页：${items.size} 条", "Compose")
    }

    /** 删除一条凭据：落盘 → 刷新列表 → 打锚点日志。 */
    fun delete(target: RepoCredential) {
        val removed = RepoCredentialStore.remove(context, target.host, target.owner, target.repo)
        items = RepoCredentialStore.all(context)
        // 锚点「决策页」：仓库打不开 → 添加 / 删除凭据是同一条链路，出问题时一个词就能全 grep 出来。
        // **令牌绝不进日志**：这里只有 owner/repo 与令牌身份 @login（Store 的文档把这条列为铁律）。
        Logger.local(
            "删除仓库凭据：${target.slug}（@${target.login}）" + if (removed) "已删除" else "未找到（可能已被删过）",
            "决策页",
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        SubPageHeader(stringResource(R.string.nav_repo_credentials), onBack)

        // 规则说明（§4.1 的说明段落，不是行型）：三条规则里只有第一条能靠名字猜出来
        SettingsSection(stringResource(R.string.translate_notes_section)) {
            SettingsProse(
                stringResource(R.string.note_credential_priority) + (currentLogin?.let { " @$it" } ?: stringResource(R.string.nav_account)) + stringResource(R.string.label_period),
                divider = false,
            )
        }

        if (items.isEmpty()) {
            // 空态：一句说明（§6.1 的口气）+ 去哪里添加。
            // 它**不是一行**（没有名称列/值列、不可点、不画 `›`），所以不属于 §4.1 的封闭集合 ——
            // 与账号管理页的空态同一种形态（居中两行字），不会被误读成「又自制了一套行」。
            // 也不复述「未设置」：那是设置主页值列的写法，这里要给的是**出路**。
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.state_no_repo_credentials),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Primer.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.note_add_credential_from_failure),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = Primer.TextTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            SettingsSection(stringResource(R.string.label_credential_list)) {
                items.forEachIndexed { i, c ->
                    // 每条一行：主标题 = owner/repo，副标题 = 令牌身份 @login + 上次使用。
                    // 删除走 DangerRow → 二次确认（§7.2：列表里点危险行只许打开确认）。
                    // `hint` 写动词，和「清空译文缓存」那行的写法一致（名字是对象，动作在右端）。
                    DangerRow(
                        icon = Icons.Filled.Delete,
                        name = c.slug,
                        sub = credentialSubtitle(c, lastUsedLabel(c.lastUsedAt).resolve()),
                        hint = stringResource(R.string.action_delete),
                        onClick = { deleteTarget = c },
                        divider = i != 0,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // 二次确认：标题 = 动词 + 对象名（§4.6）；正文三条 = 会发生什么 / 影响范围 / 能否撤销（§7.3）；
    // 确认按钮写动词（§7.2）。这条动作只删本机凭据、不动 GitHub 上的令牌，所以不需要「高风险加码」。
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text(
                    stringResource(R.string.confirm_delete_credential_title, target.slug),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.TextPrimary,
                )
            },
            text = {
                Text(
                    stringResource(R.string.confirm_delete_credential_body, target.login),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = Primer.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    delete(target)
                }) { Text(stringResource(R.string.action_delete), color = Primer.DangerText) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * 行副标题：令牌身份 `@login` +（必要时）host +「上次使用 …」。
 *
 * **副标题里的身份不是当前账号**，而是这条令牌在 GitHub 上的身份 —— 两者可能不同，
 * 所以这一行必须写清楚是谁（仓库页回退后写操作就以这个身份发生）。
 *
 * host 平时不摆出来（默认 `github.com`，人人如此，写出来只是噪音）；但同一个 `owner/repo`
 * 在两个 host 上各有一条凭据时，两行会长得一模一样 —— 删除是危险动作，
 * 规范 §4.6 要求用户**在按下前能核对对象**，所以这时把 host 显式写出来。
 *
 * [lastUsed] 由调用方**解析好**再传进来（[lastUsedLabel] 返回 [LocalizedText]）：
 * 本函数是普通函数，拿不到 Context，也不该为了拼一行字去要一个。
 */
internal fun credentialSubtitle(c: RepoCredential, lastUsed: String): String = buildString {
    append('@').append(c.login)
    if (!c.host.equals("github.com", ignoreCase = true)) append(" · ").append(c.host)
    append(" · ").append(lastUsed)
}

/**
 * 「上次使用 …」文案。
 *
 * 相对时间的措辞**不自制**：复用仓库页那套 [shortTime]（「刚刚 / N 分钟前 / N 小时前 / N 天前」），
 * 全 App 只有一套说法。它收的是 ISO-8601 UTC 串，而 `lastUsedAt` 是 epoch 毫秒 ——
 * 中间只做一次格式转换：`Instant.ofEpochMilli(...)` 自带小数秒（`…:18.794Z`），
 * 而 [shortTime] 的模板以字面 `Z` 收尾，多出来的小数秒会让 SimpleDateFormat 整串解析失败
 * （回落成原样输出），所以先 `truncatedTo(SECONDS)`。
 *
 * 返回 [LocalizedText]：第三档把相对时间**嵌**进整句（[LocalizedText] 的参数可以是另一个
 * [LocalizedText]），英文语序由 `last_used_at` 的 `%1$s` 决定，而不是靠这里拼字符串。
 */
internal fun lastUsedLabel(lastUsedAt: Long): LocalizedText {
    if (lastUsedAt <= 0L) return LocalizedText(R.string.state_never_used)
    // shortTime 的「刚刚」直接接在「上次使用」后面读不通，这一档单独成词。
    // 判据取自**时间戳本身**，不是去比对 shortTime 的输出文案 ——
    // 那条文案现在是资源，比对它在英文界面下永不成立（这里会永远走「上次使用 刚刚」）。
    if (System.currentTimeMillis() - lastUsedAt < JUST_NOW_MS) return LocalizedText(R.string.state_just_used)
    val iso = Instant.ofEpochMilli(lastUsedAt).truncatedTo(ChronoUnit.SECONDS).toString()
    return LocalizedText(R.string.last_used_at, listOf(shortTime(iso)))
}
