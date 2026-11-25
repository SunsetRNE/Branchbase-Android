package com.branchbase.ui.home

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.branchbase.ui.navigation.BranchbaseLazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.profile.SubPageHeader
import com.branchbase.ui.settings.SettingsKeys
import com.branchbase.ui.theme.ElementMotion
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
 * ## 选中态 = 卡片底色（不是一圈静止的描边）
 * 未选中：[Primer.Gray100] 底、**无描边**；选中：[Primer.InfoSurfaceStrong] 浅蓝实底 +
 * 1.5dp [Primer.Blue500] 蓝环 + 右侧对勾。三者同时变：底色负责「一眼看出哪些已置顶」，
 * 蓝环负责在这一屏 20 张卡里把边界说清楚，对勾负责色弱与强光下的可读性
 * （`NotificationScreen` 的多选行也是这么叠的）。
 *
 * 上一版让**每一张**未选中的卡都带 1dp [Primer.Border] 黑框，等于用「框」表达「没选中」——
 * 满屏都是框，选中只是换了个框色 + 6% 蓝底，扫视时读不出来。现在框只在选中时出现。
 *
 * ## 计数徽标 = 抽屉入口
 * 右上角那粒蓝底徽标（`已选 N / 共 M`）点开是底部抽屉：解释「最多 5 个 + 点一下即置顶」、
 * 集中列出已选的仓库（可单个移除 / 一次清空）。原先这两件事占着列表上方一整行，
 * 说明文字又只在第一次有用 —— 现在它们按需出现，列表直接顶到标题下面。
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
    // 计数徽标点开的抽屉（详见文件头「计数徽标 = 抽屉入口」）
    var drawerOpen by remember { mutableStateOf(false) }
    // 滚动反馈要用：顶部是否已离开首项、底部还有没有内容可滚
    val listState = rememberLazyListState()
    val scrolledFromTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val canScrollMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last != null && (last.index < info.totalItemsCount - 1 || last.offset + last.size > info.viewportEndOffset)
        }
    }
    // 两条边缘都只在`绘制期`读值（graphicsLayer / 颜色 alpha），不触发重组 —— 与 Motion.kt 的约定一致
    val topEdgeAlpha by animateFloatAsState(
        targetValue = if (scrolledFromTop) 1f else 0f,
        animationSpec = tween(140),
        label = "topEdge",
    )
    val bottomEdgeAlpha by animateFloatAsState(
        targetValue = if (canScrollMore) 1f else 0f,
        animationSpec = tween(140),
        label = "bottomEdge",
    )

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
                SelectionBadge(pinned = selected.size, limit = FrequentRepoRules.HOME_LIMIT) { drawerOpen = true }
                Spacer(Modifier.width(10.dp))
            }
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.action_refresh),
                tint = Primer.Blue500,
                modifier = Modifier.size(20.dp).iconTap { refreshKey++ },
            )
        }

        // ① 顶部边缘：离开首项才出现的那条 1dp 分隔线。
        // 用「淡入一条线」而不是「标题栏跟着压缩」：后者要把标题栏挪进滚动容器，
        // 整页结构跟着变，而这一页的信息密度不值得为一次滚动改结构。
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .graphicsLayer { alpha = topEdgeAlpha }
                .background(Primer.Border),
        )

        when {
            loading && repos.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.state_loading), color = Primer.TextTertiary)
            }

            repos.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.state_no_my_repos), fontSize = 13.sp, color = Primer.TextTertiary)
            }

            else -> Box(Modifier.weight(1f).fillMaxWidth()) {
                BranchbaseLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
                // ① 底部边缘：还有内容可滚时，最后一行淡到页面底色（不是硬切一条线）
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(28.dp)
                        .graphicsLayer { alpha = bottomEdgeAlpha }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Primer.BackgroundPrimary))),
                )
            }
        }
    }

    if (drawerOpen) {
        SelectedReposSheet(
            selected = selected,
            total = repos.size,
            limit = FrequentRepoRules.HOME_LIMIT,
            onRemove = { name ->
                selected = selected - name
                FrequentRepoStore.save(context, bucket, selected)
            },
            onClear = {
                selected = emptyList()
                FrequentRepoStore.save(context, bucket, emptyList())
            },
            onDismiss = { drawerOpen = false },
        )
    }
}

/**
 * 右上角的计数徽标：`已置顶 N / 5`，点开抽屉。
 *
 * 做成**蓝底胶囊**而不是原先那行灰字：它现在是一个入口（可点），而可点的东西必须先长得像可点的
 * 东西 —— 灰字放在标题栏右侧，和旁边的刷新图标不是一类，用户不会去点它。
 * 用 [Primer.InfoSurface] 实底而不是 `Blue500.copy(alpha = …)`：深色板上 alpha 叠加会随
 * 卡片底色漂移，实底色板两边各自定义过一次，深浅两套都是可读的。
 */
@Composable
private fun SelectionBadge(pinned: Int, limit: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    // ② 数字变化时轻轻弹一下：0.9 → 1（220ms）。这一粒是整页唯一的「计数器」，
    // 不弹的话，点卡片时用户的注意力在卡片上，右上角这个数变了也没人看见。
    // 首次组合不弹（否则每次进页都先缩一下，读成「闪了一下」）。
    val pulse = remember { Animatable(1f) }
    var pulseArmed by remember { mutableStateOf(false) }
    LaunchedEffect(pinned) {
        if (!pulseArmed) {
            pulseArmed = true
            return@LaunchedEffect
        }
        pulse.snapTo(0.9f)
        pulse.animateTo(1f, tween(ElementMotion.BADGE_MS))
    }
    Text(
        // 原来是「已选 N / 共 M（候选数）」：两个数字不是一回事，用户得先知道 M 是列表条数。
        // 改成「已置顶 N / 5」—— 分母就是上限，和抽屉里那行一致，也直接回答「还能再放几个」。
        text = stringResource(R.string.label_frequent_repos_pinned_count, pinned, limit),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Primer.Blue600,
        maxLines = 1,
        modifier = Modifier
            // 缩放只走绘制层：动画期间不重组、不重排
            .graphicsLayer {
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .clip(shape)
            .background(Primer.InfoSurface, shape)
            .border(1.dp, Primer.Blue500.copy(alpha = 0.35f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 行尾的对勾：槽位由父级固定，这里只负责「有 / 没有」的缩放淡入。 */
@Composable
private fun PinCheckIcon(selected: Boolean) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(ElementMotion.BADGE_MS)) +
                scaleIn(tween(ElementMotion.BADGE_MS), initialScale = 0.6f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f),
        ) {
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

/** 抽屉里的信息徽标：`accent` 表示状态色（已置顶），否则是中性色（规模 / 数量）。 */
@Composable
private fun DrawerChip(text: String, accent: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.Medium,
        color = if (accent) Primer.Blue600 else Primer.TextSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (accent) Primer.InfoSurface else Primer.Gray150, shape)
            .border(1.dp, if (accent) Primer.Blue500.copy(alpha = 0.35f) else Primer.Gray200, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * 「首页置顶仓库」抽屉：说明顺序规则 + 集中展示已置顶 + 单个移除 / 一次全部取消。
 *
 * 原先这三件事拆在两处：说明文字与「清除」占着列表上方一整行（每进一次页都要看一遍，而它只在
 * 第一次有用），已置顶内容则散落在 20 张卡里靠蓝底一个个找。现在合并到「点徽标才出现」的一层里。
 *
 * 顺序就是首页的显示顺序（`selected` 本就是点选先后），所以这里不做二次排序 ——
 * 抽屉里的第 1 行就是首页左上角那一格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectedReposSheet(
    selected: List<String>,
    /** 候选全集条数 —— 第二个徽标要显示「共 N 个仓库」。 */
    total: Int,
    limit: Int,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.label_frequent_repos_pinned),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Primer.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                if (selected.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        // 「清除」太泛（清的是什么？），这颗按钮只做一件事：把首页那几个全撤了
                        Text(stringResource(R.string.action_unpin_all), fontSize = 13.sp, color = Primer.Blue500)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 两个徽标同一排：蓝的答「还能放几个」，灰的答「候选一共多少」——
            // 它们是两类信息（状态 vs 规模），用同一种底色会被读成一件事，所以分色。
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                DrawerChip(stringResource(R.string.label_frequent_repos_pinned_count, selected.size, limit), accent = true)
                DrawerChip(stringResource(R.string.label_frequent_repos_total_repos, total))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                // 说明的落点换了：抽屉里列的是「已经置顶的」，所以要说的是「顺序」，
                // 而不是原来那句「点一下选中」（那是列表上的动作，写在抽屉里指代不明）。
                stringResource(R.string.note_frequent_repos_pin_order, limit),
                fontSize = 11.5.sp,
                color = Primer.TextSecondary,
                lineHeight = 17.sp,
                // 提示也做成一条底色块：它和上面两枚徽标是一组「规则说明」，
                // 单独一行灰字夹在两枚徽标与列表之间会显得像漏排的空行。
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(Primer.Gray150).padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            if (selected.isEmpty()) {
                Text(
                    stringResource(R.string.note_frequent_repos_selected_empty),
                    fontSize = 13.sp,
                    color = Primer.TextTertiary,
                    lineHeight = 18.sp,
                )
            } else {
                selected.forEachIndexed { index, fullName ->
                    Row(
                        // 每行一条浅灰底：抽屉里的行是「条目」，不是列表页的卡片 ——
                        // 底色只用来把连续几行切成「一条一条」，圆角比卡片小一档（8dp）
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Primer.Gray100)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 序号 = 首页的格子顺序，省得用户回首页数
                        Text(
                            "${index + 1}",
                            fontSize = 12.sp,
                            color = Primer.TextTertiary,
                            modifier = Modifier.width(18.dp),
                        )
                        Text(
                            fullName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Primer.Blue500,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_remove),
                            tint = Primer.IconSecondary,
                            modifier = Modifier.size(18.dp).iconTap { onRemove(fullName) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 可置顶的仓库行：**底色 = 选中态**（未选中 Gray100 无框 / 选中 InfoSurfaceStrong + 1.5dp Blue500 环）。
 *
 * 尺寸与字号跟另外两处仓库卡（首页「常用仓库」、个人页「热门仓库」）同一套参数：
 * 圆角 10dp / 卡距 6dp / 内边距 h14·v8 / 名字 14sp / 描述 12sp·行高 15sp·限 2 行 /
 * 名字↔描述 2dp / 描述↔元信息 6dp。这一页原先还是上一代规格（12dp / 10dp / 14dp / 15sp / 13sp）——
 * 三处卡片各自演化过一次之后，这里成了唯一还胖的那个 —— 一行约多 22dp，20 行滚下来约多半屏。
 */
@Composable
private fun PinnableRepoRow(repo: RepoSummary, selected: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val press = remember { MutableInteractionSource() }
    // ② 选中底色 180ms 渐变（ElementMotion.COLOR_MS）：原来是硬切 ——
    // 一次点按在列表里读成「闪了一下」，渐变才读得出「这一张被我钉到首页了」。
    val rowBg by animateColorAsState(
        targetValue = if (selected) Primer.InfoSurfaceStrong else Primer.Gray100,
        animationSpec = tween(ElementMotion.COLOR_MS),
        label = "pinRowBg",
    )
    Row(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            .fillMaxWidth()
            .clip(shape)
            // 选中 = 底色（浅蓝实底）；未选中 = Gray100 的「有底、无框」——
            // 旧版让每张卡都带 1dp 黑框，满屏都是框，选中的那张只是框变蓝，扫视读不出来。
            .background(rowBg)
            // 蓝环只在选中时出现：它是「选中」的一部分，不是常驻装饰
            .then(if (selected) Modifier.border(1.5.dp, Primer.Blue500, shape) else Modifier)
            // indication = null + selectable(role = Checkbox)：整行是一个复选框，
            // 读屏会念「已选中 / 未选中」，比「一个带边框的按钮」准确
            .selectable(
                selected = selected,
                role = Role.Checkbox,
                interactionSource = press,
                indication = null,
                onClick = onToggle,
            )
            // 竖向比横向收得更狠：留白是这一行最高的成本项，横向再收会让文字贴边
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    repo.fullName,
                    fontSize = 14.sp,
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
                Spacer(Modifier.height(2.dp))
                Text(
                    repo.desc,
                    fontSize = 12.sp,
                    // 显式写行高：12sp 走默认（≈16.2sp）会让两行描述多出 2dp
                    lineHeight = 15.sp,
                    color = Primer.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                // 没有描述也是信息：与另外两处仓库卡同一句占位，节奏不断档，
                // 也不至于在这一页里出现「一行比邻居矮 15dp」的参差
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.state_no_repo_description),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = Primer.TextTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(6.dp))
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
        // 对勾的槽位**常驻**：原来用 `if (selected)` 时才加 10dp + 20dp，
        // 于是每次勾选/取消，这一行的文字列宽都会变一次、整段文字重排 —— 渐变底色再顺，
        // 文字还是会跳一下。现在槽位固定，只让图标自己缩放淡入。
        Spacer(Modifier.width(10.dp))
        PinCheckIcon(selected = selected)
    }
}
