package com.branchbase.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.branchbase.core.Account
import com.branchbase.core.AccountStatus
import com.branchbase.core.AccountStore
import com.branchbase.core.LocalRepos
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Avatar
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账号管理页（属设置页规范里的「账户」组，见 `docs/specs/settings-design.md` §三）。
 *
 * 支持：新增（外层跳登录流程）、切换、删除、检查（`GET /user` 判定封禁/失效）。
 *
 * 删除语义：只移除账号记录，该账号的本地仓库与任务记录保留 ——
 * 重新添加同 login 的账号后自动重新可见，其他账号始终看不到。
 */
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Activity 作用域：与根 [com.branchbase.ui.auth.LoginFlow] 拿到的是**同一个**实例，
    // 所以这里触发的新增流程，根布局那条流也会收到（见 LoginViewModel.addAccount）
    val loginViewModel: com.branchbase.ui.auth.LoginViewModel = androidx.lifecycle.viewmodel.compose.viewModel()

    var accounts by remember { mutableStateOf(AccountStore.accounts(context)) }
    var currentId by remember { mutableStateOf(AccountStore.current(context)?.id) }
    var checking by remember { mutableStateOf<Set<String>>(emptySet()) }
    var deleteTarget by remember { mutableStateOf<Account?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun reload() {
        accounts = AccountStore.accounts(context)
        currentId = AccountStore.current(context)?.id
    }

    /** 检查单个账号（委托给 [com.branchbase.core.AccountChecks]，与启动检查同一套判定）。 */
    suspend fun check(a: Account) {
        checking = checking + a.id
        com.branchbase.core.AccountChecks.check(context, a)
        checking = checking - a.id
        reload()
    }

    // 新增流程结束（登录成功）后刷新列表：新账号应当立刻出现在这里。
    // 这一步不能省 —— 本页在 Tab 保活的树里，不会因为「离开过」而自动重建。
    val addingAccount by com.branchbase.ui.auth.AddAccountFlow.activeState
    LaunchedEffect(addingAccount) {
        if (!addingAccount) reload()
    }

    // ⚠️ 这里**不能**用 `DisposableEffect { onDispose { finish() } }` 顺手清理 ——
    // 第一版就是这么写的，结果「添加账号」完全没反应：登录界面接管整屏时本页会被
    // dispose，那个 onDispose 恰好把刚置上的标记清掉（等于自己取消自己）。
    // 残留改用「进入时丢弃过期标记」兜底，见 AddAccountFlow.begin 的踩坑记录。

    LaunchedEffect(Unit) {
        Logger.ui("进入账号管理页", "Compose")
        // 上一次「进了新增流程又中途离开」的残留标记在这里丢掉（见 add 的说明）
        if (com.branchbase.ui.auth.AddAccountFlow.dropIfStale()) {
            Logger.ui("丢弃过期的「新增账号」标记（上次进入后未完成）", "Compose")
            loginViewModel.endAddAccount()   // 两条通道一起收，避免不一致
        }
        reload()
        // 进入即探测，但**只探结论陈旧的**（见 AccountChecks.isStale）。
        //
        // 两处改动的理由（1.0.77，真机日志）：
        // ① 原来只跳过「已检查过」的账号，结论一旦是「令牌已失效」，用户每次进设置都会被再报
        //    一次同样的事 —— 同一件事反复说，主观上就成了「老是报失效」；
        // ② 更要紧的是**启动检查的结果原本不生效**：`AccountStore.add` 每次登录都把
        //    `lastCheck` 重置为 0 / `status` 重置为 UNKNOWN，于是启动时那次检查（`MainActivity`
        //    后台线程写在同一个 store 里）刚到设置页就被当成「没查过」，必然重探一遍。
        //    根因已在那侧修掉（见 `AccountStore.add` 的注释），这里靠 isStale 的时间窗口兜住。
        val stale = accounts.filter {
            com.branchbase.core.AccountChecks.isStale(it.status, it.lastCheck)
        }
        stale.forEach { check(it) }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Primer.BackgroundPrimary)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        SubPageHeader("账号", onBack) {
            Text(
                "全部检查",
                fontSize = 13.sp,
                color = if (checking.isEmpty()) Primer.Blue500 else Primer.TextTertiary,
                modifier = Modifier.clickable(enabled = checking.isEmpty()) {
                    scope.launch { accounts.forEach { check(it) } }
                },
            )
        }

        feedback?.let {
            Text(it, fontSize = 12.sp, color = Primer.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        if (accounts.isEmpty()) {
            // weight(1f)：Column 里已经有头部兄弟节点，fillMaxSize() 会按「父容器整高」测量 →
            // 总高超出容器，超出部分画到边界之外（被底部导航栏压住）
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有账号", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text("登录后即可管理本地仓库与任务", fontSize = 12.sp, color = Primer.TextTertiary)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(accounts, key = { it.id }) { a ->
                    AccountCard(
                        account = a,
                        isCurrent = a.id == currentId,
                        isChecking = checking.contains(a.id),
                        repoCount = LocalRepos.count(context, a.login),
                        onCheck = { scope.launch { check(a) } },
                        onSwitch = {
                            if (AccountStore.switchTo(context, a.id)) {
                                reload()
                                feedback = "已切换到 @${a.login}"
                                // 重建 Activity：登录态从 session 键恢复，全 App 切到新账号
                                (context as? android.app.Activity)?.recreate()
                            }
                        },
                        onDelete = { deleteTarget = a },
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Primer.Border, RoundedCornerShape(10.dp))
                            .clickable {
                            // 记一行：这个功能出过一次「点了没反应」，当时日志里查不到任何线索
                            Logger.ui("点「添加账号」→ 进入新增登录流程", "Compose")
                            // 先寄存「我在哪」：新增登录页会整屏接管，本页与整个主界面子树都会被销毁，
                            // 返回时靠这份寄存恢复回来（否则落在个人页主页，而不是这里）
                            com.branchbase.ui.main.MainNavMemory.remember(
                                com.branchbase.ui.main.MainNavMemory.Route(
                                    tab = com.branchbase.ui.main.MainNavMemory.MainTab.HOME,
                                    onProfile = true,
                                    profileSubPage = SubPage.Accounts.name,
                                ),
                            )
                            // 两条都发：loginViewModel 那条是给根布局的（已验证会重组的通道），
                            // AddAccountFlow 是真源；onAdd 保留给宿主做别的钩子
                            loginViewModel.addAccount()
                            onAdd()
                            Logger.ui(
                                "新增流程标记 = ${com.branchbase.ui.auth.AddAccountFlow.active}",
                                "Compose",
                            )
                        }.padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        // 「添加账号」= 再登一个号（当前账号保持登录、凭据不动）。
                        // 它的出口是登录流程里的返回键 → 回到本页，因此这里不再自称「登录」。
                        Text("添加账号", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
                    }
                }
                item {
                    Text(
                        "每个账号的本地仓库与任务互相隔离（repos/{login}/…）。删除账号不会删除它们，重新登录同一账号即可再次看到。",
                        fontSize = 11.5.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    deleteTarget?.let { target ->
        // 同一账号可能同时存在两种登录方式（OAuth + 密钥，见 AccountStore.indexOfSameIdentity），
        // 所以标题与说明都必须点名**哪一种** —— 只说「删除账号 @X」会让人以为两条一起没了
        val sameLoginOthers = accounts.count { it.login.equals(target.login, ignoreCase = true) } - 1
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = {
                Text(
                    "删除「${target.login} · ${target.auth.label}」？",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primer.TextPrimary,
                )
            },
            text = {
                Column {
                    Text("@${target.login} · ${target.auth.label}", fontSize = 12.5.sp, color = Primer.TextSecondary)
                    if (sameLoginOthers > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "同一账号还有 ${sameLoginOthers} 条其它登录方式的记录（账号列表里单独一行），" +
                                "这次只删这一条。",
                            fontSize = 12.sp,
                            color = Primer.TextSecondary,
                            lineHeight = 18.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "该账号名下的本地仓库（${LocalRepos.count(context, target.login)} 个）与任务记录会保留，" +
                            "重新登录同一账号后即可再次看到；其他账号看不到这些数据。",
                        fontSize = 12.sp,
                        color = Primer.TextTertiary,
                        lineHeight = 18.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val login = target.login
                    AccountStore.remove(context, target.id)
                    reload()
                    feedback = "已删除 $login · ${target.auth.label}"
                    deleteTarget = null
                }) { Text("删除", color = Primer.DangerText) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AccountCard(
    account: Account,
    isCurrent: Boolean,
    isChecking: Boolean,
    repoCount: Int,
    onCheck: () -> Unit,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCurrent) Primer.SelectedRow else Primer.BackgroundPrimary)
            .border(1.dp, if (isCurrent) Primer.Blue500 else Primer.Border, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                // avatarUrl：快照缺失时回落会话 user.avatar_url（老版本迁移来的账号只有会话）
                url = account.avatarUrl,
                login = account.login,
                size = 44.dp,
                background = if (isCurrent) Primer.Blue500 else Primer.Gray150,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(account.login, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    if (isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        Badge("当前", Primer.AccentText, Primer.InfoSurfaceSoft, Primer.Border)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text("@${account.login} · ${account.host}", fontSize = 12.sp, color = Primer.TextTertiary)
            }
            Box {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = Primer.TextSecondary,
                    modifier = Modifier.size(22.dp).iconTap { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("检查状态", fontSize = 13.sp) },
                        onClick = { menuOpen = false; onCheck() },
                    )
                    if (!isCurrent) {
                        DropdownMenuItem(
                            text = { Text("切换到此账号", fontSize = 13.sp) },
                            onClick = { menuOpen = false; onSwitch() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除账号", fontSize = 13.sp, color = Primer.DangerText) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // 徽标**横向可滚**：这几个徽标的宽度取决于 login 长度 / 仓库数 / 时间文案，
        // 窄屏（360dp）上本来就会挤到行尾之外。溢出会让整行换行、卡片高度跳动 ——
        // 用户看到的就是「检查时 UI 明显被撑高」。可滚之后宽度变化只影响滚不滚，不影响高度。
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(status = account.status, checking = isChecking)
            Spacer(Modifier.width(6.dp))
            Badge(account.auth.label, Primer.TextTertiary, Primer.Gray150, Primer.Border)
            Spacer(Modifier.width(6.dp))
            Badge("$repoCount 个本地仓库", Primer.TextTertiary, Primer.Gray150, Primer.Border)
            if (account.lastCheck > 0) {
                Spacer(Modifier.width(6.dp))
                Badge(relativeCheck(account.lastCheck), Primer.TextTertiary, Primer.Gray150, Primer.Border)
            }
        }
    }
}

/**
 * 账号状态徽标。
 *
 * ## 为什么不再把文案换成「检查中…」
 *
 * 原来检查时整块换成 `检查中…`：文案、底色、前景全变，徽标宽度也跟着变 ——
 * 而这一行还有「登录方式 / N 个本地仓库 / X 分钟前检查」几个徽标，宽度一变整行就重排，
 * 窄屏上直接溢出到行外，卡片高度随之跳动。用户的观感是「一检查 UI 就被撑高一下」。
 *
 * 现在**文案与配色始终是状态本身**，只在前面加一个固定 10dp 的转圈槽位；
 * 不检查时槽位用等宽 Spacer 占着，于是「检查中」这个状态的变化**完全不改变布局**。
 * 顺带一个好处：检查过程中用户仍能看到**上一次的结论**，而不是被一句「检查中」盖掉。
 */
@Composable
private fun StatusBadge(status: AccountStatus, checking: Boolean) {
    val fg = statusFg(status)
    val bg = statusBg(status)
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, Primer.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 固定槽位：无论检不检查都占同样宽度
            Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(9.dp),
                        color = fg,
                        strokeWidth = 1.5.dp,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Text("● ${status.label}", fontSize = 10.5.sp, color = fg)
        }
    }
}

@Composable
private fun Badge(text: String, fg: Color, bg: Color, border: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 10.5.sp, color = fg)
    }
}

@Composable
private fun statusFg(s: AccountStatus): Color = when (s) {
    AccountStatus.OK -> Primer.SuccessTextStrong
    AccountStatus.INVALID, AccountStatus.SUSPENDED -> Primer.DangerText
    AccountStatus.LIMITED -> Primer.WarningTextStrong
    else -> Primer.TextTertiary
}

@Composable
private fun statusBg(s: AccountStatus): Color = when (s) {
    AccountStatus.OK -> Primer.SuccessSurface
    AccountStatus.INVALID, AccountStatus.SUSPENDED -> Primer.DangerSurface
    AccountStatus.LIMITED -> Primer.WarningSurface
    else -> Primer.Gray150
}

/** 「3 分钟前 / 2 小时前 / 5 天前」 */
private fun relativeCheck(at: Long): String {
    val diff = System.currentTimeMillis() - at
    val min = diff / 60000
    return when {
        min < 1 -> "刚刚检查"
        min < 60 -> "${min} 分钟前检查"
        min < 60 * 24 -> "${min / 60} 小时前检查"
        else -> "${min / (60 * 24)} 天前检查"
    }
}
