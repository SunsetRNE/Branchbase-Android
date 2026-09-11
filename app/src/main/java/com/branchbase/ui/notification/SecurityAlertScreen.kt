package com.branchbase.ui.notification

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
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
import com.branchbase.cache.PageCache
import com.branchbase.cache.SearchCacheDatabase
import com.branchbase.cache.SearchCacheManager
import com.branchbase.core.AccountStore
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 安全警报落地页。
 *
 * 通知 `RepositoryVulnerabilityAlert` / `RepositoryAdvisory` 类型点击后直达本页；
 * 通过 subject.url 提取 path，复用 `RustBridge.getJson` 拉取 Dependabot alerts /
 * security-advisories 详情，展示严重级别 / 受影响范围 / 发现时间 / CVE / 描述。
 * 拉取失败或无权限时优雅回退到「横幅 + 仓库归属」骨架。
 */
@Composable
fun SecurityAlertScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    title: String,
    subjectUrl: String,
    onBack: () -> Unit,
    onOpenRepo: () -> Unit,
) {
    val host = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    }
    val token = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).getJSONObject("token").optString("access_token") }.getOrNull() ?: ""
    }

    var detail by remember { mutableStateOf<SecurityDetail?>(null) }
    var loading by remember { mutableStateOf(true) }

    val context = LocalContext.current
    val cacheManager = remember(context) {
        SearchCacheManager(SearchCacheDatabase.getInstance(context).searchCacheDao())
    }
    // subject.url → 告警接口路径（如 `/repos/o/r/dependabot/alerts/1`）；null = 无法从 URL 提取
    val path = remember(subjectUrl) { extractPathFromUrl(subjectUrl) }
    // 键选 profileKey 而非 notificationKey：同一告警路径的结果受**令牌权限**影响
    // （私有仓库的 Dependabot / code scanning 告警对无权限账号会返回骨架或 404），
    //   而 notificationKey 只按 path 建键，会让两个账号在同设备上互相污染缓存。
    //   profileKey 带 login，把结果绑定到当前账号；TTL 仍用 TYPE_NOTIFICATION（2 分钟）。
    val login = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).getJSONObject("user").optString("login") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: AccountStore.currentLogin(context)
    }
    val cacheKey = remember(login, path) {
        path?.let { PageCache.profileKey(login, "security:$it") }
    }

    LaunchedEffect(owner, repo, subjectUrl, cacheKey) {
        loading = true
        detail = null
        if (path == null || cacheKey == null) {
            loading = false
            return@LaunchedEffect
        }
        // force 恒为 false：本页没有下拉刷新/重试入口（未新增 UI），直出 + 回源即可
        val force = false

        // ① 先直出缓存（含过期）：从通知列表点同一条告警不再空转
        // 注意 parseSecurityDetail 恒非空（失败时返回空 SecurityDetail），所以用「本次是否直出成功」的标志位判定
        val cached = PageCache.cachedFirst(cacheManager, cacheKey, PageCache.TYPE_NOTIFICATION, force)
        val shown = cached != null
        cached?.let {
            detail = parseSecurityDetail(it)
            loading = false
        }

        // ② 回源并写回（未过期时 refresh 直接返回缓存，不发请求；失败返回 null 且不覆盖旧缓存）
        val json = PageCache.refresh(cacheManager, cacheKey, PageCache.TYPE_NOTIFICATION, force) {
            withContext(Dispatchers.IO) { RustBridge.getJson(host, token, path) }
        }
        if (json != null && !json.startsWith("ERROR:")) {
            detail = parseSecurityDetail(json)
        } else if (!shown) {
            // 无缓存可直出时才保留原有的「回退骨架」语义（detail 保持 null）
            detail = null
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        // 顶部返回栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).iconTap { onBack() })
            Spacer(Modifier.width(8.dp))
            Text("安全警报", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
        }

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            // 红色横幅（对齐线框 #FFDCE0）
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFDCE0)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, tint = Primer.Red500, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("检测到安全风险", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFB91C1C))
                }
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                detail?.summary?.let {
                    Text(it, fontSize = 12.5.sp, color = Primer.TextSecondary, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.height(12.dp))

            when {
                loading -> {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Primer.Blue500, strokeWidth = 2.dp)
                    }
                }
                detail != null -> {
                    val d = detail!!
                    InfoRow("仓库", "$owner/$repo")
                    InfoRow("类型", "安全警报")
                    d.severity?.let { InfoRow("严重级别", it, valueColor = severityColor(it)) }
                    d.dependency?.let { InfoRow("受影响范围", it) }
                    d.publishedAt?.let { InfoRow("发现时间", it.take(10)) }
                    d.cveId?.let { InfoRow("CVE", it) }
                    d.description?.let { desc ->
                        Spacer(Modifier.height(12.dp))
                        Text("描述", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                        Spacer(Modifier.height(4.dp))
                        Text(desc, fontSize = 13.sp, color = Primer.TextSecondary, lineHeight = 20.sp)
                    }
                }
                else -> {
                    // 拉取失败 / 无权限：回退骨架
                    InfoRow("仓库", "$owner/$repo")
                    InfoRow("类型", "安全警报")
                }
            }

            Spacer(Modifier.height(24.dp))
            // 查看仓库按钮
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Primer.Blue500).clickable { onOpenRepo() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("查看仓库", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Primer.TextPrimary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, fontSize = 13.sp, color = Primer.TextTertiary, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 13.sp, color = valueColor, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

/** 严重级别 → 颜色（critical/high 红 · medium 橙 · 其余灰） */
private fun severityColor(s: String): Color = when (s.lowercase()) {
    "critical", "high" -> Primer.Red500
    "medium", "moderate" -> Primer.Orange500
    else -> Primer.Gray600
}