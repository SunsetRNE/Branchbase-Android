package com.branchbase.ui.repository

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.core.engineErrorStatus
import com.branchbase.ui.LocalizedText
import com.branchbase.ui.cardSurface
import com.branchbase.ui.navigation.BranchbaseLazyColumn
import com.branchbase.ui.resolve
import com.branchbase.ui.theme.Avatar
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap

/**
 * 贡献者完整列表（全屏）。
 *
 * ## 为什么单独一页
 *
 * 仓库概览的贡献者卡只列前 8 位（[RepositoryOverviewScreen] 里的 `CONTRIBUTORS_PREVIEW`），
 * 因为卡是一个 LazyColumn item，100 位贡献者会在同一帧里排完 —— 「概览卡」不该为了
 * 一份完整名单牺牲掉懒加载。所以要「看全部」就走这一页：每行一张浮层卡，
 * 与 Releases / 星标列表同一套行卡密度。
 *
 * ## 数据
 *
 * `GET /repos/{o}/{r}/contributors?per_page=100`（[RustBridge.getRepoContributors]，
 * 与概览卡同一条路径、同一份解析）—— 不另开接口，也不在这里重算提交数。
 *
 * 这个端点对公开仓库**不需要协作者权限**（与 `/stargazers`、`/subscribers` 不同），
 * 所以失败文案只分「仓库没了 / 限流 / 其它」，不套「限制为协作者可见」那句。
 */
@Composable
internal fun ContributorsScreen(
    sessionJson: String,
    owner: String,
    repo: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val (host, token, _) = sessionInfo(sessionJson)
    var contributors by remember { mutableStateOf<List<Contributor>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(owner, repo, tick) {
        loading = true
        error = null
        val raw = RustBridge.getRepoContributors(host, token, owner, repo)
        when {
            raw == null -> error = context.getString(R.string.state_no_data_retry)
            raw.startsWith("ERROR:") -> error = contributorsErrorText(raw).resolve(context)
            else -> contributors = parseContributors(raw)
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().background(Primer.BackgroundPrimary).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.action_back),
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.label_contributors),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Primer.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            // 总数放在标题行右侧：这一页没有分页，用户需要知道「一共多少人」才判断名单完不完整
            if (!loading && error == null && contributors.isNotEmpty()) {
                Text(
                    pluralStringResource(
                        R.plurals.stat_contributors,
                        contributors.size,
                        contributors.size.toString(),
                    ),
                    fontSize = 11.5.sp,
                    color = Primer.TextTertiary,
                )
            }
        }
        when {
            loading -> ListLoading()
            error != null -> ListError(error!!, onRetry = { tick++ })
            contributors.isEmpty() -> ListEmpty(stringResource(R.string.state_no_contributors))
            else -> BranchbaseLazyColumn(Modifier.fillMaxSize()) {
                items(contributors, key = { it.login }) { ContributorCardRow(it) }
            }
        }
    }
}

/**
 * 一位贡献者：**浮层行卡 + 头像 + 登录名 + 提交数**。
 *
 * 行不可点：本仓库没有「用户主页」的仓库内路由，做成可点就是一个点了没反应的死落点
 * （Releases / 星标列表的失败卡里有同一条教训）。
 */
@Composable
private fun ContributorCardRow(c: Contributor) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .cardSurface()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(url = c.avatarUrl, login = c.login, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            c.login,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Primer.Blue500,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            pluralStringResource(R.plurals.label_commit_count, c.commits.toInt(), c.commits),
            fontSize = 11.5.sp,
            color = Primer.TextTertiary,
        )
    }
}

/**
 * 贡献者列表的失败文案（纯函数，便于单测）。
 *
 * 与 [peopleListErrorText] 分开：那个的 403 分支写的是「GitHub 已把列表限制为仓库协作者可见」，
 * 而 `/contributors` 是公开端点 —— 真被拒只可能是令牌或限流，套那句会把用户引到权限上去排查。
 */
internal fun contributorsErrorText(rawError: String): LocalizedText = when {
    engineErrorStatus(rawError) == 404 -> LocalizedText(R.string.error_repo_not_found_or_forbidden)
    engineErrorStatus(rawError) == 429 || rawError.contains("rate limit", ignoreCase = true) ->
        LocalizedText(R.string.error_rate_limited)
    else -> LocalizedText(
        R.string.error_people_list_load_failed,
        listOf(LocalizedText(R.string.label_contributors), rawError.removePrefix("ERROR:").take(120)),
    )
}
