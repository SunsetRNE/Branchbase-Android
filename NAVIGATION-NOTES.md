# 返回键与导航（返回键只有一条链路，别再各写各的）

> 这份文档存在的理由：返回键的 bug **反复出现**（同一个位置改过三次，每次都能"修好"又复发）。
> 根因不是某处写错，而是**判定散落在每一页里**。这里把整条链路写清楚，改之前先读完。

## 一、谁在管返回键

| 层 | 位置 | 职责 |
|----|------|------|
| 顶层策略 | `LoginFlow` | 登录流程中间态 → 回欢迎页；**欢迎页不拦截** → 系统默认退出 App |
| 已登录顶层 | `MainScreen` / `LoggedInGate`（引导页） | 顶层 → **再按一次退出应用**（`rememberTopLevelBackAction`）；更深路由 → 关掉当前页 |
| 页面内部 | `ProfileScreen` / `RepositoryScreen` / `NotificationScreen` | 子页 / 多选态 / 面板：关掉自己那一层 |
| 统一入口 | `PageBackHandler`（`ui/navigation/PageTransitions.kt`） | **所有页面级返回键都走它**，不要裸用 `BackHandler` |
| 顶层动作 | `rememberTopLevelBackAction`（`ui/navigation/TopLevelBack.kt`） | 双击窗口（2s）+ Toast 提示 + `finish()`；纯函数 `shouldExitOnBack` 有单测 |

## 二、两条必须遵守的规则

### 规则 1：只有当前页能抢返回键

Compose 的返回键是「**最后注册且启用者胜**」。而 `AnimatedContent`（`PageSwitcher` / `TabSwitcher`）
会把**旧页继续留在组合树里**播完退场动画（200~260ms），旧页的 `BackHandler` 在此期间仍然注册且启用。

于是出现最典型的现场：

```
主界面顶层按返回 → 触发顶层动作（外层 PageSwitcher 开始播动画）
用户在动画没播完时再按一次 → 这一下被「退场中的 MainScreen」吃掉 → App 没退出
用户感受：说好的「再按一次退出」，按了两次都没退出
```

`PageSwitcher` / `TabSwitcher` 会给内容下发 `LocalPageActive`（目标页 = true，退场中的旧页 = false），
`PageBackHandler` 把它与页面自身条件取与。**判定提成了纯函数 `shouldHandleBack(enabled, pageActive)` 并有单测**。

### 规则 2：页面状态要收敛成一条路由，且返回键按路由分派

不要写 `if (x != null) { 页面(); return }`：那样状态一清空，退场中的页面会先变空白再淡出（闪烁），
而且返回键无处安放。用 `PageSwitcher(state = route)`，路由**携带页面数据**
（如 `RepoRoute.Issue(number)`），退场期间 `AnimatedContent` 会把旧路由原样交回。

## 三、已踩过的坑（改之前先看这里）

| 坑 | 现象 | 正确做法 |
|----|------|---------|
| 顶层写 `enabled = route.depth > 0` | 顶层整个关掉 handler，把「顶层按返回」让给外层兜底 → 中间多一层门（如提交模式引导页）就**直接退出 App** | 顶层也显式分派（见 `backDisposition`） |
| 顶层改成 `enabled = true` | 退场动画期间仍然启用 → **吃掉第二次返回键**，「再按一次退出」失灵 | 一律用 `PageBackHandler`（叠加 `LocalPageActive`） |
| 每个页面各挂一个裸 `BackHandler` | 谁赢取决于注册顺序，动画期间新旧两页会抢同一个事件 | 全部换成 `PageBackHandler`；多层页面用嵌套 `PageSwitcher` 各自下发 active |
| 把 busy/error 塞进页面状态（如密钥填写页） | 状态值一变就被当成换页：多播一次动画 + 重建内容丢输入 | 页面身份与请求态分开（页面态存 `data object`，请求态用独立 `StateFlow`） |

## 四、当前行为（用户可见的契约）

1. 已登录时主界面**顶层 Tab** 按返回 → 弹出「再按一次返回退出应用」，**留在主界面**；
2. 2 秒内再按一次 → **彻底退出 App**（超时则重新从第 1 条开始）；
3. 主界面内的子页 / 详情 / 多选态按返回 → 逐层关闭，**不会**跳到登录页；
4. 登录流程中间态（模式介绍 / 密钥填写 / 授权中 / 2FA）按返回 → 回欢迎页；
5. 密钥填写页按返回 → 回密钥介绍页（不是直接跳出流程）；
6. 未登录（欢迎页）按返回 → 直接退出 App（没有会话可保，不需要二次确认）；
7. 登录后的提交模式引导页 → 与主界面顶层同一条规则（第 1、2 条）。

> **为什么不再「回登录首页」**（2026-09 用户反馈修掉）：会话是持久化的，被丢回登录页后重启应用又直接回主界面，
> 于是登录页成了一个与真实登录状态不符的死状态，用户以为自己被登出了。
> 现在顶层只做「退出」，防误触退出由**双击确认**承担 —— 两个意图都不丢。
> 想改成「顶层直接退出」（不要二次确认），把 `MainScreen` / `LoggedInGate` 里的
> `rememberTopLevelBackAction()` 换成直接 `finish()` 即可，其余层不用动。

## 五、改动清单（自检）

- [ ] 新页面用了 `PageBackHandler` 而不是裸 `BackHandler`？
- [ ] 新页面在 `PageSwitcher` / `TabSwitcher` 里（能拿到 `LocalPageActive`）？
- [ ] 多一层可返回的页面时，用的是嵌套 `PageSwitcher`，而不是在同一层堆 `if`？
- [ ] 跑过 `PageTransitionsTest` / `TopLevelBackTest`（方向 + 返回键规则 + 双击退出窗口）？
