package com.branchbase.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.profile.SubPageHeader
import com.branchbase.ui.settings.SettingsKeys
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 「常用仓库」管理页（首页长按标题 / 点右上管理图标进入）。
 *
 * ## 这一页只做一件事：决定首页那 5 个格子放谁、按什么顺序
 * - **选中 = 置顶到首页**，并且顺序就是点选先后（后点的排在后面）；
 * - 最多 [FrequentRepoRules.HOME_LIMIT] 个，选满之后点第 6 个给 Toast 说明原因，
 *   而不是静默无反应（静默失败在真机上看起来就是「点击坏了」）；
 * - 点一下**立刻落盘**：没有「保存」按钮。多一个保存按钮就多一个「返回时忘了保存」的丢数据路径，
 *   而这里每次点击的成本极低（`apply()` 异步写）。
 *
 * ## 为什么列表在这里全量展示、而不是只展示已置顶的
 * 置顶的对象是「我能用的仓库」—— 账号自己持有的 + 有权限/被协作的 + 所属组织与团队里的
 * （`/user/repos?affiliation=...`，见 [MY_REPOS_PATH]）。用户要先看见候选全集才能决定置顶谁，
 * 所以这一页的列表 = 候选全集（与首页同源：同一份 [MY_REPOS_CACHE_KEY] 缓存），选中态叠在列表上。
 *
 * ## 边框颜色就是「选中态」本身（需求原话：改编列表边框颜色来代表是否选中）
 * 未选中：1dp [Primer.Border]（与首页卡片同款描边）；选中：1.5dp [Primer.Blue500] +
 * 6% 蓝底 + 右侧对勾。三者同时变，是因为**只靠边框颜色**区分的话，色弱用户和
 * 强光下的屏幕都读不出来（`NotificationScreen` 的多选行也是这么叠的）。
 */
@Composable
fun FrequentReposScreen(sessionJson: String, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Logger.ui("进入常用仓库管理页", "Compose") }
    val context = LocalContext.current
    val token = runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    val host = runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    val bucket = remember(sessionJson, context) { frequentRepoBucket(context, sessionJson) }

    var repos by remember { mutableStateOf<List<RepoSummary>>(emptyList()) }
    // null 与空列表在存储层是两种意思（见 FrequentRepoStore），进了这一页就统一成「当前勾选集合」
    var selected by remember { mutableStateOf(FrequentRepoStore.selection(context, bucket).orEmpty()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        // 首页刚写过同一份缓存：先拿它渲染，用户看到的是「我已经置顶的那几个」，不是骨架
        if (refreshKey == 0) {
            SettingsKeys.prefs(context).getString(MY_REPOS_CACHE_KEY, null)?.let {
                repos = parseRepoList(it)
                loading = false
            }
        }
        val json = withContext(Dispatchers.IO) { RustBridge.getJson(host, token, MY_REPOS_PATH) }
        if (json != null && !json.startsWith("ERROR:")) {
            repos = parseRepoList(json)
            SettingsKeys.prefs(context).edit().putString(MY_REPOS_CACHE_KEY, json).apply()
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        SubPageHeader(stringResource(R.string.label_manage_frequent_repos), onBack) {
            if (repos.isNotEmpty()) {
                Text(
                    stringResource(R.string.label_selected_of_visible, selected.size, repos.size),
                    fontSize = 13.sp,
                    color = Primer.TextTertiary,
                )
                Spacer(Modifier.width(12.dp))
            }
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.action_refresh),
                tint = Primer.Blue500,
                modifier = Modifier.size(20.dp).iconTap { refreshKey++ },
            )
        }

        // 一行说明 + 清除：这两件事都只跟「当前勾选」有关，所以贴在标题下面、列表上面
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.note_frequent_repos_hint, FrequentRepoRules.HOME_LIMIT),
                fontSize = 12.sp,
                color = Primer.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            if (selected.isNotEmpty()) {
                TextButton(onClick = {
                    selected = emptyList()
                    FrequentRepoStore.save(context, bucket, emptyList())
                }) {
                    Text(stringResource(R.string.action_clear_selection), fontSize = 13.sp, color = Primer.Blue500)
                }
            }
        }

        when {
            loading && repos.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.state_loading), color = Primer.TextTertiary)
            }

            repos.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.state_no_my_repos), fontSize = 13.sp, color = Primer.TextTertiary)
            }

            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(repos, key = { it.fullName }) { repo ->
                    PinnableRepoRow(
                        repo = repo,
                        selected = repo.fullName in selected,
                        onToggle = {
                            if (!FrequentRepoRules.canPin(selected, repo.fullName)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_frequent_repos_limit, FrequentRepoRules.HOME_LIMIT),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                selected = FrequentRepoRules.toggle(selected, repo.fullName)
                                FrequentRepoStore.save(context, bucket, selected)
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

/** 可置顶的仓库行：边框 = 选中态（未选中 1dp Border / 选中 1.5dp Blue500 + 蓝底 + 对勾） */
@Composable
private fun PinnableRepoRow(repo: RepoSummary, selected: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val press = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Primer.Blue500.copy(alpha = 0.06f) else Primer.BackgroundSecondary)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) Primer.Blue500 else Primer.Border,
                shape = shape,
            )
            // indication = null + selectable(role = Checkbox)：整行是一个复选框，
            // 读屏会念「已选中 / 未选中」，比「一个带边框的按钮」准确
            .selectable(
                selected = selected,
                role = Role.Checkbox,
                interactionSource = press,
                indication = null,
                onClick = onToggle,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    repo.fullName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Primer.Blue500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 候选集里混着组织仓库、别人的协作仓库和复刻，徽标在这里比首页更有用
                RepoBadges(repo)
            }
            if (repo.desc.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(repo.desc, fontSize = 13.sp, color = Primer.TextSecondary, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            // 元信息用「图标 + 数字」，不走 `stringResource(标签) + 数字` 拼接：
            // 拼接出来的句子没法翻译（i18n 规范：一条文案只表达一个完整句子）
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (repo.language != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(langColor(repo.language)))
                        Spacer(Modifier.width(4.dp))
                        Text(repo.language, fontSize = 12.sp, color = Primer.TextSecondary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = Primer.IconSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(repo.stars, fontSize = 12.sp, color = Primer.TextSecondary)
                }
                if (repo.forks != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CallSplit, contentDescription = null, tint = Primer.IconSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(repo.forks, fontSize = 12.sp, color = Primer.TextSecondary)
                    }
                }
            }
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            // 纯装饰：整行已是 `selectable(role = Checkbox)`，选中态由读屏自己念，
            // 再挂一个 contentDescription 只会念两遍
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Primer.Blue500,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
