package com.branchbase.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * 账号管理页（对齐 `design/account-management-prototype.html`）。
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

    LaunchedEffect(Unit) {
        Logger.ui("进入账号管理页", "Compose")
        reload()
        // 进入即对未检查过的账号做一次探测（启动后的首次检查也走这里）
        accounts.filter { it.status == AccountStatus.UNKNOWN }.forEach { check(it) }
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
                            .clickable { onAdd() }.padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Primer.Blue500, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
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
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除账号「${target.login}」？", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary) },
            text = {
                Column {
                    Text("@${target.login} · ${target.auth.label}", fontSize = 12.5.sp, color = Primer.TextSecondary)
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
                    feedback = "已删除账号 @$login"
                    deleteTarget = null
                }) { Text("删除", color = Primer.Red500) }
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
            .background(if (isCurrent) Color(0xFFF6FAFF) else Primer.BackgroundPrimary)
            .border(1.dp, if (isCurrent) Primer.Blue500 else Primer.Border, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                url = account.avatar,
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
                        Badge("当前", Color(0xFF0A4E9B), Color(0xFFE6F1FF), Color(0xFFA9CDF5))
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
                        text = { Text("删除账号", fontSize = 13.sp, color = Primer.Red500) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(if (isChecking) "检查中…" else "● ${account.status.label}", statusFg(account.status), statusBg(account.status), statusBorder(account.status))
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

@Composable
private fun Badge(text: String, fg: Color, bg: Color, border: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 10.5.sp, color = fg)
    }
}

private fun statusFg(s: AccountStatus): Color = when (s) {
    AccountStatus.OK -> Color(0xFF0B6B2E)
    AccountStatus.INVALID, AccountStatus.SUSPENDED -> Color(0xFF9E1C24)
    AccountStatus.LIMITED -> Color(0xFF7A5B00)
    else -> Primer.TextTertiary
}

private fun statusBg(s: AccountStatus): Color = when (s) {
    AccountStatus.OK -> Color(0xFFE7F8ED)
    AccountStatus.INVALID, AccountStatus.SUSPENDED -> Color(0xFFFDECEC)
    AccountStatus.LIMITED -> Color(0xFFFFF8E5)
    else -> Primer.Gray150
}

private fun statusBorder(s: AccountStatus): Color = when (s) {
    AccountStatus.OK -> Color(0xFFA9E3BC)
    AccountStatus.INVALID, AccountStatus.SUSPENDED -> Color(0xFFF5B5B5)
    AccountStatus.LIMITED -> Color(0xFFE3B341)
    else -> Primer.Border
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
