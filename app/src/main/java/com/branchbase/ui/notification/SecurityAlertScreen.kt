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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.core.RustBridge
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 安全警报落地页（对齐 `docs/notification-wireframe.md` §4）。
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

    LaunchedEffect(owner, repo, subjectUrl) {
        loading = true
        detail = null
        val path = extractPathFromUrl(subjectUrl)
        if (path != null) {
            val json = withContext(Dispatchers.IO) { RustBridge.getJson(host, token, path) }
            if (json != null && !json.startsWith("ERROR:")) {
                detail = parseSecurityDetail(json)
            }
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Primer.IconPrimary, modifier = Modifier.size(24.dp).clickable { onBack() })
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