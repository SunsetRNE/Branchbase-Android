package com.branchbase.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 账号头像地址的解析（`Account.avatarUrl` / `AccountStore.avatarUrlOfSession`）。
 *
 * 契约：**快照优先，快照缺失才回落会话里的 `user.avatar_url`**。
 *
 * 为什么要回落：`avatar` 是登录时抓下的快照，而老版本单会话迁移成的账号
 * （`AccountStore.migrateIfNeeded`）根本没有这个字段 —— 只有会话。渲染层
 * （设置页账户卡 / 启动预热）都走 `avatarUrl`，所以这条回落一旦断了，
 * 这类账号就永远只显示首字母，哪怕会话里头像地址还在。
 */
class AccountAvatarTest {

    private fun sessionWithAvatar(url: String?) = buildString {
        append("""{"host":"github.com","token":{"access_token":"t"},"user":{"login":"octocat"""")
        if (url != null) append(""","avatar_url":"""").append(url).append('"')
        append("}}")
    }

    @Test
    fun `有快照时用快照_不看会话`() {
        val acc = Account(
            id = "a",
            login = "octocat",
            avatar = "https://snapshot/a.png",
            session = sessionWithAvatar("https://session/b.png"),
        )
        assertEquals("https://snapshot/a.png", acc.avatarUrl)
    }

    @Test
    fun `快照缺失时回落到会话里的头像`() {
        val acc = Account(
            id = "a",
            login = "octocat",
            avatar = null,
            session = sessionWithAvatar("https://session/b.png"),
        )
        assertEquals("https://session/b.png", acc.avatarUrl)
    }

    @Test
    fun `会话里也没有头像时为空`() {
        val acc = Account(id = "a", login = "octocat", session = sessionWithAvatar(null))
        assertNull(acc.avatarUrl)
    }

    @Test
    fun `空白头像不当成地址`() {
        assertNull(AccountStore.avatarUrlOfSession(sessionWithAvatar("   ")))
    }

    @Test
    fun `会话不是合法 JSON 时不崩且为空`() {
        assertNull(AccountStore.avatarUrlOfSession("not json"))
        assertNull(Account(id = "a", login = "octocat", session = "not json").avatarUrl)
    }

    @Test
    fun `会话里缺 user 节点时为空`() {
        val session = JSONObject("""{"host":"github.com","token":{"access_token":"t"}}""").toString()
        assertNull(Account(id = "a", login = "octocat", session = session).avatarUrl)
    }
}
