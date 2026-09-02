package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.LanguageColors
import com.branchbase.ui.theme.Primer
import org.json.JSONObject

/**
 * 项目页（仓库首页）。
 *
 * 对齐 `docs/repository-overview-wireframe.md`：
 * 仓库头（owner/名/描述）→ 星标/复刻/关注 → README → 许可证 → 贡献者 → 语言比例条。
 * 数据源：getRepoInfo / readmeHtml+parseHtml / getRepoLanguages / getRepoContributors。
 */
@Composable
fun RepositoryOverviewContent(
    sessionJson: String,
    owner: String,
    repo: String,
    branch: String? = null,
    refreshTick: Int = 0,
    onLinkClick: (Destination) -> Unit,
    onActionClick: (String) -> Unit,
) {
    val session = remember(sessionJson) { runCatching { JSONObject(sessionJson) }.getOrNull() }
    val host = session?.optString("host", "github.com") ?: "github.com"
    val token = session?.optJSONObject("token")?.optString("access_token").orEmpty()
    val login = session?.optJSONObject("user")?.optString("login").orEmpty()
    val context = LocalContext.current

    var repoInfo by remember { mutableStateOf<RepoInfo?>(null) }
    var readmeBlocks by remember { mutableStateOf<List<ReadmeBlock>>(emptyList()) }
    var languages by remember { mutableStateOf<List<LanguageStat>>(emptyList()) }
    var contributors by remember { mutableStateOf<List<Contributor>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(owner, repo, branch, refreshTick) {
        loading = true
        error = null

        // 1. 仓库信息
        val info = RustBridge.getRepoInfo(host, token, owner, repo)
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { parseRepoInfo(it) }
        if (info != null) repoInfo = info
        // 实际分支：用户选择 ?: 仓库默认分支 ?: main
        val effectiveBranch = branch ?: info?.defaultBranch ?: "main"

        // 2. README（html → parseHtml → blocks）；branch 透传给 readme 接口；HTML 走 Room 缓存（type=README，key=owner/repo@branch）
        val cacheManager = SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
        val readmeKey = "$owner/$repo@${branch ?: ""}"
        var html = cacheManager.get(readmeKey, "README")
        if (html == null) {
            html = RustBridge.readmeHtml(host, token, owner, repo, branch ?: "")
            if (html != null && !html.startsWith("ERROR:")) {
                cacheManager.put(readmeKey, "README", html)
            }
        }
        if (html != null && !html.startsWith("ERROR:")) {
            val parsed = RustBridge.parseHtml(html, host, owner, repo, effectiveBranch, "", login)
            if (parsed != null && !parsed.startsWith("ERROR:")) {
                readmeBlocks = parseReadmeBlocks(parsed)
            }
        }

        // 3. 语言
        RustBridge.getRepoLanguages(host, token, owner, repo)
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { languages = parseLanguages(it) }

        // 4. 贡献者
        RustBridge.getRepoContributors(host, token, owner, repo)
            ?.takeIf { !it.startsWith("ERROR:") }
            ?.let { contributors = parseContributors(it) }

        if (info == null) error = "仓库不存在或无权访问"
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary),
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primer.Blue500)
            }
            error != null -> ErrorState(error!!)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item { RepoHeader(repoInfo) }
                item { ActionRow(repoInfo, onActionClick) }

                item { SectionTitle("自述文件 README") }
                if (readmeBlocks.isEmpty()) {
                    item { EmptyHint("暂无自述文件") }
                } else {
                    item { ReadmeContent(readmeBlocks, onLinkClick = onLinkClick) }
                }

                item { SectionTitle("许可证 License") }
                item { LicenseRow(repoInfo?.license) }

                item { SectionTitle("贡献者 Contributors") }
                if (contributors.isEmpty()) {
                    item { EmptyHint("暂无贡献者") }
                } else {
                    items(contributors) { ContributorRow(it) }
                }

                item { SectionTitle("项目语言 Languages") }
                item { LanguageSection(languages) }
            }
        }
    }
}

// ── 组件 ──

@Composable
private fun RepoHeader(info: RepoInfo?) {
    Column(Modifier.fillMaxWidth().padding(14.dp, 16.dp)) {
        info?.ownerLogin?.let {
            Text(it, fontSize = 12.sp, color = Primer.TextTertiary)
        }
        Text(
            text = info?.name ?: "",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Primer.Blue500,
        )
        if (!info?.description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(info.description, fontSize = 13.sp, color = Primer.TextSecondary, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun ActionRow(info: RepoInfo?, onActionClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionButton(Icons.Filled.Star, "Star", info?.stars) { onActionClick("star") }
        ActionButton(Icons.AutoMirrored.Filled.CallSplit, "Fork", info?.forks) { onActionClick("fork") }
        ActionButton(Icons.Filled.Visibility, "Watch", info?.watchers) { onActionClick("watch") }
    }
}

@Composable
private fun RowScope.ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, count: Long?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(Primer.Gray150)
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = label, tint = Primer.IconPrimary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, color = Primer.TextSecondary)
        count?.let {
            Spacer(Modifier.width(4.dp))
            Text(formatCount(it), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = Primer.TextPrimary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun LicenseRow(license: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Primer.Gray150)
            .padding(10.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = license ?: "无",
            fontSize = 13.sp,
            color = if (license != null) Primer.Blue500 else Primer.TextTertiary,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ContributorRow(c: Contributor) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Primer.Blue500), contentAlignment = Alignment.Center) {
            if (c.avatarUrl != null) {
                AsyncImage(model = c.avatarUrl, contentDescription = c.login, modifier = Modifier.size(26.dp).clip(CircleShape))
            } else {
                Text(c.login.take(1).uppercase(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(c.login, fontSize = 13.sp, color = Primer.Blue500, modifier = Modifier.weight(1f))
        Text("${c.commits} commits", fontSize = 12.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun LanguageSection(langs: List<LanguageStat>) {
    if (langs.isEmpty()) {
        EmptyHint("暂无语言数据")
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        // 比例条（占比 >= 0.5% 的语言才显示色块）
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            langs.filter { it.percent >= 0.5 }.forEach { lang ->
                Box(
                    Modifier
                        .weight((lang.percent * 1000).toInt().coerceAtLeast(1).toFloat())
                        .fillMaxSize()
                        .background(LanguageColors.of(lang.name)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 图例
        langs.forEach { lang ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(LanguageColors.of(lang.name)))
                Spacer(Modifier.width(8.dp))
                Text(lang.name, fontSize = 12.sp, color = Primer.TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    text = String.format("%.2f%%", lang.percent),
                    fontSize = 12.sp,
                    color = Primer.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = Primer.TextTertiary)
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("加载失败", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        Spacer(Modifier.height(4.dp))
        Text(message, fontSize = 12.sp, color = Primer.TextTertiary)
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1000 -> "%.1fk".format(n / 1000.0)
    else -> n.toString()
}