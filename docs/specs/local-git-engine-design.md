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
| 浅 clone（`depth(1)`） | 减体积；代价是本地只有 HEAD 一条提交 —— 要查历史得先 `fetch_deepen`（见 §3 与 §7.1） | `:37`（`fo.remote_callbacks(callbacks).depth(1); // 浅 clone，减体积`） |
| 与 REST 通道**不共用连接池** | 两条通道的凭据与生命周期不同（Git 走 HTTPS+token，REST 走 reqwest 客户端） | [`reachability-design.md`](reachability-design.md) §五 |

## 3. 稳定接口（`core/src/git/mod.rs` 的 28 个 `pub fn`）

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
| **工作台本地读接口**（阶段 3，**全部只读**） | `log_graph` · `list_tags` · `log_file` · `diff_worktree` · `diff_commit`（同一段注释之下） | 输出是**扁平 native JSON**（不是 GitHub REST 那份嵌套结构）；分页一律 `limit` / `skip`；空仓库给 `[]` 而不是报错。`log_graph` 逐条带 `unpushed`（**HEAD 可达、上游不可达**，与 `repo_status` 的 `ahead` 同口径；没有上游时一条都不标 —— 见 `unpushed_oids`）；`list_tags` 按 D-f 取全字段、轻量 tag **留空不编值**；`diff_*` 的 `patch` 超 200 KB 截断并置 `truncated`；`diff_worktree` 里**未跟踪文件也带内容**（`show_untracked_content`：不给内容的话，「新增一个文件」点开就是一片空白），且 `files[i]` 与 patch 的第 i 段**同序**——上层按下标对齐，不解析路径 |
| **加深克隆**（阶段 4） | `fetch_deepen(dir, depth, token)` | `depth <= 0` = 全量，内部发 `i32::MAX`（与 `git fetch --unshallow` 同一条路；libgit2 也拿 `INT_MAX` 当「不要浅边界」的哨兵）；`depth > 0` = 加深到该条数。**只动对象与 `refs/remotes/origin/*`**：不改工作区、不动本地提交 —— 因此是安全动作，但可能是长任务（进度/取消复用 clone 那一条通道，见 §3.1）。浅克隆里 `.git/shallow` 由 libgit2 在浅边界归零时删掉（`fetch.c:65` + `repository.c` 的 `shallow_roots_write`）—— UI 正是靠它把提交图翻回本地来源 |

JNI 侧对应导出（`core/src/bridge/jni.rs`）：`nativeGitClone`、`nativeGitPull`、`nativeGitCommit`、
`nativeGitPush`、`nativeGitStatus`、`nativeGitResetSoft`、`nativeGitResetHardRemote`、
`nativeGitAmend`、`nativeGitRevert`、`nativeGitPushSetUpstream`、`nativeGitInitSsl`、
`nativeLocalBranches` / `nativeRemoteBranches` / `nativeFetchRemote` / `nativeCheckoutBranch` /
`nativeCreateBranchLocal` / `nativeDeleteBranchLocal` / `nativeDiscardAllChanges`、
`nativeGitLogGraph` / `nativeGitListTags` / `nativeGitLogFile` / `nativeGitDiffWorktree` / `nativeGitDiffCommit`、
`nativeGitFetchDeepen`、
以及进度用的 `nativeGitCloneProgress` / `nativeGitCloneCancel`（见 §4.1）。
**每一对 `external fun` ↔ JNI 导出都由 `JniSignatureTest` 逐参数、逐类型钉着**（参数表写错编译期查不出来）。

### 3.1 clone / 加深的进度与取消（两条只读接口 + 一份快照）

clone 是**分钟级**的操作（浅 clone 一个中等仓库在手机上也要几十秒），而它此前对上层**完全不透明**：
UI 只能挂一句「正在克隆…」。现在引擎往外报进度，口径如下。

**加深（`fetch_deepen`）走的是同一条通道**（1.0.98）：它同样调 `progress::begin/transfer/complete`、
同样读取消标记 —— 上层因此复用同一只进度弹窗与同一套终态处理，**不出现第二种「转圈」**。
差别只有一处：fetch 没有检出阶段，`checkout` 那两个计数恒为 0（`phase` 自然落在 receive / resolve / finalize）。

| 出口 | 位置 | 约定 |
|---|---|---|
| `progress::snapshot_json()` | `core/src/git/progress.rs` | 单行 JSON：`phase` · `received` · `total` · `indexed` · `bytes` · `checkoutDone` · `checkoutTotal`，文件顶部有完整契约表 |
| `request_cancel()` | 同文件 | 置取消标记；正在跑的 clone / 加深在**下一次回调**里中断 |
| `nativeGitCloneProgress()` | `core/src/bridge/jni.rs` | 上面那份快照的 JNI 出口（Kotlin 侧每 200ms 轮询一次） |
| `nativeGitCloneCancel()` | 同上 | 请求取消（没有 clone / 加深在跑时是空操作） |

三条不许改的口径：

1. **`phase` 字符串是稳定契约**（`idle` / `connect` / `receive` / `resolve` / `checkout` / `finalize` / `done` / `failed`），
   翻译成文案是 UI 的事。改了字符串，UI 会退化成「正在准备…」而不是崩（`CloneProgress.Phase.UNKNOWN` 兜底），
   但用户就再也看不到「卡在哪一步」。
2. **没有分母就不给百分比**：`total == 0`（远端没报总数）时 UI 必须走**不确定进度条**，
   而不是拿 `received / 1` 编一个数 —— 真机上「进度条冲到 100% 然后不动」比没有进度条更让人以为卡死。
   百分比口径（阶段加权，5→70→85→100）写在 `CloneProgress.percent` 的 KDoc 里，纯函数、有单测。
3. **取消是「尽快」不是「立刻」**：libgit2 没有取消句柄，唯一的中断点是回调返回值，
   所以网络完全静默时会等到下一次回调才停。UI 必须显示「正在取消…」并继续等结果，不能假装已经停了。

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

### 4.1 clone 侧的错误归一与目标目录规约

clone 不做 `nff:` 这类分支判定，但有两件**必须由引擎收口**的事（`clone_repo` 的文档注释里也写了）：

| 事项 | 规则 | 为什么 |
|---|---|---|
| 目标目录预检 | 不存在 → 放行；**含 `.git` → 拒绝且一个字节都不动**；存在但不含 `.git` → 整体删掉重建 | 半成品目录会让 libgit2 回一句 `'…' exists and is not an empty directory`，用户界面上没有任何出路；含 `.git` 的目录是**别人的仓库**，引擎无权代删 |
| 失败清场 | 失败即删掉这次留下的目录 | 半个 `.git` 既不能用、又挡住下一次 clone |
| 锁文件报错 | `failed to lock file '<path>' for writing` 原样保留**完整路径**，并附**现场取证**（清掉了哪几个 `*.lock`） | ① 真机上这条被上层 `take(120)` 截成了 `…/Branchbase-An`，**正好切掉文件名**（已改成与其它写操作一致的 300 字符）；② 「真残留」与「文件系统假象」处置完全不同，清单为空就是后者的判据 |
| 取消 | `Err("clone 已取消")` | 与「真的失败」分开：UI 不该为自己按的取消弹一条红字失败 |
| 残留锁清理 | `clear_stale_locks(dir)`：只扫 `.git`、跳过 `objects/`/`modules/`/`lfs/`、只删普通文件；**只在失败路径上调用** | 操作进行中清理会把别人正在用的锁删掉 |

### 4.2 已知事故：`.git/HEAD.lock`（FUSE 子树不兼容）—— 已定位 + 已绕开

真机（2026-09-25，OnePlus PJD110 / Android 16）上 clone 稳定失败。1.0.90 把失败原因补全之后，
锁文件名终于露出来了：

```
failed to lock file '…/Android/data/com.branchbase/files/repos/SunsetRNE/Branchbase-Android/.git/HEAD.lock' for writing
```

**为什么是 HEAD**：一次 clone 会把 `HEAD` 写两次 —— `git_repository_init` 建仓库时写一次（unborn HEAD），
收尾 `git_repository_set_head` 再写一次（`clone.c` 的 `update_head_to_new_branch`）。
第二次撞上了第一次留下的 `HEAD.lock`；同一份 libgit2 代码在同一台机器上，
ext4（容器里 `/tmp`）与 `/sdcard/Download`（**同一个 FUSE、另一棵策略子树**）都能克隆成功，
只有「App 私有的外部存储目录」这棵子树必现 —— 所以根因落在**该 FUSE 子树对 git 锁文件语义的兼容性**上，
不在 libgit2 的用法上。

**为什么不能只修 clone**：git 的每一次写都是「建 `<path>.lock` → 写完 rename」。
这条路不兼容，坏掉的就不只是 clone —— commit / pull / push 迟早会坏在 `.git/index.lock`、
`.git/refs/…lock` 上。

**兼容处理（1.0.91）**：本地仓库根目录从 `getExternalFilesDir(null)/repos` 换到
**内部存储** `noBackupFilesDir/repos`（`core/LocalRepos.kt` 的 `base()`，理由与取舍都写在它的 KDoc 里），
外部存储时代的仓库在启动时**一次性搬迁**过去（`LocalRepos.migrateFromExternal`，跨文件系统时复制 + 删源，
失败保留原目录、下次启动继续）。搬迁会在日志里留一行 `[本地] [Repos]`：根目录在哪、这次搬了几个。

> **这条结论的边界**：内部存储是 `/data` 分区（ext4），git 的锁语义正常；
> 若将来有人把 `base()` 改回外部存储，这台机器上的 clone/commit 会**再次**坏在锁文件上，
> 而且**只会在真机上坏**（JVM 单测发现不了）—— `LocalReposMigrationTest` 用源码级钉子钉住了这一点。


## 5. 既定决策登记（代码里只剩裸编号的那些）

| 编号 | 内容 | 现状 |
|---|---|---|
| **D3** | 草稿隔离目录：编辑草稿落在 `files/edit/...`，与正式文件分开 | `ui/decision/CommitPrepScreens.kt:376`、`ui/repository/RepositoryFileViewer.kt:121` |
| **D10** | 「Git 化」是提交模式③（本地仓库）下的子开关 | `ui/decision/SyncDecisionScreens.kt:238` |
| **D11** | **不改写已推送历史**：不做 merge/rebase，已推送的提交只能 revert | `SyncDecisionScreens.kt:101,490`、`core/src/git/mod.rs:761`。**已拍板待实现（2026-09）**：拆开 merge 与 rebase —— merge 只新增提交、不改写历史，**允许**；仍禁 rebase / amend 已推送 / 强推。落地时改本行措辞与分叉页文案（见 [`git-mode-design.md`](git-mode-design.md) §6.4 的 D-g） |

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

1. **浅 clone 的历史拿不到**：`depth(1)` 意味着本地只有 HEAD 一条提交（`:37`）。
   出路是 `fetch_deepen`（1.0.98 落地）：全量加深后 `.git/shallow` 被删掉、历史可完整走；
   代价是它**一次全史下载**（大仓库几分钟、几百 MB），所以 UI 把它当长任务
   （任务中心 + 进度弹窗），并且默认不自动做 —— 由用户在提交图档点「加深历史」。
   注意 `pull` / `fetch` 都**不会**撤销浅边界（git 自己也要 `--unshallow`）。
2. **pull 只 fast-forward**：分叉即 `nff:` 报错，不做自动 merge（`:51-52`、`:119-120`）。
3. **脏工作区不隐式 stash**：切分支会被 libgit2 拒绝，UI 负责提示「撤销改动后再切换」（`:345-349`）。
4. **远端跟踪匹配有优先级**：先 upstream 配置、同名兜底 —— 避免「本地 `main` 跟踪 `origin/other`」被误判（`:266-268`）。
5. **`discard_all_changes` 会删未跟踪文件**：这是「撤销工作区」的完整语义，调用方必须二次确认（`:430-433`）。
6. **主分支保护只在 UI**：引擎层只拒绝删当前分支，`main`/`master` 的置灰是调用方的责任（`:413`）。
7. **进度只有 clone 与加深有**：**加深（1.0.98）已接入**同一条通道；pull / push / commit 仍然只有
   「开始 / 结束」两个状态点。进度快照的骨架（`progress.rs`）是通用的，pull 接进来只是多挂两个回调的事，
   但**这一版没做** —— 别看着 `nativeGitCloneProgress` 以为 pull 也有进度。
8. **进度是进程内快照，不跨进程**：`snapshot_json` 读的是本次进程的内存状态；
   App 被杀之后没有任何「上次拉到哪」的残留（下一次 clone / 加深从零开始）。
   **取消标记同样是进程内、且是全局一个**（`CANCEL_REQUESTED`）：同一时刻只允许一次 clone / 加深在跑，
   这条护栏在 UI 侧（`cloneActive` / 运行器自己），引擎不排队。
9. **仓库根目录在内部存储**：`noBackupFilesDir/repos`（1.0.91 起，见 §4.2）。
   它不参与云备份 / 设备迁移（与外部存储时代一致），也不再能被文件管理器翻到 ——
   代价与理由写在 `LocalRepos.base()` 的 KDoc 里。
   **文案已改（1.0.92）**：分叉决策页原先那句「请复制仓库路径到桌面端解决」不再出现 ——
   改成「远端未动、App 不做 merge/rebase、改动可逐文件查看 / 复制」（见
   [`decision-pages-design.md`](decision-pages-design.md) §4.1）。
   仍然没有「把仓库整体取走」的出路（导出 zip 之类），要的话得单独立项。

## 8. 钉子与验收

- **单测**：`core/src/git/mod.rs` 内 `mod tests` 共 **38** 个 `#[test]`、`core/src/git/progress.rs` 内 **6** 个
  （`cargo test` 会连集成测试一起跑：**90** 个单测 + 4 个 `core/tests/deepseek_http.rs`）。
  与决策页相关的是 `scan_sensitive`（5 条）、`map_push_error`（2 条）、
  `repo_status`（3 条：`dirty` 顺序、父子提交与完整 sha、远端 ref 三态含悬挂符号引用）；
  与 clone / 加深相关的是 `prepare_clone_target`（3 条）、`discard_partial_clone`（1 条）、
  `clear_stale_locks`（2 条：只删锁文件 / 没有 `.git` 时是空操作）、
  `map_clone_error`（4 条：锁文件保留完整路径 + 现场清单、其余原样、取消要能区分）、取消标记（1 条）、
  `fetch_deepen`（3 条：没有 origin 时如实报错 / 全量之后浅边界消失且历史完整 / 已全量时再跑一次无害）、
  `diff_worktree`（2 条：行首语义与逐文件统计 / 未跟踪文件带内容且两段按下标对齐），
  以及 `progress.rs` 的阶段/百分比/JSON（6 条）。
  **说清测不到什么**：libgit2 的 local transport 不支持 depth（`transports/local.c` 的
  `local_shallow_roots` 直接返回空），所以「真浅克隆」在单测里造不出来 —— 那一组是**手工写下
  `.git/shallow`** 再加深，钉的是界面依赖的性质（边界消失、全史可走）；真浅克隆只能在真机 / HTTP 上验。
- **Kotlin 侧**：`CloneProgressTest`（11 例：解析容错、阶段百分比、越界夹紧、单调性）、
  `CloneProgressDialogTest`（2 例：分母未知不编号）、`CloneErrorDiagnosticsTest`（2 例：源码级钉住
  「clone 失败原因不许再被单独截短」）、`LocalReposMigrationTest`（5 例：搬迁不丢东西、
  跨文件系统复制路径成立、源不存在不许假成功，以及**源码级钉住 `base()` 不回到外部存储**）。
- **改这块时要跑的**：`cd core && cargo test`；若是接口（签名/返回约定）改动，
  还要 `./gradlew :app:testDebugUnitTest`（`JniSignatureTest` 逐参数比对，见 [`BUILD-NOTES.md`](BUILD-NOTES.md) §四）
  并重建 `.so`。
- **没有钉子、只能靠真机的**：真实远端上的 clone/pull/push 行为（需要网络与凭据）；
  以及 §4.2 那条 FUSE 兼容结论本身 —— 它是在真机上量出来的（容器里 ext4 与
  `/sdcard/Download` 都成功、只有 App 私有外部目录失败），JVM 单测只能钉住「别再改回外部存储」。

