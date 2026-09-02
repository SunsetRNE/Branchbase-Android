package com.branchbase.ui.home

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.branchbase.core.RustBridge
import kotlinx.coroutines.launch
import com.branchbase.ui.theme.Primer
import org.json.JSONObject

/**
 * 首页 Dashboard。
 *
 * 对齐 docs/home-wireframe.md：搜索栏+头像 / 快捷方式 / 我的星标 / 最近活动。
 */
@Composable
fun HomeScreen(
    sessionJson: String,
    onProfileClick: () -> Unit,
    onSearchClick: () -> Unit,
    onRepoClick: (String) -> Unit = {},
) {
    val user = runCatching { JSONObject(sessionJson).getJSONObject("user") }.getOrNull()
    val login = user?.optString("login", "用户") ?: "用户"
    val avatarUrl = user?.optString("avatar_url")?.takeIf { it.isNotBlank() }

    // 解析 token 与 host，用于拉取数据
    val token = runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    val host = runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("branchbase", Context.MODE_PRIVATE) }

    var repos by remember { mutableStateOf<List<StarredRepo>>(emptyList()) }
    var events by remember { mutableStateOf<List<Activity>>(emptyList()) }

    // 加载星标（先读缓存，再网络刷新）
    suspend fun loadStarred(refresh: Boolean) {
        if (!refresh) {
            prefs.getString("starred_repos", null)?.let { repos = parseStarredRepos(it) }
        }
        RustBridge.getStarredRepos(host, token)?.let { json ->
            if (!json.startsWith("ERROR:")) {
                repos = parseStarredRepos(json)
                prefs.edit().putString("starred_repos", json).apply()
            }
        }
    }

    // 加载最近活动（先读缓存，再网络刷新）
    suspend fun loadEvents(refresh: Boolean) {
        if (!refresh) {
            prefs.getString("received_events", null)?.let { events = parseActivities(it) }
        }
        RustBridge.getReceivedEvents(host, token, login)?.let { json ->
            if (!json.startsWith("ERROR:")) {
                events = parseActivities(json)
                prefs.edit().putString("received_events", json).apply()
            }
        }
    }

    LaunchedEffect(Unit) {
        loadStarred(false)
        loadEvents(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary),
    ) {
        // 顶部：搜索栏 + 头像
        SearchBarRow(login = login, avatarUrl = avatarUrl, onProfileClick = onProfileClick, onSearchClick = onSearchClick)

        // 内容：分区块列表
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { ShortcutRow() }
            item {
                SectionHeader("我的星标", Icons.Filled.Star) {
                    scope.launch { loadStarred(true) }
                }
            }
            if (repos.isEmpty()) {
                item { EmptyState("暂无星标仓库") }
            } else {
                items(repos) { repo -> RepoCard(repo, onClick = { onRepoClick(repo.fullName) }) }
            }
            item {
                SectionHeader("最近活动", Icons.Filled.History) {
                    scope.launch { loadEvents(true) }
                }
            }
            if (events.isEmpty()) {
                item { EmptyState("暂无最近活动") }
            } else {
                items(events) { act -> ActivityItem(act) }
            }
        }
    }
}

/** 解析 /user/starred 返回的 JSON 数组 */
private fun parseStarredRepos(json: String): List<StarredRepo> {
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            StarredRepo(
                fullName = obj.optString("full_name"),
                desc = obj.optString("description").orEmpty(),
                language = obj.optString("language").takeIf { it.isNotBlank() },
                stars = obj.optLong("stargazers_count").let { formatCount(it) },
                forks = obj.optLong("forks_count").let { formatCount(it) },
            )
        }
    }.getOrDefault(emptyList())
}

private fun formatCount(n: Long): String = when {
    n >= 1000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}

/** 解析 received_events 返回的 JSON 数组 */
private fun parseActivities(json: String): List<Activity> {
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val type = obj.optString("type")
            val actor = obj.optJSONObject("actor")?.optString("login") ?: return@mapNotNull null
            val repoName = obj.optJSONObject("repo")?.optString("name") ?: ""
            val createdAt = obj.optString("created_at")
            val (icon, verb) = when (type) {
                "WatchEvent" -> Icons.Filled.Star to "star 了"
                "ForkEvent" -> Icons.Filled.CallSplit to "fork 了"
                "IssuesEvent" -> Icons.Filled.ErrorOutline to "在 issue 上操作"
                "PullRequestEvent" -> Icons.Filled.CallSplit to "提交了 PR 到"
                "PushEvent" -> Icons.Filled.Code to "推送代码到"
                "CreateEvent" -> Icons.Filled.Add to "创建了"
                else -> return@mapNotNull null
            }
            Activity(
                icon = icon,
                text = "$actor $verb $repoName",
                time = relativeTime(createdAt),
            )
        }
    }.getOrDefault(emptyList())
}

/** ISO 时间转相对时间 */
private fun relativeTime(iso: String): String {
    return runCatching {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val epoch = fmt.parse(iso)?.time ?: return@runCatching iso
        val minutes = (System.currentTimeMillis() - epoch) / 60000
        when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 1440 -> "${minutes / 60} 小时前"
            else -> "${minutes / 1440} 天前"
        }
    }.getOrDefault(iso)
}

/** 搜索栏 + 头像（头像点击进个人页） */
@Composable
private fun SearchBarRow(login: String, avatarUrl: String?, onProfileClick: () -> Unit, onSearchClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 搜索框（点击进搜索页）
        Row(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Primer.BackgroundSecondary)
                .clickable { onSearchClick() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = "搜索", tint = Primer.IconSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("搜索 GitHub", fontSize = 14.sp, color = Primer.TextTertiary)
        }
        Spacer(Modifier.width(10.dp))
        // 头像（40dp 圆，与搜索框同高）
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { onProfileClick() },
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = login,
                    modifier = Modifier.size(40.dp).clip(CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(Primer.Blue500),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(login.take(1).uppercase(), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** 快捷方式（横向滚动） */
@Composable
private fun ShortcutRow() {
    val shortcuts = listOf(
        Icons.Filled.Add to "新建",
        Icons.Filled.CallSplit to "我的PR",
        Icons.Filled.ErrorOutline to "我的Issue",
        Icons.Filled.Star to "星标",
        Icons.Filled.Code to "搜索代码",
    )
    LazyRow(
        modifier = Modifier.padding(bottom = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(shortcuts) { (icon, label) ->
            Column(
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Primer.BackgroundSecondary)
                    .clickable { },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = label, tint = Primer.IconPrimary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(5.dp))
                Text(label, fontSize = 11.sp, color = Primer.TextSecondary)
            }
        }
    }
}

/** 区块标题（图标 + 文字 + 可选刷新按钮） */
@Composable
private fun SectionHeader(title: String, icon: ImageVector, onRefresh: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Primer.IconPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        Spacer(Modifier.weight(1f))
        if (onRefresh != null) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "刷新",
                tint = Primer.IconSecondary,
                modifier = Modifier.size(18.dp).clickable { onRefresh() },
            )
        }
    }
}

/** 仓库卡片 */
@Composable
private fun RepoCard(repo: StarredRepo, onClick: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Primer.BackgroundSecondary)
            .clickable { onClick() }
            .padding(14.dp),
    ) {
        Text(repo.fullName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primer.Blue500)
        if (repo.desc.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(repo.desc, fontSize = 13.sp, color = Primer.TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (repo.language != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(langColor(repo.language)))
                    Spacer(Modifier.width(4.dp))
                    Text(repo.language, fontSize = 12.sp, color = Primer.TextSecondary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, contentDescription = "star", tint = Primer.IconSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(repo.stars, fontSize = 12.sp, color = Primer.TextSecondary)
            }
            if (repo.forks != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CallSplit, contentDescription = "fork", tint = Primer.IconSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(repo.forks, fontSize = 12.sp, color = Primer.TextSecondary)
                }
            }
        }
    }
}

/** 活动项 */
@Composable
private fun ActivityItem(act: Activity) {
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Primer.BackgroundSecondary)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Primer.BackgroundPrimary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(act.icon, contentDescription = null, tint = Primer.IconPrimary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(act.text, fontSize = 13.sp, color = Primer.TextSecondary)
            Spacer(Modifier.height(3.dp))
            Text(act.time, fontSize = 11.sp, color = Primer.TextTertiary)
        }
    }
}

/** 空态 */
@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Primer.TextTertiary, fontSize = 13.sp)
    }
}

/** 星标仓库（来自 /user/starred） */
private data class StarredRepo(
    val fullName: String,
    val desc: String,
    val language: String?,
    val stars: String,
    val forks: String? = null,
)

/** 活动（来自 received_events） */
private data class Activity(
    val icon: ImageVector,
    val text: String,
    val time: String,
)

/** GitHub 语言色映射 */
private fun langColor(lang: String?): Color = when (lang) {
    "Kotlin" -> Color(0xFFA97BFF)
    "Rust" -> Color(0xFFDEA584)
    "Java" -> Color(0xFFB07219)
    "Python" -> Color(0xFF3572A5)
    "JavaScript" -> Color(0xFFF1E05A)
    "TypeScript" -> Color(0xFF3178C6)
    "Go" -> Color(0xFF00ADD8)
    "Swift" -> Color(0xFFF05138)
    "C++" -> Color(0xFFF34B7D)
    "C" -> Color(0xFF555555)
    "Dart" -> Color(0xFF00B4AB)
    else -> Color(0xFF8B949E)
}