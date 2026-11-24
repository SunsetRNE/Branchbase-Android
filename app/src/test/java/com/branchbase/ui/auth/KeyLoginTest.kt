package com.branchbase.ui.auth

import com.branchbase.core.AccountStore
import com.branchbase.ui.repository.sessionInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密钥登录与登录流程层级的单测。
 *
 * 这些是「改一行就可能悄悄坏掉」的契约：会话 JSON 的字段名、权限清单、
 * 网页端链接的预填参数、以及返回键要回到哪一层。它们在真机上都要走完整流程才试得出来。
 */
class KeyLoginTest {

    @Test
    fun `密钥会话结构与既有链路兼容`() {
        val user = JSONObject("""{"login":"octocat","avatar_url":"https://x/a.png"}""")
        val session = buildKeySession(host = "github.com", token = "ghp_abc", user = user)

        // ① 与 sessionInfo()（业务页到处在用）兼容：host / token.access_token / user.login
        val (host, token, login) = sessionInfo(session)
        assertEquals("github.com", host)
        assertEquals("ghp_abc", token)
        assertEquals("octocat", login)

        // ② 与 AccountStore.accessTokenOf() 兼容（兼容 {"token":{...}} 与裸 access_token 两种写法）
        assertEquals("ghp_abc", AccountStore.accessTokenOf(session))
    }

    @Test
    fun `权限清单与_oauth_申请范围一致`() {
        // 少勾 read:user 会让密钥校验直接失败；少勾 repo 会「能登录但提交报错」——
        // 两边范围不一致属于最容易埋雷的地方，这里钉住
        assertEquals(listOf("repo", "read:user", "read:org", "notifications"), KEY_LOGIN_SCOPES)
        assertEquals(OAuthCredentials().scopes.sorted(), KEY_LOGIN_SCOPES.sorted())
    }

    @Test
    fun `网页端建密钥链接预填了全部权限`() {
        val url = keyTokenCreateUrl()
        assertTrue(url.startsWith("https://github.com/settings/tokens/new?"))
        // 预填参数必须逐项都在（否则用户到了网页还得自己猜要勾什么）
        KEY_LOGIN_SCOPES.forEach { assertTrue("链接缺少权限 $it：$url", url.contains(it)) }
        assertTrue(url.contains("description=Branchbase"))
    }

    @Test
    fun `登录流程层级：欢迎_0_介绍_1_密钥填写_2_主界面_3`() {
        assertEquals(0, LoginState.Idle.depth)
        assertEquals(1, LoginState.OAuthIntro.depth)
        assertEquals(1, LoginState.KeyIntro.depth)
        assertEquals(2, LoginState.KeyInput.depth)
        assertEquals(2, LoginState.Authorizing("u", "v").depth)
        assertEquals(2, LoginState.ExchangingToken.depth)
        assertEquals(2, LoginState.Error("x", LoginFailStep.OAUTH_START).depth)
        assertEquals(3, LoginState.LoggedIn("{}").depth)
    }

    @Test
    fun `密钥填写页返回回到介绍页_其余中间态回欢迎页`() {
        // 用户刚看过「密钥登录介绍页」才进来的，返回应回到那一页，而不是跳出整个流程
        assertEquals(LoginState.KeyIntro, loginBackTarget(LoginState.KeyInput))
        assertEquals(LoginState.Idle, loginBackTarget(LoginState.KeyIntro))
        assertEquals(LoginState.Idle, loginBackTarget(LoginState.OAuthIntro))
        // 授权进度（授权中 / 换 token）的上一页是**授权登录的流程要点页**（2026-11-24）：
        // 用户刚看过那一页才点的「开始授权」，退回去应当能接着看说明 / 重新开始
        assertEquals(LoginState.OAuthIntro, loginBackTarget(LoginState.Authorizing("u", "v")))
        assertEquals(LoginState.OAuthIntro, loginBackTarget(LoginState.ExchangingToken))
        // 出错页的「返回」仍是「换一种登录方式」→ 欢迎页
        assertEquals(LoginState.Idle, loginBackTarget(LoginState.Error("boom", LoginFailStep.OAUTH_EXCHANGE)))
        // 默认（不在新增流程里）退到未登录欢迎页 —— 上面这些就是既有行为，不能变
        assertEquals(LoginState.Idle, loginBackTarget(LoginState.AddAccountWelcome))
    }

    @Test
    fun `新增流程里按返回回新增欢迎页而不是未登录欢迎页`() {
        // 用户本来登录着，从账号页进的「添加账号」。若退到 Idle（未登录欢迎页）：
        // ① 显示一个与真实登录状态不符的界面；② Idle 不拦返回键 → 再按一下直接退出 App。
        listOf(
            LoginState.OAuthIntro,
            LoginState.KeyIntro,
            LoginState.Authorizing("u", "v"),
            LoginState.ExchangingToken,
            LoginState.Error("boom", LoginFailStep.OAUTH_EXCHANGE),
        ).forEach { from ->
            assertEquals(
                "新增流程里 ${from::class.simpleName} 的返回目标应是 AddAccountWelcome",
                LoginState.AddAccountWelcome,
                loginBackTarget(from, addingAccount = true),
            )
        }
        // 填写密钥页仍先回介绍页（两级返回不跳级）
        assertEquals(LoginState.KeyIntro, loginBackTarget(LoginState.KeyInput, addingAccount = true))
    }

    @Test
    fun `会话保留 user 完整字段_账号表要拿它取头像`() {
        // persistAccount 会从会话的 user.avatar_url 取头像写入多账号表；
        // 少带这个字段的话，密钥登录进来的账号在「账号管理」里就没有头像
        val user = JSONObject(
            """{"login":"octocat","avatar_url":"https://x/a.png","name":"Monalisa"}""",
        )
        val session = JSONObject(buildKeySession("github.com", "ghp_abc", user))
        assertEquals("octocat", session.getJSONObject("user").optString("login"))
        assertEquals("https://x/a.png", session.getJSONObject("user").optString("avatar_url"))
    }

    @Test
    fun `建密钥链接支持自定义权限（细粒度密钥场景）`() {
        val url = keyTokenCreateUrl(listOf("read:user", "repo"))
        assertTrue(url.contains("scopes=read:user,repo"))
    }

    // ── 失败端：重试重做「失败的那一步」，不是一律回欢迎页 ──

    @Test
    fun `失败页的返回仍是欢迎页（与失败步骤无关）`() {
        // 返回是「换一条路」，与「重试」是两件事：失败步骤不该影响返回目标
        LoginFailStep.entries.forEach { step ->
            assertEquals(
                "failedAt = $step 时返回目标应仍是 Idle",
                LoginState.Idle,
                loginBackTarget(LoginState.Error("boom", step)),
            )
        }
    }

    // ── OAuth 失败分类：把原始报文折成「能照着做」的三类 ──

    @Test
    fun `OAuth 失败分类能落到可行动的类别`() {
        // 本地构建没注入 secret / OAuth App 的 secret 被重置 —— 都是这一条
        assertEquals(
            OAuthFailure.ClientCredentials,
            classifyOAuthFailure(
                """GitHub 错误 (200 OK): {"error":"incorrect_client_credentials"}""",
            ),
        )
        // code 只能用一次；过期或重复使用都会撞上
        assertEquals(
            OAuthFailure.BadCode,
            classifyOAuthFailure("""GitHub 错误 (200 OK): {"error":"bad_verification_code"}"""),
        )
        assertEquals(
            OAuthFailure.BadCode,
            classifyOAuthFailure("""GitHub 错误 (200 OK): {"error":"invalid_grant"}"""),
        )
        assertEquals(
            OAuthFailure.RedirectMismatch,
            classifyOAuthFailure("""GitHub 错误 (200 OK): {"error":"redirect_uri_mismatch"}"""),
        )
        // 1.4.24 起引擎会发稳定码：码优先，正文只做迁移期兜底
        assertEquals(
            OAuthFailure.ClientCredentials,
            classifyOAuthFailure("AUTH_CLIENT_CREDENTIALS:GitHub 错误 (400): {\"error\":\"incorrect_client_credentials\"}"),
        )
        assertEquals(
            OAuthFailure.BadCode,
            classifyOAuthFailure("AUTH_BAD_CODE:GitHub 错误 (400): {\"error\":\"bad_verification_code\"}"),
        )
        assertEquals(
            OAuthFailure.RedirectMismatch,
            classifyOAuthFailure("AUTH_REDIRECT_MISMATCH:GitHub 错误 (400): {\"error\":\"redirect_uri_mismatch\"}"),
        )
        // 未知错误保留原文，便于贴日志
        val raw = "GitHub 错误 (500 Internal Server Error): upstream timeout"
        assertEquals(OAuthFailure.Other(raw), classifyOAuthFailure(raw))
    }

    @Test
    fun `没注入 client_secret 时不认为 OAuth 可用`() {
        // 本地直接 assembleDebug（没有 local.properties）就是这个状态：
        // 必须**不发请求**直接给可行动的提示，而不是让用户跑完一圈授权再报错
        assertEquals(false, oauthConfigured(OAuthCredentials(clientSecret = "")))
        assertEquals(true, oauthConfigured(OAuthCredentials(clientSecret = "abc")))
        assertEquals(false, oauthConfigured(OAuthCredentials(clientId = "", clientSecret = "abc")))
    }
}
