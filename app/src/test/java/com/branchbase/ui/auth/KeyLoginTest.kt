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
        assertEquals(2, LoginState.NeedTwoFactor.depth)
        assertEquals(2, LoginState.Error("x").depth)
        assertEquals(3, LoginState.LoggedIn("{}").depth)
    }

    @Test
    fun `密钥填写页返回回到介绍页_其余中间态回欢迎页`() {
        // 用户刚看过「密钥登录介绍页」才进来的，返回应回到那一页，而不是跳出整个流程
        assertEquals(LoginState.KeyIntro, loginBackTarget(LoginState.KeyInput))
        assertEquals(LoginState.Idle, loginBackTarget(LoginState.KeyIntro))
        assertEquals(LoginState.Idle, loginBackTarget(LoginState.OAuthIntro))
        assertEquals(LoginState.Idle, loginBackTarget(LoginState.Authorizing("u", "v")))
        assertEquals(LoginState.Idle, loginBackTarget(LoginState.NeedTwoFactor))
        assertEquals(LoginState.Idle, loginBackTarget(LoginState.Error("boom")))
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
}
