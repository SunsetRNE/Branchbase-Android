package com.branchbase.ui.profile

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.core.engineErrorOrRaw
import com.branchbase.ui.CardElevation
import com.branchbase.ui.CardShape
import com.branchbase.ui.cardSurface
import com.branchbase.ui.log.Logger
import com.branchbase.ui.navigation.PageBackHandler
import com.branchbase.ui.repository.Feedback
import com.branchbase.ui.task.TaskKind
import com.branchbase.ui.task.TaskStore
import com.branchbase.ui.theme.Primer
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.rememberPressFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 编辑资料页：修改当前登录用户的 GitHub 公开资料（PATCH /user）。
 *
 * 对齐 GitHub Profile 编辑项：名称 / 简介（bio）/ 公司 / 位置 / 网站 / 社交链接；
 * 只提交有变化的字段（避免无意义 PATCH）；保存前校验网站 URL。
 *
 * ## 2026-11-27 改版：卡片分组 + 常驻头顶保存（决策一~四）
 *
 * ① **整页拆成浮层卡**：头像块、三张字段卡（基本资料 / 工作与位置 / 链接）与底部说明卡，
 * 一共五张卡 —— 原来裸在页面上的头像行和说明文字各占一块白，夹在卡中间像漏做的一块，
 * 收进卡面之后「哪块是什么」由卡边界自己说。字段行从 `OutlinedTextField`
 * （Material 默认 56dp 高）换成 34dp 自绘框（简介 50dp 可换行）。
 *
 * 行高与空白逐项压：标签 10sp/12sp 行高、卡标题 11sp/13sp、卡内边距 12/6dp、
 * 字段行距 6dp、标题到字段 4dp、卡间距 8dp、页头 6dp —— 整页约 599dp，
 * 而 360×792dp 的机器可用高度约 736dp，既不滚动、底部也留得出余量。
 * （1.4.85 是 739dp，溢出 3dp 把第三张卡与说明切掉；1.4.86 压到 640dp；这一版再压一档。）
 *
 * ② 卡面复用全 App 唯一的 [cardSurface]（白面 + 4dp 阴影 + 12dp 圆角，深色下靠明度差分层的
 * 那一套）；每张卡标题左侧一个 12dp **语义图标**（人 / 工作 / 链接），统一取 `Outlined`
 * 变体 —— `Filled` 的实心人像与实心包和 `Link` 的细线混在一页里，粗细先打架。
 *
 * ③ **砍掉页脚「取消 / 保存」**：两个按钮与顶部「保存」重复，且页脚定高会挤掉最后一张卡。
 * 离开只剩两条路（左上角返回、系统返回键），两条都走 [ProfileEditScreen] 里的同一个
 * [attemptBack]：**有未保存改动先弹确认**，没有就直接退。系统返回交给 [PageBackHandler]
 * （注册在 `PageSwitcher` 的兜底之后 ⇒ 优先命中，见 PageTransitions.kt 的注册顺序说明）。
 *
 * ④ 顶部「保存」从纯文字换成**白字蓝底浮层小卡**：尺寸贴着原来的 14sp 文字按钮
 * （11sp 字 + 10/4.5dp 内边距 ≈ 42×24dp，原来约 28×19dp），带 2dp 阴影、8dp 圆角，
 * 与标题垂直居中、右缘与卡片对齐 —— 一个常驻但低噪音的动作，而不是第二个主按钮。
 * 第一版做成 57×28dp 后，在 360dp 宽的屏上已经是块「蓝按钮」，比要替代的文字还抢眼。
 */
@Composable
fun ProfileEditScreen(
    sessionJson: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optString("host", "github.com") }.getOrDefault("github.com")
    }
    val token = remember(sessionJson) {
        runCatching { JSONObject(sessionJson).optJSONObject("token")?.optString("access_token").orEmpty() }.getOrDefault("")
    }
    val user = remember(sessionJson) { runCatching { JSONObject(sessionJson).optJSONObject("user") }.getOrNull() }

    fun field(key: String): String =
        user?.optString(key)?.takeIf { it.isNotBlank() && it != "null" } ?: ""

    // 进入页面时的一份快照：既是「未保存改动」的判据，也是 PATCH body 的基线。
    // 不能每次重组重读 sessionJson —— 保存失败后用户继续编辑时，会话里的值可能已经被刷新。
    val original = remember(sessionJson) {
        PROFILE_FIELDS.associateWith { field(it) }
    }

    var name by remember { mutableStateOf(original.getValue("name")) }
    var bio by remember { mutableStateOf(original.getValue("bio")) }
    var company by remember { mutableStateOf(original.getValue("company")) }
    var location by remember { mutableStateOf(original.getValue("location")) }
    var blog by remember { mutableStateOf(original.getValue("blog")) }
    var twitter by remember { mutableStateOf(original.getValue("twitter_username")) }

    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    val current = mapOf(
        "name" to name,
        "bio" to bio,
        "company" to company,
        "location" to location,
        "blog" to blog,
        "twitter_username" to twitter,
    )
    val changed = changedProfileFields(current, original)

    LaunchedEffect(Unit) { Logger.ui("进入编辑资料页", "Compose") }

    // 保存中不退出：请求已经发出，此刻离开的话成功/失败都没有落点。
    fun attemptBack() {
        if (saving) return
        if (changed.isNotEmpty()) confirmLeave = true else onBack()
    }

    // 自己注册的返回处理器晚于 PageSwitcher 的兜底注册 ⇒ 系统返回键先到这里。
    // 弹窗开着时返回键只关弹窗（与 AlertDialog 的 dismiss 同一语义），不叠加第二层询问。
    PageBackHandler {
        if (confirmLeave) confirmLeave = false else attemptBack()
    }

    fun save() {
        if (saving) return
        if (changed.isEmpty()) {
            feedback = Feedback(context.getString(R.string.state_no_changes_to_save), ok = false)
            return
        }
        if (blog.isNotBlank() && !blog.startsWith("http://") && !blog.startsWith("https://")) {
            feedback = Feedback(context.getString(R.string.error_website_scheme), ok = false)
            return
        }

        // 只提交变化字段
        val body = JSONObject()
        changed.forEach { body.put(it, current.getValue(it)) }

        scope.launch {
            saving = true
            feedback = null
            val taskId = TaskStore.start(context, TaskKind.SYNC, context.getString(R.string.action_update_github_profile))
            val err = withContext(Dispatchers.IO) { RustBridge.updateProfile(host, token, body.toString()) }
            saving = false
            if (err == null) {
                TaskStore.success(context, taskId, context.getString(R.string.state_updated_fields, body.length()))
                Logger.net("PATCH /user → 200（${body.length()} 个字段）", "GitHubAPI")
                feedback = Feedback(context.getString(R.string.state_saved), ok = true)
                onSaved()
            } else {
                TaskStore.fail(context, taskId, context.engineErrorOrRaw(err))
                feedback = Feedback(context.getString(R.string.error_save_failed, context.engineErrorOrRaw(err)), ok = false)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Primer.BackgroundPrimary)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 页头：返回 + 标题 + 常驻保存（右缘与卡片同一条 16dp 竖线，纵向与标题居中）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back),
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { attemptBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_edit_profile), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            SavePill(saving = saving, onClick = { save() })
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // 页头是裸的、下面是卡：留 4dp 把卡面的阴影和标题隔开，否则第一张卡像贴在页头下沿
            Spacer(Modifier.height(4.dp))

            // 头像区：**单独一张浮层卡**。它不是可编辑字段（头像只能去网页端换），
            // 但既然整页都换成了浮层语言，单独一行裸字夹在三张卡中间反而像漏做的一块。
            Column(
                Modifier
                    .fillMaxWidth()
                    .cardSurface(shape = CardShape, elevation = CardElevation)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(Primer.Blue500),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (field("login").ifBlank { "?" }).take(1).uppercase(),
                            color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "@${field("login")}",
                            fontSize = 13.5.sp, lineHeight = 16.sp,
                            fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary,
                        )
                        Text(
                            stringResource(R.string.note_avatar_on_web),
                            fontSize = 10.sp, lineHeight = 13.sp, color = Primer.TextTertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            EditCard(Icons.Outlined.Person, stringResource(R.string.label_profile_section_basic)) {
                EditField(stringResource(R.string.label_name), name, stringResource(R.string.hint_display_name), { name = it })
                EditField(stringResource(R.string.label_bio), bio, stringResource(R.string.hint_bio), { if (it.length <= 160) bio = it }, singleLine = false, last = true)
            }

            Spacer(Modifier.height(8.dp))

            EditCard(Icons.Outlined.Work, stringResource(R.string.label_profile_section_work)) {
                EditField(stringResource(R.string.label_company), company, stringResource(R.string.hint_company), { company = it })
                EditField(stringResource(R.string.label_location), location, stringResource(R.string.label_city_region), { location = it }, last = true)
            }

            Spacer(Modifier.height(8.dp))

            EditCard(Icons.Outlined.Link, stringResource(R.string.label_profile_section_links)) {
                EditField(stringResource(R.string.label_website), blog, "https://example.com", { blog = it }, mono = true)
                EditField(stringResource(R.string.label_social_accounts), twitter, stringResource(R.string.hint_twitter_username), { twitter = it }, mono = true, last = true)
            }

            // 说明也单独成卡：它挂在三张字段卡下面时，读者分不清它是「最后一张卡的注解」
            // 还是页脚；给它自己的卡面，边界就是语义边界。
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .cardSurface(shape = CardShape, elevation = CardElevation)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.note_profile_visibility),
                    fontSize = 10.sp,
                    color = Primer.TextTertiary,
                    lineHeight = 14.sp,
                )
            }

            feedback?.let { fb ->
                Spacer(Modifier.height(8.dp))
                Text(
                    fb.text,
                    fontSize = 12.5.sp,
                    // 语气由产生方给出（见 Feedback），不从文案里猜：
                    // 文案抽成资源后 `startsWith("已")` 在英文界面下永不成立
                    color = if (fb.ok) Primer.Green500 else Primer.Red500,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = {
                Text(
                    stringResource(R.string.confirm_unsaved_profile_title),
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary,
                )
            },
            text = {
                Text(
                    stringResource(R.string.note_unsaved_profile_body),
                    fontSize = 12.sp, color = Primer.TextTertiary, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onBack() }) {
                    Text(stringResource(R.string.action_discard_changes), color = Primer.Red500)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text(stringResource(R.string.action_keep_editing), color = Primer.Blue500)
                }
            },
        )
    }
}

/**
 * 头顶的保存动作：**白字蓝底浮层小卡**。
 *
 * 不是主按钮（主按钮在页脚已经被砍掉，见文件头决策③），所以体积贴着一行字
 * （11sp 字 + 10/4.5dp 内边距 ≈ 42×24dp，原来 14sp 纯文字约 28×19dp）；
 * 阴影 2dp —— 比 [CardElevation]（4dp）低两档，读成「浮在页头的一条动作」而不是一张卡。
 * 按压反馈复用全 App 唯一的 [rememberPressFeedback]。
 */
@Composable
private fun SavePill(saving: Boolean, onClick: () -> Unit) {
    val press = rememberPressFeedback()
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .graphicsLayer {
                scaleX = press.scale.value
                scaleY = press.scale.value
            }
            .shadow(2.dp, shape)
            .clip(shape)
            .background(if (saving) Primer.Gray300 else Primer.Blue500)
            .clickable(
                interactionSource = press.interaction,
                indication = LocalIndication.current,
                enabled = !saving,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (saving) stringResource(R.string.state_saving) else stringResource(R.string.action_save),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

/**
 * 一张分组卡：**浮层卡面 + 一个语义图标 + 组标题**，组内字段由调用方给。
 *
 * 图标只做扫视锚点（12dp Outlined、[Primer.IconSecondary]），不参与语义 —— 读写资料全靠字段本身，
 * 所以 `contentDescription = null`（读屏要念的是字段标签，不是装饰图标）。
 */
@Composable
private fun EditCard(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .cardSurface(shape = CardShape, elevation = CardElevation)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Primer.IconSecondary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
            Text(title, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, color = Primer.TextPrimary)
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/**
 * 紧凑输入行：10.5sp 标签 + 36dp 自绘框（简介 54dp、可换行）。
 *
 * 为什么不用 `OutlinedTextField`：它带着 Material 的 56dp 最小高度与 16dp 内边距，
 * 六行排下来比一屏还高（改版前那一版就是这样，必须滚动才能看到最后的「保存」）。
 * 这里只需要「框 + 单行字 + 占位符」，自绘比去覆盖 Material 的默认尺寸更短也更好读。
 *
 * 未聚焦的描边用 [Primer.Border]（= 改版前 Material 的 outline），聚焦才变 [Primer.Blue500]；
 * 聚焦后不再画占位符 —— 光标从 x=0 起，
 * 会正好压住占位符第一个字（与 `TranslateModelDialogs` 同一处理）。
 */
@Composable
private fun EditField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    mono: Boolean = false,
    last: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val font = if (mono) FontFamily.Monospace else null
    Column(Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 6.dp)) {
        Text(label, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
        Spacer(Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 6,
            textStyle = TextStyle(
                fontSize = 12.5.sp,
                lineHeight = 15.sp,
                color = Primer.TextPrimary,
                fontFamily = font,
            ),
            cursorBrush = SolidColor(Primer.Blue500),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (singleLine) 34.dp else 50.dp)
                        .clip(shape)
                        // 未聚焦用装饰描边（= 改版前 OutlinedTextField 的 outline）：
                        // 六条 1dp 纯黑框在 360dp 屏上会读成「线框稿」，把整页压成表格；
                        // 聚焦态的 Blue500 才是「这里能输入」的那条提示。
                        .border(1.dp, if (focused) Primer.Blue500 else Primer.Border, shape)
                        .padding(horizontal = 9.dp, vertical = if (singleLine) 0.dp else 6.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && !focused) {
                        Text(
                            placeholder,
                            fontSize = 12.sp,
                            lineHeight = 15.sp,
                            color = Primer.TextTertiary,
                            fontFamily = font,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** 参与「只提交变化字段」的键，顺序即 PATCH body 的字段顺序。 */
private val PROFILE_FIELDS: List<String> =
    listOf("name", "bio", "company", "location", "blog", "twitter_username")

/**
 * 哪些字段**有变化**（纯函数，便于单测）。
 *
 * 两个用途同一判据：
 * - `save()` 只把这些键写进 PATCH body（未改动的字段不上传，避免覆盖）；
 * - 返回时据此决定要不要弹「未保存」确认 —— 改回原值就等于没改，不该拦。
 */
internal fun changedProfileFields(
    current: Map<String, String>,
    original: Map<String, String>,
): List<String> = PROFILE_FIELDS.filter { current[it].orEmpty() != original[it].orEmpty() }
