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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.branchbase.R
import com.branchbase.core.RustBridge
import com.branchbase.ui.log.Logger
import com.branchbase.ui.repository.Feedback
import com.branchbase.ui.task.TaskKind
import com.branchbase.ui.task.TaskStore
import com.branchbase.ui.theme.iconTap
import com.branchbase.ui.theme.Primer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 编辑资料页：修改当前登录用户的 GitHub 公开资料（PATCH /user）。
 *
 * 对齐 GitHub Profile 编辑项：
 * - 名称 / 简介（bio）
 * - 公司 / 位置 / 个人网站 / 社交链接
 * - 仅提交有变化的字段（避免无意义 PATCH）；保存前校验网站 URL
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

    var name by remember { mutableStateOf(field("name")) }
    var bio by remember { mutableStateOf(field("bio")) }
    var company by remember { mutableStateOf(field("company")) }
    var location by remember { mutableStateOf(field("location")) }
    var blog by remember { mutableStateOf(field("blog")) }
    var twitter by remember { mutableStateOf(field("twitter_username")) }

    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }

    LaunchedEffect(Unit) { Logger.ui("进入编辑资料页", "Compose") }

    fun save() {
        // 只提交变化字段
        val body = JSONObject()
        if (name != field("name")) body.put("name", name)
        if (bio != field("bio")) body.put("bio", bio)
        if (company != field("company")) body.put("company", company)
        if (location != field("location")) body.put("location", location)
        if (blog != field("blog")) body.put("blog", blog)
        if (twitter != field("twitter_username")) body.put("twitter_username", twitter)

        if (body.length() == 0) { feedback = Feedback(context.getString(R.string.state_no_changes_to_save), ok = false); return }
        if (blog.isNotBlank() && !blog.startsWith("http://") && !blog.startsWith("https://")) {
            feedback = Feedback(context.getString(R.string.error_website_scheme), ok = false)
            return
        }

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
                TaskStore.fail(context, taskId, err)
                feedback = Feedback(context.getString(R.string.error_save_failed, err), ok = false)
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
        // 头部
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back),
                tint = Primer.IconPrimary,
                modifier = Modifier.size(24.dp).iconTap { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_edit_profile), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(
                if (saving) stringResource(R.string.state_saving) else stringResource(R.string.action_save),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (saving) Primer.TextTertiary else Primer.Blue500,
                modifier = Modifier.clickable { if (!saving) save() },
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // 头像区（只读提示）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(Primer.Blue500),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (field("login").ifBlank { "?" }).take(1).uppercase(),
                        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("@${field("login")}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextPrimary)
                    Text(stringResource(R.string.note_avatar_on_web), fontSize = 11.5.sp, color = Primer.TextTertiary)
                }
            }

            Spacer(Modifier.height(16.dp))

            EditField(stringResource(R.string.label_name), name, stringResource(R.string.hint_display_name), { name = it })
            EditField(stringResource(R.string.label_bio), bio, stringResource(R.string.hint_bio), { if (it.length <= 160) bio = it }, singleLine = false)
            EditField(stringResource(R.string.label_company), company, stringResource(R.string.hint_company), { company = it })
            EditField(stringResource(R.string.label_location), location, stringResource(R.string.label_city_region), { location = it })
            EditField(stringResource(R.string.label_website), blog, "https://example.com", { blog = it }, mono = true)
            EditField(stringResource(R.string.label_social_accounts), twitter, stringResource(R.string.hint_twitter_username), { twitter = it }, mono = true)

            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.note_profile_visibility),
                fontSize = 11.5.sp,
                color = Primer.TextTertiary,
                lineHeight = 17.sp,
            )

            feedback?.let { fb ->
                Spacer(Modifier.height(10.dp))
                Text(
                    fb.text,
                    fontSize = 12.5.sp,
                    // 语气由产生方给出（见 Feedback），不从文案里猜：
                    // 文案抽成资源后 `startsWith("已")` 在英文界面下永不成立
                    color = if (fb.ok) Primer.Green500 else Primer.Red500,
                )
            }
            Spacer(Modifier.height(28.dp))
        }

        // 底部按钮
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(8.dp))
                    .background(Primer.Gray150).border(1.dp, Primer.Border, RoundedCornerShape(8.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.action_cancel), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary) }
            Box(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (saving) Primer.Gray300 else Primer.Green500)
                    .clickable { if (!saving) save() },
                contentAlignment = Alignment.Center,
            ) { Text(if (saving) stringResource(R.string.state_saving) else stringResource(R.string.action_save), fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    mono: Boolean = false,
) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Primer.TextSecondary)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, fontSize = 13.sp, color = Primer.TextTertiary) },
            singleLine = singleLine,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.5.sp,
                fontFamily = if (mono) FontFamily.Monospace else null,
            ),
        )
    }
}
