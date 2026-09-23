# 本地 Git 引擎（libgit2）· 稳定接口 / 错误归一 / 边界

> **这份文档是什么**：`core/src/git/mod.rs`（Rust 侧 Git 工具包）的**对外条款** ——
> 它给上层（决策页面、本地仓库页、分支同步页）承诺了什么、错误怎么归一、哪些事**刻意不做**。
>
> **谁该读**：改 `core/src/git/` 的人；写决策页面 / 本地仓库相关 UI 的人；review 这些改动的人。
>
> **本文是补写的。** 代码注释里一直引用 `docs/code-editing-collaboration-thinking.md` §9
> （`core/src/git/mod.rs:3`：「Git 引擎（libgit2）+ 稳定接口」），但那份文档**从未进过版本库**
> （`git log --all -- docs/code-editing-collaboration-thinking.md` 0 条记录 —— 与 `docs/html-parser-design.md`
> 同一段历史：`/docs/` 曾被整体 `.gitignore`）。补写时以**代码实际行为**为准，
> 注释已改指本文 §3 / §4。
>
> **真源**：`core/src/git/mod.rs`（20 个 `pub fn`）· JNI 导出 `core/src/bridge/jni.rs` ·
> Kotlin 侧门面 `app/src/main/java/com/branchbase/core/RustBridge.kt`。

---

## 1. 职责与边界

管**本地工作树**：clone / pull / commit / push、本地分支增删切、撤销与丢弃、证书与代理。
不管**远端仓库管理**（分支 ref、PR、合并、仓库设置）—— 那些走 REST，见
[`decision-pages-design.md`](decision-pages-design.md) §8.4。

**刻意不做的事**（改之前先看这里）：

| 不做 | 为什么 | 证据 |
|---|---|---|
| 不 merge / rebase | 已推送历史不改写；分叉只给「分叉决策页」，由用户选 | `core/src/git/mod.rs:761`「不改写历史，对齐 D11 边界」 |
| 不隐式 stash | 脏工作区切分支时宁可**拒绝**，也不替用户藏改动 | `:348`、`:432`「不做任何隐式丢弃」；UI 侧 `LocalBranchSyncScreen.kt:340` 明说「不会隐式 stash」 |
| 不自动切分支去拉取 | 落后的非当前分支交给用户逐个「切换并拉取」 | `ui/repository/LocalBranchSyncModels.kt:110` |
| 不碰 `main`/`master` 的删除 | 引擎层拒绝删除当前分支，保护性置灰由 UI 做 | `:413`「当前分支拒绝；调用方另外把 main/master 置灰」 |

## 2. 为什么是 libgit2

| 决策 | 理由 | 证据 |
|---|---|---|
| `git2` 0.18 + `vendored-libgit2` + `vendored-openssl` | Android 交叉编译不能依赖系统 libgit2 / OpenSSL | `core/Cargo.toml:36-37` |
| HTTPS 走 vendored OpenSSL，**自验证书链** | Android 系统没有 OpenSSL 兼容的 CA 路径，必须自备 bundle | `init_ssl_certs:924`、`:478-484`（证书验证段：内置 Mozilla CA bundle） |
| 通配符匹配是**自实现**的 | 早期实现把 `xgithub.com` 误判为匹配 `*.github.com`；现在要求 `.<suffix>` 前恰好一段主机名 | `dns_matches:501`（要求见 `:496-500`） |
| 验证失败**不静默放行** | 交还 libgit2，其 openssl 后端会失败 | `check_cert:563-568` |
| 浅 clone（`depth(1)`） | 减体积；代价是不能查历史（见 §7） | `:37`（`fo.remote_callbacks(callbacks).depth(1); // 浅 clone，减体积`） |
| 与 REST 通道**不共用连接池** | 两条通道的凭据与生命周期不同（Git 走 HTTPS+token，REST 走 reqwest 客户端） | [`reachability-design.md`](reachability-design.md) §五 |

## 3. 稳定接口（`core/src/git/mod.rs` 的 20 个 `pub fn`）

> 「稳定」= 函数名、参数含义、返回约定是**对外条款**（表中行号只作定位参考，会随文件演进漂），改签名要同时改 JNI 与 Kotlin 门面，
> 并重建 `.so`（见 [`BUILD-NOTES.md`](BUILD-NOTES.md) §四）。

**返回约定**：成功返回 `Ok(JSON 字符串)`（无返回值的返回空串）· 失败返回 `Err`，
经 JNI 变成 `"ERROR: ..."` 前缀串（`core/src/bridge/jni.rs:37`），Kotlin 侧 `RustBridge` 用
`startsWith("ERROR:")` 判失败。

| 分组 | 函数（`:行`） | 说明 |
|---|---|---|
| clone / pull / commit / push | `clone_repo:14` · `pull_repo:52` · `commit_repo:126` · `push_repo:446` | `clone_repo` 支持指定分支与 PAT；`pull_repo` **只做 fast-forward**；`commit_repo` 暂存全部改动并返回 sha |
| 本地分支 | `local_branches:173` · `checkout_branch:349` · `create_branch_local:383` · `delete_branch_local:414` | `local_branches` 输出 `name/is_head/upstream/ahead/behind`，**当前分支排最前**；`create_branch_local` **新建后立即切换** |
| 远端跟踪 | `fetch_remote:229` · `remote_branches:268` | `fetch_remote` **只刷新 `refs/remotes/origin/*`**，不合并、不动工作区（「先看清再决定」原语）；`remote_branches` 的跟踪匹配**先看 upstream 配置、同名兜底** |
| 撤销 / 丢弃 | `discard_all_changes:433` | 恢复已跟踪文件 + 删除未跟踪文件；**只在用户确认后调用** |
| 证书 / 代理 | `init_ssl_certs:924` · `set_git_proxy:979` | `init_ssl_certs` 全局生效一次；`set_git_proxy` 写全局 gitconfig 的 `[http] proxy`（空串 = 清除） |
| 决策页面支持 | `repo_status` · `reset_soft` · `reset_hard_to_remote` · `amend_message` · `revert_commit` · `push_set_upstream` · `scan_sensitive`（同一段注释之下，按名字找） | 条款写在 [`decision-pages-design.md`](decision-pages-design.md) §6，本文不重复 |

JNI 侧对应导出（`core/src/bridge/jni.rs`）：`nativeGitClone:867`、`nativeGitPull:890`、`nativeGitCommit:992`、
`nativeGitPush:1013`、`nativeGitStatus:1290`、`nativeGitResetSoft:1303`、`nativeGitResetHardRemote:1317`、
`nativeGitAmend:1333`、`nativeGitRevert:1349`、`nativeGitPushSetUpstream:1371`、`nativeGitInitSsl:1405`。

## 4. 错误归一：`nff:` 前缀 = 分叉决策页的触发信号

**这是本节最容易被忽略、也最不该动的一条**：

```rust
/// 推送错误归一：被拒（非快进/锁 ref/rejected）→ `nff:` 前缀，供上层触发分叉决策页；
/// 其余原样透出为 `push 失败:`。
fn map_push_error(msg: &str) -> CoreError { ... }
```

（`core/src/git/mod.rs:1001-1010`）

- 命中条件：`non-fast-forward` / `cannot lock ref` / `rejected` → `nff: 推送被拒（远端领先）: …`；
- pull 侧同样归一：本地有未推送提交且远端领先 → `Err("nff: 本地与远端分叉（non-fast-forward）")`（`:118-121`）；
- **上层依赖这个前缀**：`nff:` 出现即弹**分叉决策页（P0-1）**，其余错误按普通失败提示；
- 改动风险：把 `nff:` 改掉 / 改成别的文案，分叉决策页**再也不会被触发**，
  用户看到的是「推送失败」而不是「要你选合并还是覆盖」。

## 5. 既定决策登记（代码里只剩裸编号的那些）

| 编号 | 内容 | 现状 |
|---|---|---|
| **D3** | 草稿隔离目录：编辑草稿落在 `files/edit/...`，与正式文件分开 | `ui/decision/CommitPrepScreens.kt:376`、`ui/repository/RepositoryFileViewer.kt:121` |
| **D10** | 「Git 化」是提交模式③（本地仓库）下的子开关 | `ui/decision/SyncDecisionScreens.kt:238` |
| **D11** | **不改写已推送历史**：不做 merge/rebase，已推送的提交只能 revert | `SyncDecisionScreens.kt:101,490`、`core/src/git/mod.rs:761` |

> 这三个编号原本登记在丢失的 `docs/code-editing-collaboration-thinking.md` 里，现在只剩代码里的裸引用 ——
> 本表就是它们的登记处；再出现新的 `Dxx` 请加到这里。

## 6. 证书与代理

- **首次使用前必须初始化证书**：`RustBridge` 在开工前调 `nativeGitInitSsl`，
  把内置 Mozilla CA bundle 写到 `{dir}/branchbase-cacert.pem` 并注入 libgit2（`RustBridge.kt:1024`）。
  没做这一步 → 所有 HTTPS Git 操作失败在证书上。
- **代理是全局 gitconfig**：`set_git_proxy` 写 `[http] proxy`，重启后由 `init_ssl_certs` 保留（`:976-979`）；
  取值语义见 [`settings-design.md`](settings-design.md) §8.1（空串 = 不使用代理）。
- 证书链验证是**自实现**的：SAN 优先 → CN 兜底（`hostname_matches:519`）、逐级找 issuer 走到自签名根（`verify_cert_chain:541`）。

## 7. 已知边界

1. **浅 clone 的历史不可用**：`depth(1)` 意味着 `log` / `blame` / 历史 diff 都拿不到（`:37`）。
2. **pull 只 fast-forward**：分叉即 `nff:` 报错，不做自动 merge（`:51-52`、`:119-120`）。
3. **脏工作区不隐式 stash**：切分支会被 libgit2 拒绝，UI 负责提示「撤销改动后再切换」（`:345-349`）。
4. **远端跟踪匹配有优先级**：先 upstream 配置、同名兜底 —— 避免「本地 `main` 跟踪 `origin/other`」被误判（`:266-268`）。
5. **`discard_all_changes` 会删未跟踪文件**：这是「撤销工作区」的完整语义，调用方必须二次确认（`:430-433`）。
6. **主分支保护只在 UI**：引擎层只拒绝删当前分支，`main`/`master` 的置灰是调用方的责任（`:413`）。

## 8. 钉子与验收

- **单测**：`core/src/git/mod.rs` 内 `mod tests` 共 **15** 个 `#[test]`（`cargo test` 会连集成测试一起跑：
  60 个单测 + 4 个 `core/tests/deepseek_http.rs`）。与决策页相关的是 `scan_sensitive`（5 条）、
  `map_push_error`（2 条）、`repo_status`（3 条：`dirty` 顺序、父子提交与完整 sha、远端 ref 三态含悬挂符号引用）。
- **改这块时要跑的**：`cd core && cargo test`；若是接口（签名/返回约定）改动，
  还要 `./gradlew :app:testDebugUnitTest`（`JniSignatureTest` 逐参数比对，见 [`BUILD-NOTES.md`](BUILD-NOTES.md) §四）
  并重建 `.so`。
- **没有钉子、只能靠 review 的**：真实远端上的 clone/pull/push 行为（需要网络与凭据）。
