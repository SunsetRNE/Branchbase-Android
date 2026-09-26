//! Git 工具包：基于 libgit2（`git2` crate），提供 clone / pull（fast-forward）。
//!
//! 对齐 `docs/specs/local-git-engine-design.md` §3（稳定接口）/ §4（错误归一）。
//! HTTPS 走 vendored OpenSSL（见 Cargo.toml）。

use std::sync::atomic::Ordering;

use crate::error::{CoreError, Result};

pub mod progress;

/// 浅 clone 仓库到本地目录。
///
/// - `url`：仓库 HTTPS URL（如 `https://github.com/o/r.git`）。
/// - `into`：本地目标目录（绝对路径）。
/// - `branch`：要检出的分支（`None` = 默认分支）。
/// - `token`：可选的 PAT（私有仓库用；`None` = 匿名）。
///
/// ## 目标目录的三条规矩（每条都对应一次真机事故）
///
/// 1. **只允许落在「不存在」或空目录上**：这是 libgit2 的硬要求，撞上别的它只回
///    `'<path>' exists and is not an empty directory` —— 用户看到的是一句英文，界面上
///    也没有任何出路。所以入口先走 [`prepare_clone_target`] 收口。
/// 2. **含 `.git` 的目录绝不代删**：那是另一个仓库（或上一次成功的 clone）。
///    宁可报错交给用户决定，也不替他把可能还有未推送提交的目录删掉。
/// 3. **失败不留半成品**：失败路径上目录里可能只剩半个 `.git`（对象不全 / 没有 HEAD），
///    App 用不了，下一次 clone 又会被它挡住 → 失败即 [`discard_partial_clone`] 清场。
///
/// ## 进度
///
/// 全过程往 [`progress`] 写快照（阶段 + 计数），UI 侧轮询它画进度条。
/// 取消走 [`request_cancel`]：回调一旦看到取消标记就让 libgit2 中断传输，
/// 失败路径照常清场。
pub fn clone_repo(url: &str, into: &str, branch: Option<&str>, token: Option<&str>) -> Result<()> {
    use git2::build::{CheckoutBuilder, RepoBuilder};
    use git2::{FetchOptions, RemoteCallbacks};

    // 任何一次 clone 尝试都从「清空上一次的进度」开始 —— 包括被预检拦下的那次：
    // 否则上层会读到上一次的 done/failed 快照，进度条在弹窗刚打开时闪一下 100%。
    progress::begin();
    CANCEL_REQUESTED.store(false, Ordering::SeqCst);
    if let Err(e) = prepare_clone_target(into) {
        progress::complete(false);
        return Err(e);
    }

    let mut callbacks = RemoteCallbacks::new();
    callbacks.certificate_check(check_cert);
    // 进度 + 取消都挂在同一个回调上：libgit2 用「回调返回 false」表示中断。
    callbacks.transfer_progress(|stats| {
        if cancelled() {
            return false;
        }
        progress::transfer(
            stats.received_objects(),
            stats.total_objects(),
            stats.indexed_objects(),
            stats.received_bytes() as u64,
        );
        true
    });
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, allowed| {
            let user = username.unwrap_or("x-access-token");
            // 按 libgit2 请求的类型作答：先给 USERNAME，再给账密。
            // 旧实现忽略 allowed、无条件返回 userpass，某些 URL/服务器组合下会被
            // 判为「不支持的凭证类型」而失败（pull 能用、push 报错这类不对称现象）。
            if allowed.contains(git2::CredentialType::USERNAME) {
                return git2::Cred::username(user);
            }
            git2::Cred::userpass_plaintext(user, &tk)
        });
    } else {
        callbacks.credentials(|_url, _username, _allowed| git2::Cred::default());
    }

    let mut fo = FetchOptions::new();
    fo.remote_callbacks(callbacks).depth(1); // 浅 clone，减体积

    // 检出阶段也报进度（大仓库最后那一段「检出文件」同样会等很久）；
    // 只挂回调，策略保持 libgit2 默认（SAFE），行为与旧实现逐字一致。
    let mut checkout = CheckoutBuilder::new();
    checkout.progress(|_path, done, total| progress::checkout(done, total));

    let mut builder = RepoBuilder::new();
    builder.fetch_options(fo).with_checkout(checkout);
    if let Some(b) = branch {
        builder.branch(b);
    }

    match builder.clone(url, std::path::Path::new(into)) {
        Ok(_) => {
            progress::complete(true);
            Ok(())
        }
        Err(e) => {
            progress::complete(false);
            // 失败路径先取证再清场：`failed to lock file` 这句到底对应「真有一个残留锁文件」
            // 还是「文件系统把已经 rename 掉的锁当成还在」，只有**现场清单**能回答。
            // 清场本身会把整个目录删掉，所以取证必须在它之前。
            let stale = clear_stale_locks(into);
            discard_partial_clone(into);
            Err(map_clone_error(&e, &stale))
        }
    }
}

/// 取消标记：置位后，正在跑的 clone 会在**下一次回调**里中断。
///
/// 为什么不做「立刻掐断」：libgit2 没有取消句柄，唯一的官方中断点是回调返回值；
/// 而回调只在有数据流动时触发。所以取消是「尽快」而不是「立刻」——
/// 网络完全静默时它会等到下一次超时/回调才生效（UI 用「正在取消…」如实表达）。
static CANCEL_REQUESTED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

fn cancelled() -> bool {
    CANCEL_REQUESTED.load(Ordering::SeqCst)
}

/// 请求取消正在进行的 clone（没有 clone 在跑时是空操作）。
pub fn request_cancel() {
    CANCEL_REQUESTED.store(true, Ordering::SeqCst);
}

/// clone 失败归一：保留 libgit2 原文（诊断全靠它），并对 `failed to lock file` 这一句
/// 追加**现场取证**。
///
/// 为什么单挑这一句：字面意思（「加锁失败」）对用户零信息量，而它背后有且只有两种可能，
/// 处置完全不同：
///
/// | 可能 | 现场会看到 |
/// |---|---|
/// | 真有残留锁文件（上一次操作被系统掐掉留下的） | `clear_stale_locks` 删到了文件，清单跟在文案后面 |
/// | 文件系统层面的假象（锁文件其实已被 rename 掉，stat 却还说它存在） | 清单为空 —— 这时**重试也不会好**，得换文件系统 |
///
/// 路径原文一个字都不能省：真机日志里这一行曾经被上层截断，切掉的正好是锁文件名。
fn map_clone_error(e: &git2::Error, stale_locks: &[String]) -> CoreError {
    let msg = e.message();
    if msg.contains("failed to lock file") {
        let scene = if stale_locks.is_empty() {
            "现场没有找到锁文件（锁是文件系统层面的假象，不是残留文件）".to_string()
        } else {
            format!("现场已清掉 {} 个残留锁文件：{}", stale_locks.len(), stale_locks.join("、"))
        };
        return CoreError::Other(format!("clone 失败: {msg}｜{scene}"));
    }
    if cancelled() {
        return CoreError::Other("clone 已取消".to_string());
    }
    CoreError::Other(format!("clone 失败: {msg}"))
}

/// git 的锁文件后缀：写 `<path>` 时先建 `<path>.lock`，写完 rename 覆盖。
const LOCK_SUFFIX: &str = ".lock";

/// 删掉仓库目录里残留的 `*.lock`，返回被删掉的路径（相对 `.git`，便于日志阅读与断言）。
///
/// 调用时机只有一处：clone 失败路径上取证 + 兜底（见 [`clone_repo`]）。
/// **不要在操作进行中调用** —— 那会把另一个正在使用的锁删掉。
///
/// 只扫 `.git`，跳过 `objects/`（对象库可能有几十万个文件，且里面不会有锁）、`modules/`、`lfs/`；
/// 只删**普通文件**：万一有人把目录命名成 `x.lock`，删它会连带删掉里面的东西。
pub fn clear_stale_locks(dir: &str) -> Vec<String> {
    let git_dir = std::path::Path::new(dir).join(".git");
    let mut removed = Vec::new();
    remove_locks_under(&git_dir, &git_dir, &mut removed);
    removed.sort();
    removed
}

fn remove_locks_under(dir: &std::path::Path, base: &std::path::Path, out: &mut Vec<String>) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        let name = entry.file_name().to_string_lossy().to_string();
        let Ok(kind) = entry.file_type() else { continue };
        if kind.is_dir() {
            if matches!(name.as_str(), "objects" | "modules" | "lfs") {
                continue;
            }
            remove_locks_under(&path, base, out);
        } else if kind.is_file() && name.ends_with(LOCK_SUFFIX) {
            if std::fs::remove_file(&path).is_ok() {
                let shown = path.strip_prefix(base).unwrap_or(&path);
                out.push(shown.display().to_string());
            }
        }
    }
}

/// clone 入口的目标目录预检（见 [`clone_repo`] 的「三条规矩」）。
///
/// - 不存在 → 放行；
/// - 存在且含 `.git` → **拒绝**（不删）；
/// - 存在且不含 `.git` → 上一次留下的半成品 / 空目录，删掉重建（返回删除的顶层条目数）。
fn prepare_clone_target(into: &str) -> Result<usize> {
    let path = std::path::Path::new(into);
    if !path.exists() {
        return Ok(0);
    }
    if path.join(".git").exists() {
        return Err(CoreError::Other(format!(
            "目标目录里已经有一个仓库（{into}）。先删除本地副本，或换一个目录再拉取。"
        )));
    }
    Ok(remove_tree(path))
}

/// clone 失败后的清场：删掉这次尝试留下的目录（返回删除的顶层条目数）。
///
/// 只可能在 [`prepare_clone_target`] 放行之后调用 —— 也就是说，这里的目录要么是
/// 本次 clone 新建的，要么是预检时确认过**没有 `.git`** 的半成品，不含用户数据。
fn discard_partial_clone(into: &str) -> usize {
    let path = std::path::Path::new(into);
    if !path.exists() {
        return 0;
    }
    remove_tree(path)
}

/// 递归删除目录；返回删除前它有几个顶层条目（给日志与单测用，删除失败按 0 计）。
fn remove_tree(dir: &std::path::Path) -> usize {
    let entries = std::fs::read_dir(dir).map(|rd| rd.count()).unwrap_or(0);
    let _ = std::fs::remove_dir_all(dir);
    entries
}

/// pull：fetch origin 并 fast-forward 当前分支到远端。
pub fn pull_repo(dir: &str, token: Option<&str>) -> Result<()> {
    use git2::Repository;

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;

    // 与 `fetch_remote` 走同一条路：同一份 refspec，同一个「本地缺对象就补浅边界再试一次」
    // 的自愈（见 [fetch_origin]）—— 「拉取」与「刷新远端」死在同一个坑里，
    // 只在其中一处自愈等于让另一个入口永远修不好。
    fetch_origin(&repo, token, false)?;

    // 当前分支名
    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let branch_short = head
        .shorthand()
        .ok_or_else(|| CoreError::Other("无法获取分支名".into()))?
        .to_string();
    let head_name = head
        .name()
        .ok_or_else(|| CoreError::Other("无法获取 ref 名".into()))?
        .to_string();

    // 远端跟踪分支
    let remote_ref_name = format!("refs/remotes/origin/{branch_short}");
    let remote_ref = match repo.find_reference(&remote_ref_name) {
        Ok(r) => r,
        Err(_) => return Ok(()), // 无远端跟踪分支，跳过合并
    };
    let remote_commit = repo
        .reference_to_annotated_commit(&remote_ref)
        .map_err(|e| CoreError::Other(format!("解析远端提交失败: {e}")))?;

    let (analysis, _) = repo
        .merge_analysis(&[&remote_commit])
        .map_err(|e| CoreError::Other(format!("merge 分析失败: {e}")))?;

    if analysis.is_fast_forward() {
        let mut head_ref = repo
            .find_reference(&head_name)
            .map_err(|e| CoreError::Other(format!("读取分支 ref 失败: {e}")))?;
        head_ref
            .set_target(remote_commit.id(), "fast-forward")
            .map_err(|e| CoreError::Other(format!("fast-forward 失败: {e}")))?;
        repo.set_head(&head_name)
            .map_err(|e| CoreError::Other(format!("set_head 失败: {e}")))?;
        repo.checkout_head(Some(git2::build::CheckoutBuilder::default().force()))
            .map_err(|e| CoreError::Other(format!("checkout 失败: {e}")))?;
    } else if !analysis.is_up_to_date() {
        // 本地有未推送提交 + 远端领先 → 分叉（对齐 P0-1 分叉决策页触发条件）
        return Err(CoreError::Other("nff: 本地与远端分叉（non-fast-forward）".into()));
    }
    Ok(())
}

/// 本地 git commit：暂存所有改动 + 提交（返回 commit sha）
pub fn commit_repo(dir: &str, message: &str, author_name: &str, author_email: &str) -> Result<String> {
    use git2::{Repository, Signature};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut index = repo.index().map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    // 用 "." 而非 "*"：libgit2 的 pathspec 里 "*" 不递归子目录，
    // 会让子目录里的改动被静默漏掉（提交后工作区仍是 dirty）。
    // 再补一次 update_all 以覆盖删除/重命名（add_all 不处理删除）。
    index
        .add_all(["."].iter(), git2::IndexAddOption::DEFAULT, None)
        .map_err(|e| CoreError::Other(format!("暂存失败: {e}")))?;
    index
        .update_all(["."].iter(), None)
        .map_err(|e| CoreError::Other(format!("同步删除失败: {e}")))?;
    index.write().map_err(|e| CoreError::Other(format!("写索引失败: {e}")))?;

    let tree_id = index.write_tree().map_err(|e| CoreError::Other(format!("写树失败: {e}")))?;
    let tree = repo.find_tree(tree_id).map_err(|e| CoreError::Other(format!("找树失败: {e}")))?;
    let sig = Signature::now(author_name, author_email)
        .map_err(|e| CoreError::Other(format!("签名失败: {e}")))?;

    let parent = repo
        .head()
        .ok()
        .and_then(|h| h.target())
        .and_then(|t| repo.find_commit(t).ok());
    let parents: Vec<&git2::Commit> = parent.iter().collect();

    let id = repo
        .commit(Some("HEAD"), &sig, &sig, message, &tree, &parents)
        .map_err(|e| CoreError::Other(format!("commit 失败: {e}")))?;
    Ok(id.to_string())
}

// ── 本地分支管理（列表 / 切换 / 新建 / 删除） ──

/// 当前 HEAD 的短名（detached 或空仓库返回空串）。
fn head_branch_name(repo: &git2::Repository) -> String {
    repo.head()
        .ok()
        .and_then(|h| h.shorthand().map(|s| s.to_string()))
        .unwrap_or_default()
}

/// 本地分支列表（JSON 数组）：name / is_head / upstream / ahead / behind。
///
/// 当前分支排最前，其余按名字排序。
pub fn local_branches(dir: &str) -> Result<String> {
    use git2::{BranchType, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let head = head_branch_name(&repo);

    let mut out: Vec<serde_json::Value> = Vec::new();
    let branches = repo
        .branches(Some(BranchType::Local))
        .map_err(|e| CoreError::Other(format!("读取分支失败: {e}")))?;
    for entry in branches {
        let (branch, _) = entry.map_err(|e| CoreError::Other(format!("读取分支失败: {e}")))?;
        let name = branch.name().ok().flatten().unwrap_or("").to_string();
        if name.is_empty() {
            continue;
        }
        let is_head = name == head;
        let mut upstream = String::new();
        let mut ahead: usize = 0;
        let mut behind: usize = 0;
        if let Ok(up) = branch.upstream() {
            upstream = up.name().ok().flatten().unwrap_or("").to_string();
            if let (Some(local_oid), Some(up_oid)) = (branch.get().target(), up.get().target()) {
                if let Ok((a, b)) = repo.graph_ahead_behind(local_oid, up_oid) {
                    ahead = a;
                    behind = b;
                }
            }
        }
        out.push(json!({
            "name": name,
            "is_head": is_head,
            "upstream": upstream,
            "ahead": ahead,
            "behind": behind
        }));
    }
    out.sort_by(|a, b| {
        let ah = a.get("is_head").and_then(|v| v.as_bool()).unwrap_or(false);
        let bh = b.get("is_head").and_then(|v| v.as_bool()).unwrap_or(false);
        bh.cmp(&ah).then_with(|| {
            let an = a.get("name").and_then(|v| v.as_str()).unwrap_or("");
            let bn = b.get("name").and_then(|v| v.as_str()).unwrap_or("");
            an.cmp(bn)
        })
    });
    Ok(json!(out).to_string())
}

/// 只刷新远端跟踪引用（fetch，不合并、不动工作区）。
///
/// 本地对远端分支同步的「先看清再决定」原语：`pull_repo` 会把当前分支快进，
/// 而这里只更新 `refs/remotes/origin/*`，因此可以在不打扰工作区的前提下
/// 得到所有分支相对远端的最新 ahead/behind，再决定推送或拉取。
///
/// - `prune = true`：顺带删除远端已不存在的跟踪引用（`FetchPrune::On`）。
pub fn fetch_remote(dir: &str, token: Option<&str>, prune: bool) -> Result<()> {
    use git2::Repository;

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    fetch_origin(&repo, token, prune)
}

/// 加深克隆（unshallow / deepen）：把远端历史取到本地。
///
/// ## 为什么必须有这条，而不是「再 pull 一次」
///
/// clone 用的是 `depth(1)`（浅 clone 减体积，见 `clone_repo`），而 `pull_repo` /
/// `fetch_remote` **不设 depth** —— 在浅仓库上，一次普通 fetch 不会撤销浅边界
/// （git 自己也要显式 `--unshallow` 才做这件事）。于是本地永远只有 HEAD 一条提交，
/// 「提交图走本地来源」「文件历史本地优先」（`docs/specs/git-mode-design.md` §4.1 / §8 阶段 4）
/// 全都无从谈起。
///
/// ## 参数与语义
///
/// - `depth <= 0`：**全量**加深，等价 `git fetch --unshallow` —— 内部发 `i32::MAX`，
///   与 git 自己的做法一致（libgit2 也是拿 `INT_MAX` 当「不要浅边界」的哨兵：
///   `fetch.c` 里 `nego.depth != INT_MAX` 才跳过本地已有的对象）；
/// - `depth > 0`：把历史加深到该条数（增量取，给「再往前看一点」留的口子）。
///
/// **只动 `.git` 里的对象与远端跟踪引用**：不改工作区、不动本地分支与已有提交。
/// 因此它是一件**安全动作**（不会丢东西），但可能是长任务 —— 进度走 [`crate::git::progress`]
/// 那份快照（与 clone 同一个通道，UI 不许出现第二种「转圈」），取消走 [`request_cancel`]。
pub fn fetch_deepen(dir: &str, depth: i32, token: Option<&str>) -> Result<()> {
    use git2::Repository;

    // 与 clone 同一套进度语义：`begin` 先清上一次的结果，否则弹窗打开时会闪一下上一轮的 100%
    progress::begin();
    CANCEL_REQUESTED.store(false, Ordering::SeqCst);

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    // depth <= 0 = 全量：发 i32::MAX，与 git 自己的 `--unshallow` 一致
    let effective_depth = if depth <= 0 { i32::MAX } else { depth };

    let (mut remote, mut fo) = deepen_fetch_parts(&repo, token, effective_depth)?;
    let was_shallow = repo.is_shallow();
    let outcome = match remote.fetch(&[FETCH_HEADS_REFSPEC], Some(&mut fo), None) {
        Ok(()) => Ok(()),
        // 本地副本「声称完整、其实缺对象」时，普通 fetch 会死在协商阶段 —— 加深这条路同样
        // 会，而它偏偏是浅仓库出问题时**唯一**的出路。自愈一次再看（见 [fetch_origin]）。
        Err(e) if heal_missing_objects(&repo, &e) => {
            let (mut remote, mut fo) = deepen_fetch_parts(&repo, token, effective_depth)?;
            remote.fetch(&[FETCH_HEADS_REFSPEC], Some(&mut fo), None)
        }
        Err(e) => Err(e),
    };
    match outcome {
        Ok(()) => {
            // 加深到一半（depth > 0）时收尾也会踩那个浅边界 bug（见 [restore_shallow_after_fetch]），
            // 按同一口径复核；真全量加深后本地已完整，复核只会得出「不需要边界」，文件保持消失。
            restore_shallow_after_fetch(&repo, was_shallow);
            progress::complete(true);
            Ok(())
        }
        Err(e) => {
            // 失败也要落一个终态：不然进度条会永远停在「接收中」，而它其实已经停了
            progress::complete(false);
            // 用户按的取消不是「失败」：libgit2 用回调返回 false 中断，错误原文对用户没有意义
            if cancelled() {
                return Err(CoreError::Other("已取消".into()));
            }
            Err(CoreError::Other(format!("加深失败: {e}")))
        }
    }
}

/// [fetch_deepen] 一次尝试要用的东西：`origin` 的 remote + 回调（进度 / 取消 / 凭据）+ 深度。
///
/// 做成函数是因为它**会被调用两次**（缺对象自愈后重试一次），而 `RemoteCallbacks` 里的凭据
/// 闭包不可克隆 —— 复制粘贴一遍就等于把「凭据怎么答」这件事变成两处。
fn deepen_fetch_parts<'r>(
    repo: &'r git2::Repository,
    token: Option<&str>,
    depth: i32,
) -> Result<(git2::Remote<'r>, git2::FetchOptions<'static>)> {
    let remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;

    let mut callbacks = git2::RemoteCallbacks::new();
    callbacks.certificate_check(check_cert);
    callbacks.transfer_progress(|stats| {
        if cancelled() {
            return false;
        }
        progress::transfer(
            stats.received_objects(),
            stats.total_objects(),
            stats.indexed_objects(),
            stats.received_bytes() as u64,
        );
        true
    });
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, allowed| {
            let user = username.unwrap_or("x-access-token");
            if allowed.contains(git2::CredentialType::USERNAME) {
                return git2::Cred::username(user);
            }
            git2::Cred::userpass_plaintext(user, &tk)
        });
    }

    let mut fo = git2::FetchOptions::new();
    fo.remote_callbacks(callbacks);
    fo.depth(depth);
    Ok((remote, fo))
}

/// 远端分支清单（`refs/remotes/origin/*`），并带上对应本地分支的跟踪状态。
///
/// 输出 JSON 数组：`[{ name, local, has_local, ahead, behind }]`
/// - `name`：远端分支名（去掉 `origin/` 前缀，`origin/HEAD` 不计入）；
/// - `local`：跟踪该远端分支的本地分支名（无则空串）；
/// - `ahead` / `behind`：本地相对该远端的领先/落后提交数（无本地对应时为 0）。
///
/// 匹配优先级：先按 upstream 配置匹配；仅当本地同名分支**没有** upstream 时才按名字兜底，
/// 避免「本地 main 跟踪 origin/other」这类情况被误判成跟踪 origin/main。
pub fn remote_branches(dir: &str) -> Result<String> {
    use git2::{BranchType, Oid, Repository};
    use std::collections::{HashMap, HashSet};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;

    let mut local_oids: HashMap<String, Oid> = HashMap::new();
    let mut no_upstream: HashSet<String> = HashSet::new();
    let mut local_by_upstream: HashMap<String, String> = HashMap::new();
    if let Ok(branches) = repo.branches(Some(BranchType::Local)) {
        for (branch, _) in branches.flatten() {
            let name = branch.name().ok().flatten().unwrap_or("").to_string();
            if name.is_empty() {
                continue;
            }
            if let Some(oid) = branch.get().target() {
                local_oids.insert(name.clone(), oid);
            }
            match branch.upstream() {
                Ok(up) => {
                    if let Some(up_name) = up.name().ok().flatten() {
                        local_by_upstream.insert(up_name.to_string(), name.clone());
                    }
                }
                Err(_) => {
                    no_upstream.insert(name);
                }
            }
        }
    }

    let mut out: Vec<serde_json::Value> = Vec::new();
    let branches = repo
        .branches(Some(BranchType::Remote))
        .map_err(|e| CoreError::Other(format!("读取远端分支失败: {e}")))?;
    for entry in branches {
        let (branch, _) = entry.map_err(|e| CoreError::Other(format!("读取远端分支失败: {e}")))?;
        let full = branch.name().ok().flatten().unwrap_or("").to_string();
        let short = match full.strip_prefix("origin/") {
            Some(s) if !s.is_empty() && s != "HEAD" => s.to_string(),
            _ => continue,
        };
        let local = local_by_upstream
            .get(&full)
            .cloned()
            .or_else(|| if no_upstream.contains(&short) { Some(short.clone()) } else { None })
            .unwrap_or_default();

        let mut ahead: usize = 0;
        let mut behind: usize = 0;
        if !local.is_empty() {
            if let (Some(local_oid), Some(remote_oid)) =
                (local_oids.get(&local), branch.get().target())
            {
                if let Ok((a, b)) = repo.graph_ahead_behind(*local_oid, remote_oid) {
                    ahead = a;
                    behind = b;
                }
            }
        }
        out.push(json!({
            "name": short,
            "local": local,
            "has_local": !local.is_empty(),
            "ahead": ahead,
            "behind": behind
        }));
    }
    out.sort_by(|a, b| {
        a.get("name")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .cmp(b.get("name").and_then(|v| v.as_str()).unwrap_or(""))
    });
    Ok(json!(out).to_string())
}

/// 切换本地分支（safe checkout：**不覆盖**未提交改动）。
///
/// 若未提交改动会被目标分支覆盖，libgit2 会拒绝（错误信息含 conflict / overwritten），
/// 由上层提示「撤销改动后再切换」—— 与「脏工作区不隐式 stash」的约定一致。
pub fn checkout_branch(dir: &str, name: &str) -> Result<()> {
    use git2::{build::CheckoutBuilder, Repository};

    if name.trim().is_empty() {
        return Err(CoreError::Other("分支名不能为空".into()));
    }
    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;

    let (object, reference) = repo
        .revparse_ext(name.trim())
        .map_err(|e| CoreError::Other(format!("找不到分支 {name}: {e}")))?;

    // SAFE 模式：冲突时失败而不是静默丢改动
    repo.checkout_tree(&object, Some(CheckoutBuilder::new().safe()))
        .map_err(|e| CoreError::Other(format!("切换失败: {e}")))?;

    match reference {
        Some(r) => {
            let refname = r
                .name()
                .ok_or_else(|| CoreError::Other("分支引用名无效".into()))?
                .to_string();
            repo.set_head(&refname)
                .map_err(|e| CoreError::Other(format!("切换 HEAD 失败: {e}")))?;
        }
        None => {
            repo.set_head_detached(object.id())
                .map_err(|e| CoreError::Other(format!("切换 HEAD 失败: {e}")))?;
        }
    }
    Ok(())
}

/// 新建本地分支并**立即切换过去**（对齐「新建后默认跟随」的约定）。
pub fn create_branch_local(dir: &str, name: &str, from: &str) -> Result<()> {
    use git2::Repository;

    let name = name.trim();
    if name.is_empty() {
        return Err(CoreError::Other("分支名不能为空".into()));
    }
    if name.contains(' ') {
        return Err(CoreError::Other("分支名不能含空格".into()));
    }
    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    if repo.find_branch(name, git2::BranchType::Local).is_ok() {
        return Err(CoreError::Other(format!("分支 {name} 已存在")));
    }

    let base = if from.trim().is_empty() {
        repo.head()
            .and_then(|h| h.peel_to_commit())
            .map_err(|e| CoreError::Other(format!("读取当前提交失败: {e}")))?
    } else {
        repo.revparse_single(from.trim())
            .and_then(|o| o.peel_to_commit())
            .map_err(|e| CoreError::Other(format!("找不到起点 {from}: {e}")))?
    };

    repo.branch(name, &base, false)
        .map_err(|e| CoreError::Other(format!("创建分支失败: {e}")))?;
    checkout_branch(dir, name)
}

/// 删除本地分支。当前分支拒绝；调用方另外把 main/master 置灰。
pub fn delete_branch_local(dir: &str, name: &str) -> Result<()> {
    use git2::Repository;

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    if name == head_branch_name(&repo) {
        return Err(CoreError::Other("不能删除当前分支，请先切换到其他分支".into()));
    }
    let mut branch = repo
        .find_branch(name, git2::BranchType::Local)
        .map_err(|e| CoreError::Other(format!("找不到分支 {name}: {e}")))?;
    branch
        .delete()
        .map_err(|e| CoreError::Other(format!("删除分支失败: {e}")))?;
    Ok(())
}

/// 撤销工作区所有改动（已跟踪文件恢复 + 删除未跟踪文件）。
///
/// 用于「脏工作区切换分支」的前置步骤：用户确认后才调用，不做任何隐式丢弃。
pub fn discard_all_changes(dir: &str) -> Result<()> {
    use git2::{build::CheckoutBuilder, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut cb = CheckoutBuilder::new();
    cb.force();
    cb.remove_untracked(true);
    repo.checkout_head(Some(&mut cb))
        .map_err(|e| CoreError::Other(format!("撤销改动失败: {e}")))?;
    Ok(())
}

/// 本地 git push：推送到 origin
pub fn push_repo(dir: &str, token: Option<&str>, branch: &str) -> Result<()> {
    use git2::{PushOptions, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;

    let mut opts = PushOptions::new();
    opts.remote_callbacks(net_callbacks(token));
    let refspec = format!("refs/heads/{branch}:refs/heads/{branch}");
    remote
        .push(&[&refspec], Some(&mut opts))
        .map_err(|e| CoreError::Other(format!("push 失败: {e}")))?;
    Ok(())
}
// ── HTTPS 证书验证（Android 无 OpenSSL 兼容 CA 路径，自验证书链） ──

use std::sync::OnceLock;
use openssl::nid::Nid;
use openssl::x509::X509;

/// 内置 Mozilla CA bundle 解析（一次性，include_bytes 打包）
static CA_CERTS: OnceLock<Vec<X509>> = OnceLock::new();

fn ca_certs() -> &'static Vec<X509> {
    CA_CERTS.get_or_init(|| {
        let bundle = include_bytes!("../certs/cacert.pem");
        X509::stack_from_pem(bundle).unwrap_or_default()
    })
}

/// 通配符域名匹配（`*.github.com`）。
///
/// 要求 host 以 `.<suffix>` 结尾，且 `<suffix>` 之前恰好是一段不含点的主机名：
/// - `*.github.com` 匹配 `api.github.com`
/// - 不匹配 `a.b.github.com`（多级子域）
/// - 不匹配 `xgithub.com`（缺少点分隔，早期实现会误判为匹配）
/// - 不匹配裸 `github.com`
fn dns_matches(pattern: &str, host: &str) -> bool {
    if let Some(rest) = pattern.strip_prefix("*.") {
        let host_bytes = host.as_bytes();
        let rest_bytes = rest.as_bytes();
        if host_bytes.len() <= rest_bytes.len() + 1 || !host.ends_with(rest) {
            return false;
        }
        let dot_at = host_bytes.len() - rest_bytes.len() - 1;
        if host_bytes[dot_at] != b'.' {
            return false;
        }
        !host_bytes[..dot_at].contains(&b'.')
    } else {
        pattern == host
    }
}

/// 主机名校验：SAN（优先）→ CN 兜底
fn hostname_matches(leaf: &X509, host: &str) -> bool {
    let host = host.trim_end_matches('.').to_lowercase();
    if let Some(names) = leaf.subject_alt_names() {
        for n in names {
            if let Some(dns) = n.dnsname() {
                if dns_matches(&dns.to_lowercase(), &host) {
                    return true;
                }
            }
        }
    }
    for e in leaf.subject_name().entries_by_nid(Nid::COMMONNAME) {
        if let Ok(cn) = e.data().to_string() {
            if dns_matches(&cn.to_lowercase(), &host) {
                return true;
            }
        }
    }
    false
}

/// 用内置 bundle 验证证书链：逐级找 issuer、验签名，走到自签名根即通过
fn verify_cert_chain(leaf: &X509) -> bool {
    let certs = ca_certs();
    let mut current = leaf.clone();
    for _ in 0..8 {
        let issuer_name = current.issuer_name();
        let Some(ca) = certs.iter().find(|c| c.subject_name().try_cmp(&issuer_name).ok() == Some(std::cmp::Ordering::Equal)) else {
            return false;
        };
        let Ok(pubkey) = ca.public_key() else {
            return false;
        };
        if current.verify(&pubkey).is_err() {
            return false;
        }
        if ca.subject_name().try_cmp(ca.issuer_name()).ok() == Some(std::cmp::Ordering::Equal) {
            return true; // 自签名根，链走通
        }
        current = ca.clone();
    }
    false
}

/// libgit2 certificate_check 回调：验证通过 → Ok；否则交还 libgit2（其 openssl 后端会失败，
/// 不会静默放行不安全的连接）
fn check_cert(
    cert: &git2::cert::Cert<'_>,
    host: &str,
) -> std::result::Result<git2::CertificateCheckStatus, git2::Error> {
    let Some(x509_cert) = cert.as_x509() else {
        return Ok(git2::CertificateCheckStatus::CertificatePassthrough);
    };
    let Ok(leaf) = X509::from_der(x509_cert.data()) else {
        return Ok(git2::CertificateCheckStatus::CertificatePassthrough);
    };
    if hostname_matches(&leaf, host) && verify_cert_chain(&leaf) {
        Ok(git2::CertificateCheckStatus::CertificateOk)
    } else {
        Ok(git2::CertificateCheckStatus::CertificatePassthrough)
    }
}

// ── 决策页面支持 API（对齐 docs/specs/decision-pages-design.md §6） ──

use serde_json::json;

/// 网络回调：证书校验 + 可选 PAT。clone / pull / fetch / push 与**合并前的那次补拉**共用一份。
///
/// 抽出来是因为它此前在四处各写了一遍（`pull_repo` / `fetch_remote` / `push_repo` / clone），
/// 而其中**凭证类型**那三行是踩过坑的：旧实现忽略 `allowed`、无条件返回账密，
/// 某些服务器组合下被判成「不支持的凭证类型」——表现是「pull 能用、push 报错」这种不对称现象。
/// 一处写对、四处在用，才不会下次再有人只修好其中一处。
fn net_callbacks(token: Option<&str>) -> git2::RemoteCallbacks<'static> {
    let mut callbacks = git2::RemoteCallbacks::new();
    callbacks.certificate_check(check_cert);
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, allowed| {
            let user = username.unwrap_or("x-access-token");
            // 按 libgit2 请求的类型作答：先给 USERNAME，再给账密
            if allowed.contains(git2::CredentialType::USERNAME) {
                return git2::Cred::username(user);
            }
            git2::Cred::userpass_plaintext(user, &tk)
        });
    }
    callbacks
}

/// fetch 的 refspec：把远端的全部本地分支取到 `refs/remotes/origin/*`（不合并、不动工作区）。
/// pull / fetch_remote / 合并前补拉 / 加深共用这一份。
const FETCH_HEADS_REFSPEC: &str = "refs/heads/*:refs/remotes/origin/*";

/// fetch `origin` 的**全部本地分支**到 `refs/remotes/origin/*`（不合并、不动工作区）。
///
/// [fetch_remote]（决策页的「先看清再决定」原语）与合并前的那次补拉共用这一份 ——
/// refspec 写歪（少了 `refs/heads/*` 那半）的表现是「远端分支列表永远只有一条」，
/// 而它看着像服务端的事。
///
/// ## 失败自愈（真机事故 `object not found - no match for id (c8301a5…)`）
///
/// 失败若是「**本地缺对象**」（见 [`repair_shallow_boundary`]），补写浅边界后**重建 remote
/// 与 options 再试一次**。不做这件事的话，这台机器上「本地副本缺对象」= 「此后每一次 fetch
/// 都失败」，而用户看到的是一次网络错误 —— 他会去切网络、换 token，全都治不好。
fn fetch_origin(repo: &git2::Repository, token: Option<&str>, prune: bool) -> Result<()> {
    // libgit2 1.7.2 的浅边界 bug：**非加深** fetch 收尾会把 `<gitdir>/shallow` 删掉
    // （见 [restore_shallow_after_fetch]）。所以先记住「进这趟之前是不是浅仓库」。
    let was_shallow = repo.is_shallow();
    let (mut remote, mut fo) = origin_fetch_parts(repo, token, prune)?;
    match remote.fetch(&[FETCH_HEADS_REFSPEC], Some(&mut fo), None) {
        Ok(()) => {
            restore_shallow_after_fetch(repo, was_shallow);
            Ok(())
        }
        Err(e) => {
            if !heal_missing_objects(repo, &e) {
                return Err(CoreError::Other(format!("fetch 失败: {e}")));
            }
            // 边界补上了：**重开** remote 与 options（失败过一次的 fetch 状态不复用）
            let (mut remote, mut fo) = origin_fetch_parts(repo, token, prune)?;
            let outcome = remote.fetch(&[FETCH_HEADS_REFSPEC], Some(&mut fo), None);
            if outcome.is_ok() {
                // 这一趟的收尾会再删一次刚补上的边界，按同一条口径补回来
                let _ = repair_shallow_boundary_in(repo);
            }
            outcome.map_err(|e| CoreError::Other(format!("fetch 失败: {e}")))
        }
    }
}

/// [fetch_origin] 一次尝试要用的两件东西：`origin` 的 remote + 选项。
/// 两次尝试各建一份 —— 「找不到 origin」的文案因此只有一处。
fn origin_fetch_parts<'r>(
    repo: &'r git2::Repository,
    token: Option<&str>,
    prune: bool,
) -> Result<(git2::Remote<'r>, git2::FetchOptions<'static>)> {
    let remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;
    let mut fo = git2::FetchOptions::new();
    fo.remote_callbacks(net_callbacks(token));
    fo.prune(if prune {
        git2::FetchPrune::On
    } else {
        git2::FetchPrune::Off
    });
    Ok((remote, fo))
}

/// 这个 fetch 类错误是不是「**本地对象库缺对象**」。
///
/// libgit2 在 fetch 协商阶段从 `refs/*` 走一遍提交图来收集要发的 `have`
/// （`smart_protocol.c`：`git_revwalk__push_glob(walk, "refs/*")` 之后循环 `git_revwalk_next`），
/// 走到本地缺的提交就返回 `object not found - no match for id (<sha>)`（class=Odb、code=NotFound）。
/// 类与码**两个都认**才认，免得把别的 Not-found 也当成本地缺对象。
fn is_missing_object_error(e: &git2::Error) -> bool {
    e.class() == git2::ErrorClass::Odb && e.code() == git2::ErrorCode::NotFound
}

/// fetch（或加深）失败后的**自愈一次**：命中 [`is_missing_object_error`] 就
/// [补写浅边界](repair_shallow_boundary_in)，返回 `true` 表示调用方值得重试一次。
///
/// 只重试一次是有意的：边界是「扫出来再写回去」的，扫与写之间没有原子性可言，多试几轮也不会
/// 更好（真要更深处才缺，下一次 fetch 会再走一遍这条路）。修不动就照原样报错 ——
/// 把网络错误伪装成成功，比报错更坏。
fn heal_missing_objects(repo: &git2::Repository, e: &git2::Error) -> bool {
    if !is_missing_object_error(e) {
        return false;
    }
    matches!(repair_shallow_boundary_in(repo), Ok(n) if n > 0)
}

/// fetch 成功后的**收尾复核**：确保「事实浅」的仓库仍然自称浅。
///
/// ## 这是本事故的根因（libgit2 1.7.2）
///
/// `transports/smart_protocol.c:379 setup_shallow_roots` 用 `git_array_init_to_size` + `memcpy`
/// 拼 `t->shallow_roots`，而那个宏（`src/util/array.h:33`）只做 `size = 0` 再分配 —— **size
/// 从头到尾没被写上**。于是**非加深** fetch 收尾时 `git_fetch_download_pack`（`fetch.c:210`）
/// 拿到的 roots 恒为空数组，`git_repository__shallow_roots_write` 见 `count == 0` 就
/// `remove(<gitdir>/shallow)`（`repository.c:3742`）。
///
/// 后果是一条**死循环**（真机日志逐行对得上）：浅 clone 刷新一次远端就「自称完整」→
/// 界面改用本地来源（提交图读不出来退回 REST、本地 diff 全失败）→ 下一次 fetch 协商走
/// `refs/*` 撞上缺父提交，整次 fetch 死在 `object not found - no match for id (…)`，
/// 且**永不自愈**。
///
/// 加深路径不受这个 bug 影响：`depth > 0` 时服务端的 `shallow` / `unshallow` 包走
/// `git_oidarray__add` / `__remove`，会把 size 正确维护起来。
///
/// 复核口径与 [repair_shallow_boundary_in] 完全一样（只补不删）：真全量加深后本地已经完整，
/// BFS 扫不出「缺父」的提交，因此什么都不会写 —— 文件该消失就消失。
fn restore_shallow_after_fetch(repo: &git2::Repository, was_shallow: bool) {
    if was_shallow && !repo.is_shallow() {
        let _ = repair_shallow_boundary_in(repo);
    }
}

/// 单次修复最多扫多少条提交（「父在不在本地」要一条条 `odb.exists`，是 O(本地历史) 的活）。
/// 超过就带着已扫到的边界返回：宁可少补几条（下次 fetch 再补），也不能把界面卡死。
const MAX_SHALLOW_SCAN: usize = 200_000;

/// 把「**本地缺了父提交**」的提交补写进 `.git/shallow`（只增边界），返回**新增**条数。
///
/// ## 为什么需要它（真机事故）
///
/// 报错原文：`刷新远端失败：未知错误: fetch 失败:object not found - no match for id (c8301a5…)`。
///
/// 直接原因是 [restore_shallow_after_fetch] 里记的那个 libgit2 1.7.2 bug：**浅 clone 只要刷新
/// 一次远端，`.git/shallow` 就会被删掉**，本地副本从此「声称完整、其实缺对象」。
///
/// `.git/shallow` 是「这条提交的父不在本地」的**唯一**声明：libgit2 解析提交时按它截断父列表
/// （`commit.c` 查 `repo->shallow_grafts`），fetch 协商的那趟 revwalk 也靠它才能在边界上安全
/// 停下。文件一旦丢失（刷新远端之后、加深/拉取中途被打断、仓库被搬移、单纯没写进去），
/// 本地副本就成了「**声称完整、其实缺对象**」：`Repository::is_shallow()` 是 false，界面照走
/// 本地来源，提交图 / 文件历史 / 本地 diff 全读不出来；更麻烦的是**之后每一次 fetch 都在同一个
/// 对象上死掉**，自己再也好不了 —— 本函数就是这条死路的出口。
///
/// ## 它做什么、不做什么
///
/// 从所有 ref 指向的提交（外加 HEAD）出发 BFS：**父不在对象库里的提交**就是浅边界。
/// 只把新边界并进 `.git/shallow`（原子写：`shallow.lock` → rename，与 git 自己的写法一致）；
/// **不动 refs、不动对象、不动工作区、不删任何东西** —— 最坏情况只是把仓库的「自述」改成实话。
///
/// 写回后 libgit2 自己会重读 grafts（`grafts.c` 的 `git_grafts_refresh` 按文件 mtime+size
/// 判断有没有变），所以**同一个进程里**紧接着重试的 fetch 就能用上新边界，不必重启。
///
/// 返回 0 = 不用修（要么本来就一致，要么已经有边界）。
/// 已有 `Repository` 的调用点用 [repair_shallow_boundary_in]，不必再按路径开一次。
pub fn repair_shallow_boundary(dir: &str) -> Result<usize> {
    use git2::Repository;

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    repair_shallow_boundary_in(&repo)
}

/// [repair_shallow_boundary] 的本体（已经有 `Repository` 的调用点走这条）。
fn repair_shallow_boundary_in(repo: &git2::Repository) -> Result<usize> {
    use std::collections::HashSet;

    let odb = repo
        .odb()
        .map_err(|e| CoreError::Other(format!("打开对象库失败: {e}")))?;

    let declared = read_shallow_roots(repo);
    let mut boundary: HashSet<git2::Oid> = HashSet::new();
    let mut seen: HashSet<git2::Oid> = HashSet::new();
    let mut stack: Vec<git2::Oid> = Vec::new();

    // 起点必须覆盖 fetch 协商要走的那些 ref（`refs/*`）：出事的形状正是
    // 「引用在、对象不在」（远端分支引用有 3 条，对象库里却缺了它们的祖先）
    if let Ok(references) = repo.references() {
        for reference in references.flatten() {
            if let Ok(commit) = reference.peel_to_commit() {
                stack.push(commit.id());
            }
        }
    }
    if let Ok(head) = repo.head() {
        if let Ok(commit) = head.peel_to_commit() {
            stack.push(commit.id());
        }
    }

    while let Some(oid) = stack.pop() {
        if seen.len() >= MAX_SHALLOW_SCAN {
            break;
        }
        if !seen.insert(oid) {
            continue;
        }
        // 连自己都不在本地：这不是「缺父」（例如 ref 指向的对象被清掉了），补边界救不了
        let Some(parents) = raw_parent_ids(&odb, oid) else {
            continue;
        };
        for parent in parents {
            if odb.exists(parent) {
                if !seen.contains(&parent) {
                    stack.push(parent);
                }
            } else {
                boundary.insert(oid);
            }
        }
    }

    let added = boundary
        .iter()
        .filter(|oid| !declared.contains(oid))
        .count();
    if added == 0 {
        return Ok(0);
    }
    boundary.extend(declared);
    write_shallow_roots(repo, &boundary)?;
    Ok(added)
}

/// 读提交对象里**原始的**父列表（`parent <hex>` 行，读到空行为止）。对象不在本地返回 `None`。
///
/// 不能用 `Commit::parent_ids()`：libgit2 会按 `.git/shallow`（grafts）把**浅边界提交的父
/// 截断成空** —— 而这里要判的恰恰是「父在不在对象库里」。更麻烦的是 grafts 是**进程内缓存**的
/// （`commit.c` 只查 `repo->shallow_grafts`，只有 `git_repository__shallow_roots` 会按文件
/// mtime/size 刷新）：fetch 刚把 `<gitdir>/shallow` 删掉的那一刻，缓存里还留着旧边界，
/// 于是那条刚被抹掉的提交会被当成「没有父」，扫描直接漏掉真正的边界（真机上表现为
/// 「补了 0 条」——仓库继续自称完整）。
fn raw_parent_ids(odb: &git2::Odb<'_>, oid: git2::Oid) -> Option<Vec<git2::Oid>> {
    let object = odb.read(oid).ok()?;
    let mut parents = Vec::new();
    for line in object.data().split(|byte| *byte == b'\n') {
        if line.is_empty() {
            break;
        }
        if let Some(hex) = line.strip_prefix(b"parent ") {
            if let Ok(text) = std::str::from_utf8(hex) {
                if let Ok(parent) = git2::Oid::from_str(text.trim()) {
                    parents.push(parent);
                }
            }
        }
    }
    Some(parents)
}

/// 读 `.git/shallow` 里已声明的浅边界。**文件不在 = 空集**，正是出事的那个状态。
fn read_shallow_roots(repo: &git2::Repository) -> std::collections::HashSet<git2::Oid> {
    let mut out = std::collections::HashSet::new();
    let Ok(text) = std::fs::read_to_string(repo.path().join("shallow")) else {
        return out;
    };
    for line in text.lines() {
        if let Ok(oid) = git2::Oid::from_str(line.trim()) {
            out.insert(oid);
        }
    }
    out
}

/// 原子写 `.git/shallow`（`shallow.lock` → rename）。
/// 半截的 `shallow` 比没有 `shallow` 更坏：它会让边界看起来「就是这些」。
fn write_shallow_roots(
    repo: &git2::Repository,
    roots: &std::collections::HashSet<git2::Oid>,
) -> Result<()> {
    let path = repo.path().join("shallow");
    let mut oids: Vec<git2::Oid> = roots.iter().copied().collect();
    oids.sort();
    let mut text = String::with_capacity(oids.len() * 41);
    for oid in &oids {
        text.push_str(&oid.to_string());
        text.push('\n');
    }
    let lock = path.with_file_name("shallow.lock");
    std::fs::write(&lock, text.as_bytes())
        .map_err(|e| CoreError::Other(format!("写入浅边界失败: {e}")))?;
    std::fs::rename(&lock, &path).map_err(|e| {
        let _ = std::fs::remove_file(&lock);
        CoreError::Other(format!("提交浅边界失败: {e}"))
    })?;
    Ok(())
}

/// 仓库状态（JSON）：branch / ahead / behind / has_upstream / remote_url / dirty / unpushed
/// **+ has_parent / head_sha / has_remote_ref**。
/// 供分叉决策、Git 化回退、删除升级警告、撤销误提交等决策页面读取事实区。
///
/// 后三个字段是**只增**的「前提事实」，用途都是让 UI 在动作必然失败之前先说明原因，
/// 而不是等执行层报错再显示成「引擎不可用」：
/// - `has_parent`：[reset_soft] 走 `HEAD~1`，HEAD 是**第一个提交**时 `parent_id(0)` 必失败；
/// - `head_sha`：完整 sha，用于与远端 ref sha 比对（判断「远端有没有变化」）；
/// - `has_remote_ref`：[reset_hard_to_remote] 要求 `refs/remotes/origin/{branch}` 存在，
///   否则报「找不到 ref」。
pub fn repo_status(dir: &str) -> Result<String> {
    use git2::{Repository, StatusOptions};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;

    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let branch = head.shorthand().unwrap_or("").to_string();
    let local_commit = head
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 提交失败: {e}")))?;

    // 上游与领先/落后计数
    let mut ahead: usize = 0;
    let mut behind: usize = 0;
    let mut has_upstream = false;
    let mut remote_oid: Option<git2::Oid> = None;
    if let Ok(local_branch) = repo.find_branch(&branch, git2::BranchType::Local) {
        if let Ok(upstream) = local_branch.upstream() {
            has_upstream = true;
            if let (Some(local_oid), Some(up_oid)) = (local_commit.id().into(), upstream.get().target()) {
                let (a, b) = repo
                    .graph_ahead_behind(local_oid, up_oid)
                    .map_err(|e| CoreError::Other(format!("计算领先落后失败: {e}")))?;
                ahead = a;
                behind = b;
                remote_oid = Some(up_oid);
            }
        }
    }

    // 新增前提事实：HEAD 是否有父提交（撤销上一次提交 reset_soft 的前提）。
    // 用 parent_count() 而不是 parent_id(0).is_ok()：多父合并提交同样算「有父」。
    let has_parent = local_commit.parent_count() > 0;

    // 新增前提事实：HEAD 提交的**完整** sha（无 HEAD 时上面已经 Err 了）。
    // 用完整 sha 而非 unpushed 里的 7 位短 sha：与远端 ref sha 比对时不能有歧义。
    let head_sha = local_commit.id().to_string();

    // 新增前提事实：refs/remotes/origin/{branch} 是否存在（reset_hard_to_remote 的前提）。
    // 分支为空（detached HEAD）时该名字没有意义，直接 false。
    // 条件必须与 reset_hard_to_remote 的**真实前提完全一致**：先 find_reference 再取 target()。
    // 只判「ref 存在」是不够的 —— 符号引用（如 origin/HEAD 那种）存在但取不到 target，
    // `target()` 那步照样报「远端引用无目标」，预检就会从「提前拦住」退化成「点了才报错」。
    let has_remote_ref = !branch.is_empty()
        && repo
            .find_reference(&format!("refs/remotes/origin/{branch}"))
            .ok()
            .and_then(|r| r.target())
            .is_some();

    // 远端 URL
    let remote_url = repo
        .find_remote("origin")
        .ok()
        .and_then(|r| r.url().map(|s| s.to_string()))
        .unwrap_or_default();

    // dirty 文件清单
    let mut dirty = Vec::new();
    let mut opts = StatusOptions::new();
    opts.include_untracked(true);
    let statuses = repo
        .statuses(Some(&mut opts))
        .map_err(|e| CoreError::Other(format!("读取工作区状态失败: {e}")))?;
    for entry in statuses.iter() {
        let s = entry.status();
        let st = if s.is_index_new() || s.is_wt_new() {
            "A"
        } else if s.is_index_deleted() || s.is_wt_deleted() {
            "D"
        } else {
            "M"
        };
        if let Some(p) = entry.path() {
            dirty.push(json!({ "path": p, "status": st }));
        }
    }

    // 未推送提交清单（HEAD..上游，最多 50 条）
    let mut unpushed = Vec::new();
    if let Some(up_oid) = remote_oid {
        let mut revwalk = repo
            .revwalk()
            .map_err(|e| CoreError::Other(format!("创建 revwalk 失败: {e}")))?;
        revwalk.push_head().map_err(|e| CoreError::Other(format!("revwalk push_head 失败: {e}")))?;
        revwalk.hide(up_oid).map_err(|e| CoreError::Other(format!("revwalk hide 失败: {e}")))?;
        for oid in revwalk.take(50) {
            let oid = oid.map_err(|e| CoreError::Other(format!("revwalk 迭代失败: {e}")))?;
            if let Ok(c) = repo.find_commit(oid) {
                unpushed.push(json!({
                    "sha": &c.id().to_string()[..7],
                    "message": c.summary().unwrap_or("")
                }));
            }
        }
    }

    Ok(json!({
        "branch": branch,
        "ahead": ahead,
        "behind": behind,
        "has_upstream": has_upstream,
        "remote_url": remote_url,
        "dirty": dirty,
        "unpushed": unpushed,
        // 只增字段（老键名与类型保持不变，Kotlin 侧 parseGitStatus 缺省即退化）
        "has_parent": has_parent,
        "head_sha": head_sha,
        "has_remote_ref": has_remote_ref,
        // 合并中（MERGE_HEAD 在）：面板据此给「继续 / 放弃」而不是让用户看着一堆冲突标记
        // 猜发生了什么（`git-mode-design.md` §6.4 风险 ①）。细节走 `merge_state`
        "merging": repo.state() == git2::RepositoryState::Merge
    })
    .to_string())
}

/// 撤销最近一次提交但保留改动（reset --soft HEAD~1）。
pub fn reset_soft(dir: &str) -> Result<()> {
    use git2::{Repository, ResetType};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let parent_oid = repo
        .head()
        .map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 失败: {e}")))?
        .parent_id(0)
        .map_err(|e| CoreError::Other(format!("没有父提交可撤销: {e}")))?;
    let obj = repo
        .find_object(parent_oid, None)
        .map_err(|e| CoreError::Other(format!("找对象失败: {e}")))?;
    repo.reset(&obj, ResetType::Soft, None)
        .map_err(|e| CoreError::Other(format!("reset --soft 失败: {e}")))?;
    Ok(())
}

/// 放弃本地提交：reset --hard origin/{branch}（危险，需 UI 二次确认）。
pub fn reset_hard_to_remote(dir: &str, branch: &str) -> Result<()> {
    use git2::{Repository, ResetType};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let remote_ref_name = format!("refs/remotes/origin/{branch}");
    let remote_ref = repo
        .find_reference(&remote_ref_name)
        .map_err(|e| CoreError::Other(format!("找不到 {remote_ref_name}: {e}")))?;
    let oid = remote_ref
        .target()
        .ok_or_else(|| CoreError::Other("远端引用无目标".into()))?;
    let obj = repo
        .find_object(oid, None)
        .map_err(|e| CoreError::Other(format!("找对象失败: {e}")))?;
    repo.reset(&obj, ResetType::Hard, None)
        .map_err(|e| CoreError::Other(format!("reset --hard 失败: {e}")))?;
    Ok(())
}

/// 修改最近一次提交信息（amend，未推送提交专用）。
pub fn amend_message(dir: &str, new_message: &str) -> Result<()> {
    use git2::{Repository, Signature};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let commit = head
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 失败: {e}")))?;
    let tree = commit.tree().map_err(|e| CoreError::Other(format!("读取树失败: {e}")))?;
    let parents: Vec<git2::Commit> = commit.parents().collect();
    let parent_refs: Vec<&git2::Commit> = parents.iter().collect();

    let author = commit.author();
    let committer = commit.committer();
    let sig_author = Signature::new(
        author.name().unwrap_or(""),
        author.email().unwrap_or(""),
        &author.when(),
    )
    .map_err(|e| CoreError::Other(format!("构造作者签名失败: {e}")))?;
    let sig_committer = Signature::new(
        committer.name().unwrap_or(""),
        committer.email().unwrap_or(""),
        &committer.when(),
    )
    .map_err(|e| CoreError::Other(format!("构造提交者签名失败: {e}")))?;

    let head_name = head
        .name()
        .ok_or_else(|| CoreError::Other("无法获取 ref 名".into()))?
        .to_string();
    repo.commit(
        Some(&head_name),
        &sig_author,
        &sig_committer,
        new_message,
        &tree,
        &parent_refs,
    )
    .map_err(|e| CoreError::Other(format!("amend 失败: {e}")))?;
    Ok(())
}

/// 对已推送提交创建 revert 提交（不改写历史，对齐 D11 边界）。
/// 返回新提交 sha。
pub fn revert_commit(
    dir: &str,
    sha: &str,
    message: &str,
    author_name: &str,
    author_email: &str,
) -> Result<String> {
    use git2::{Repository, Signature};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let obj = repo
        .revparse_single(sha)
        .map_err(|e| CoreError::Other(format!("解析提交 {sha} 失败: {e}")))?;
    let commit = obj
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("目标不是提交: {e}")))?;

    // 工作区必须干净才能 revert
    let mut st_opts = git2::StatusOptions::new();
    st_opts.include_untracked(false);
    let statuses = repo
        .statuses(Some(&mut st_opts))
        .map_err(|e| CoreError::Other(format!("读取工作区状态失败: {e}")))?;
    if !statuses.is_empty() {
        return Err(CoreError::Other("工作区有未提交改动，无法 revert".into()));
    }

    // git2 0.18：revert 直接应用到工作区与索引，返回 ()；随后从索引取树
    repo.revert(&commit, None)
        .map_err(|e| CoreError::Other(format!("revert 失败: {e}")))?;
    let mut idx = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    let tree_id = idx
        .write_tree()
        .map_err(|e| CoreError::Other(format!("写树失败: {e}")))?;
    let tree = repo.find_tree(tree_id).map_err(|e| CoreError::Other(format!("找树失败: {e}")))?;
    let sig = Signature::now(author_name, author_email)
        .map_err(|e| CoreError::Other(format!("签名失败: {e}")))?;
    let parent = repo
        .head()
        .ok()
        .and_then(|h| h.target())
        .and_then(|t| repo.find_commit(t).ok());
    let parents: Vec<&git2::Commit> = parent.iter().collect();
    let id = repo
        .commit(Some("HEAD"), &sig, &sig, message, &tree, &parents)
        .map_err(|e| CoreError::Other(format!("revert 提交失败: {e}")))?;
    Ok(id.to_string())
}

/// 首次 push：确保 origin 远端 + 推送 + 设置上游分支（对齐 P2-2）。
pub fn push_set_upstream(
    dir: &str,
    remote_url: &str,
    branch: &str,
    token: Option<&str>,
) -> Result<()> {
    use git2::{BranchType, PushOptions, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = match repo.find_remote("origin") {
        Ok(r) => r,
        Err(_) => repo
            .remote("origin", remote_url)
            .map_err(|e| CoreError::Other(format!("添加 origin 失败: {e}")))?,
    };
    if remote.url() != Some(remote_url) {
        repo.remote_set_url("origin", remote_url)
            .map_err(|e| CoreError::Other(format!("更新 origin URL 失败: {e}")))?;
    }

    let mut callbacks = RemoteCallbacks::new();
    callbacks.certificate_check(check_cert);
    if let Some(tk) = token {
        let tk = tk.to_string();
        callbacks.credentials(move |_url, username, allowed| {
            let user = username.unwrap_or("x-access-token");
            // 按 libgit2 请求的类型作答：先给 USERNAME，再给账密。
            // 旧实现忽略 allowed、无条件返回 userpass，某些 URL/服务器组合下会被
            // 判为「不支持的凭证类型」而失败（pull 能用、push 报错这类不对称现象）。
            if allowed.contains(git2::CredentialType::USERNAME) {
                return git2::Cred::username(user);
            }
            git2::Cred::userpass_plaintext(user, &tk)
        });
    }

    let mut opts = PushOptions::new();
    opts.remote_callbacks(callbacks);
    let refspec = format!("refs/heads/{branch}:refs/heads/{branch}");
    remote
        .push(&[&refspec], Some(&mut opts))
        .map_err(|e| map_push_error(&e.to_string()))?;

    let mut local_branch = repo
        .find_branch(branch, BranchType::Local)
        .map_err(|e| CoreError::Other(format!("找不到本地分支 {branch}: {e}")))?;
    local_branch
        .set_upstream(Some(&format!("origin/{branch}")))
        .map_err(|e| CoreError::Other(format!("设置上游失败: {e}")))?;
    Ok(())
}

/// 敏感信息本地扫描（提交前警告，对齐 P0-4）。
/// 返回 JSON 数组：[{line, kind, mask}]；完整值仅取前 4 字符打码，绝不上传。
pub fn scan_sensitive(text: &str) -> Result<String> {
    let mut hits = Vec::new();

    // 逐行扫描（行号从 1 开始）
    for (idx, line) in text.lines().enumerate() {
        let line_no = idx + 1;
        let mut detect = |prefix: &str, kind: &str, min_len: usize| {
            if let Some(pos) = line.find(prefix) {
                let rest = &line[pos + prefix.len()..];
                let token: String = rest
                    .chars()
                    .take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
                    .collect();
                if token.len() >= min_len {
                    let mask = format!("{}****", &token.chars().take(4).collect::<String>());
                    hits.push(json!({ "line": line_no, "kind": kind, "mask": mask }));
                }
            }
        };
        detect("ghp_", "GitHub PAT", 20);
        detect("gho_", "GitHub OAuth", 20);
        detect("ghu_", "GitHub User Token", 20);
        detect("ghs_", "GitHub Server Token", 20);
        detect("ghr_", "GitHub Refresh Token", 20);
        detect("AKIA", "AWS Access Key", 16);
        detect("xoxb-", "Slack Bot Token", 10);
        detect("xoxp-", "Slack User Token", 10);
        detect("sk-", "API Key", 20);

        // 私钥块起始
        if line.starts_with("-----BEGIN") && line.contains("PRIVATE KEY") {
            hits.push(json!({ "line": line_no, "kind": "私钥", "mask": "-----BEGIN****" }));
        }

        // key=value 通用模式（api_key / secret / password / token = ...）
        let lower = line.to_lowercase();
        for kw in ["api_key", "apikey", "secret", "password", "passwd", "token"] {
            if lower.contains(kw) {
                if let Some(eq) = line.find('=') {
                    let val = line[eq + 1..].trim().trim_matches('"').trim_matches('\'');
                    if val.len() >= 8 && !val.contains(' ') {
                        let mask = format!("{}****", &val.chars().take(4).collect::<String>());
                        hits.push(json!({ "line": line_no, "kind": format!("疑似{kw}"), "mask": mask }));
                    }
                }
            }
        }
    }

    Ok(serde_json::to_string(&hits).map_err(|e| CoreError::Json(e))?)
}

/// 初始化 TLS 证书信任：把内置 Mozilla CA bundle 写入 {dir}/branchbase-cacert.pem，
/// 并注入 libgit2（openssl 后端）。Android 系统无 OpenSSL 兼容的 CA 路径，必须自备。
/// 全局生效一次，供 clone/pull/push 所有 HTTPS 连接使用。
pub fn init_ssl_certs(dir: &str) -> Result<()> {
    use std::io::Write;

    let bundle: &[u8] = include_bytes!("../certs/cacert.pem");
    let path = std::path::Path::new(dir).join("branchbase-cacert.pem");
    // 内容一致则跳过写入（幂等）
    let need_write = match std::fs::read(&path) {
        Ok(existing) => existing != bundle,
        Err(_) => true,
    };
    if need_write {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| CoreError::Other(format!("创建证书目录失败: {e}")))?;
        }
        let mut f = std::fs::File::create(&path)
            .map_err(|e| CoreError::Other(format!("写证书文件失败: {e}")))?;
        f.write_all(bundle)
            .map_err(|e| CoreError::Other(format!("写证书失败: {e}")))?;
    }

    // 不直接调用 libgit2 API（冷启动期 libgit2/OpenSSL 未初始化，git_libgit2_opts
    // 会空指针崩溃）。改用 libgit2 官方配置键 http.sslCAInfo：
    //   1. 写全局 gitconfig 文件（[http] sslCAInfo = bundle 路径）
    //   2. 设 GIT_CONFIG_GLOBAL 环境变量指向它（libgit2 读 global config 时遵循）
    // 证书在首次 clone/pull/push 建立 HTTPS 连接时由已初始化的 OpenSSL 加载。
    let cfg_path = std::path::Path::new(dir).join("branchbase-gitconfig");
    // 保留用户已设置的代理行（App 每次启动都会重写本文件）
    let mut extra = String::new();
    if let Ok(existing) = std::fs::read_to_string(&cfg_path) {
        for line in existing.lines() {
            if line.trim_start().starts_with("proxy") {
                extra.push_str(line);
                extra.push('\n');
            }
        }
    }
    let cfg_content = format!("[http]\n\tsslCAInfo = {}\n{}", path.display(), extra);
    let need_write_cfg = match std::fs::read_to_string(&cfg_path) {
        Ok(existing) => existing != cfg_content,
        Err(_) => true,
    };
    if need_write_cfg {
        std::fs::write(&cfg_path, cfg_content)
            .map_err(|e| CoreError::Other(format!("写 gitconfig 失败: {e}")))?;
    }
    std::env::set_var("GIT_CONFIG_GLOBAL", &cfg_path);
    // 双保险：OpenSSL 默认验证路径也会读 SSL_CERT_FILE（若 libgit2 走 openssl 默认路径）
    std::env::set_var("SSL_CERT_FILE", &path);
    Ok(())
}

/// 设置/清除 libgit2 的 HTTP 代理（写全局 gitconfig 的 [http] proxy，重启后由
/// init_ssl_certs 保留）。`proxy` 为空表示清除。
/// 形如 `http://127.0.0.1:7890` / `socks5://127.0.0.1:1080`。
pub fn set_git_proxy(dir: &str, proxy: &str) -> Result<()> {
    let cfg_path = std::path::Path::new(dir).join("branchbase-gitconfig");
    let mut lines: Vec<String> = match std::fs::read_to_string(&cfg_path) {
        Ok(existing) => existing
            .lines()
            .filter(|l| !l.trim_start().starts_with("proxy"))
            .map(|l| l.to_string())
            .collect(),
        Err(_) => vec!["[http]".to_string()],
    };
    if lines.is_empty() {
        lines.push("[http]".to_string());
    }
    if !proxy.trim().is_empty() {
        lines.push(format!("\tproxy = {}", proxy.trim()));
    }
    std::fs::write(&cfg_path, lines.join("\n") + "\n")
        .map_err(|e| CoreError::Other(format!("写代理配置失败: {e}")))?;
    std::env::set_var("GIT_CONFIG_GLOBAL", &cfg_path);
    Ok(())
}

/// 推送错误归一：被拒（非快进/锁 ref/rejected）→ `nff:` 前缀，供上层触发分叉决策页；
/// 其余原样透出为 `push 失败:`。
fn map_push_error(msg: &str) -> CoreError {
    if msg.contains("non-fast-forward") || msg.contains("cannot lock ref") || msg.contains("rejected") {
        CoreError::Other(format!("nff: 推送被拒（远端领先）: {msg}"))
    } else {
        CoreError::Other(format!("push 失败: {msg}"))
    }
}

// ── 本地合并与冲突解决（阶段 5：D-g 允许 merge / D-h 冲突流程） ──
//
// 这一组里**只有 `analyze_conflicts` / `merge_state` 是只读的**，其余都动仓库 ——
// 但全部遵守 D-g 的边界：**只新增提交，不改写历史**（rebase / amend 已推送 / 强推仍禁）。
//
// 冲突**不是错误**：`merge_branch` 把「有冲突」如实报成 `outcome = "conflict"`，
// 仓库停在合并中（MERGE_HEAD 在），由上层引导「逐个解决 → 提交合并 / 放弃合并」。
// 把它做成 `Err` 的话，上层只剩一句「失败」，而这时仓库**确实**处在合并中 ——
// 用户要的信息是「哪几个文件、现在能做什么」，不是「失败了」。
//
// 前置条件三条（都在 `merge_branch` 里挡住，且都给出路）：
// ① **浅克隆不给合并**：没有共同祖先，libgit2 只会回一句英文（风险 ③）；
// ② **已经在合并中不给叠加**：MERGE_HEAD 被覆盖后，「放弃合并」会回到错的地方；
// ③ **工作区必须干净**：合并会把对方的内容写进工作区，而「放弃合并」是一次 hard reset ——
//    脏工作区下那一下会连用户自己的改动一起抹掉，正是 D11 不许发生的事。

/// 合并 `branch` 到当前分支（三方合并；D-g：允许 merge，不改写已有提交）。
///
/// 输出 JSON：`{ outcome, branch, head_sha, message, conflicts: [path…] }`
///
/// - `outcome` 四态：`up_to_date`（已经包含对方）/ `fast_forward`（只有一个方向有提交，
///   直接快进，**不产生合并提交**）/ `merged`（干净合并，落了**两父**合并提交）/
///   `conflict`（有冲突，仓库停在合并中，`conflicts` 是还没解决的文件清单）；
/// - `head_sha`：合并后的 HEAD；`conflict` 时是**合并前**的 HEAD（此时还没提交）；
/// - `message`：本次（或待提交的）合并信息，默认 `Merge branch 'x' into y`。
///   干净合并用它；有冲突时它会写进 `.git/MERGE_MSG`，[merge_continue] 没给信息时也用它；
/// - `token`：只在「目标分支本地没有」时才用到 —— 那时先 `fetch origin` 再找（见下）。
///
/// ## 为什么需要 `author_name` / `author_email`
///
/// 与 [`commit_repo`] 同一条理由：干净合并**当场落一个提交**，而 App 的身份来自账号，
/// 不是 `.gitconfig`（引擎从不写全局身份）。签名缺失时 libgit2 只会报
/// 「config value 'user.name' was not found」——那是用户看不懂的一句英文。
pub fn merge_branch(
    dir: &str,
    branch: &str,
    token: Option<&str>,
    author_name: &str,
    author_email: &str,
) -> Result<String> {
    use git2::{Repository, RepositoryState};

    let branch = branch.trim();
    if branch.is_empty() {
        return Err(CoreError::Other("合并需要一个分支名".into()));
    }
    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;

    if repo.is_shallow() {
        return Err(CoreError::Other(
            "本地是浅克隆，没有共同祖先，合并不了。先「加深历史」把完整历史拉到本地。".into(),
        ));
    }
    if repo.state() == RepositoryState::Merge {
        return Err(CoreError::Other(
            "上一次合并还没结束：先解决冲突并「提交合并」，或者「放弃合并」。".into(),
        ));
    }
    if worktree_dirty(&repo)? {
        return Err(CoreError::Other("工作区有未提交改动，无法合并".into()));
    }

    let head_commit = repo
        .head()
        .map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 提交失败: {e}")))?;
    let into = {
        let name = head_branch_name(&repo);
        if name.is_empty() {
            "HEAD".to_string()
        } else {
            name
        }
    };
    let message = format!("Merge branch '{branch}' into {into}");

    let target = resolve_merge_target(&repo, branch, token)?;
    let (analysis, _) = repo
        .merge_analysis(&[&target])
        .map_err(|e| CoreError::Other(format!("merge 分析失败: {e}")))?;

    if analysis.is_up_to_date() {
        return Ok(json!({
            "outcome": "up_to_date",
            "branch": branch,
            "head_sha": head_commit.id().to_string(),
            "message": "",
            "conflicts": [],
        })
        .to_string());
    }

    // ── 快进：直接把当前分支指到对方（不产生提交，历史一字不改） ──
    if analysis.is_fast_forward() {
        let target_commit = repo
            .find_commit(target.id())
            .map_err(|e| CoreError::Other(format!("找不到目标提交: {e}")))?;
        // **先检出、再改 ref**（与 git 同序）：检出用的是默认的 safe 语义 ——
        // 工作区有东西会被覆盖时它报错退出，而不是把用户的内容盖掉
        repo.checkout_tree(target_commit.as_object(), None)
            .map_err(|e| CoreError::Other(format!("检出目标分支失败: {e}")))?;
        let head_ref = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
        let head_name = head_ref
            .name()
            .ok_or_else(|| CoreError::Other("无法获取分支 ref 名".into()))?
            .to_string();
        repo.find_reference(&head_name)
            .map_err(|e| CoreError::Other(format!("读取分支 ref 失败: {e}")))?
            .set_target(target.id(), "merge: fast-forward")
            .map_err(|e| CoreError::Other(format!("快进失败: {e}")))?;
        repo.set_head(&head_name)
            .map_err(|e| CoreError::Other(format!("set_head 失败: {e}")))?;
        return Ok(json!({
            "outcome": "fast_forward",
            "branch": branch,
            "head_sha": target.id().to_string(),
            "message": "",
            "conflicts": [],
        })
        .to_string());
    }

    if !analysis.is_normal() {
        // NONE：两边没有共同祖先（本地历史与对方是两条无关的根）。
        // 报清楚，不假装能合 —— 用户能做的判断（是不是拉错仓库了）只有看到这句才做得了
        return Err(CoreError::Other(format!(
            "合并不了：当前分支与本地的 {branch} 没有共同祖先（不是同一条历史）。"
        )));
    }

    // ── 普通三方合并：libgit2 把结果写进索引与工作区（冲突文件带标记） ──
    repo.merge(&[&target], None, None)
        .map_err(|e| CoreError::Other(format!("合并失败: {e}")))?;

    let mut index = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    if index.has_conflicts() {
        let conflicts = conflict_entries(&mut index)?;
        // 冲突时把 `message` 换成 **MERGE_MSG 那一句**：它才是 [merge_continue] 不给信息时
        // 真正会落进提交的信息。这里若回显引擎自己拼的那句，界面显示的与最终提交的就会是两句话
        let message = std::fs::read_to_string(repo.path().join("MERGE_MSG"))
            .map(|m| m.trim_end().to_string())
            .ok()
            .filter(|m| !m.is_empty())
            .unwrap_or(message);
        // libgit2 在有冲突时自己会写 MERGE_HEAD / MERGE_MSG（`merge.c` 的 write_merge_head），
        // 所以这里不再手写一遍状态文件 —— 重复写只会让两处口径有机会分家
        let paths: Vec<String> = conflicts
            .iter()
            .map(|c| c["path"].as_str().unwrap_or_default().to_string())
            .collect();
        return Ok(json!({
            "outcome": "conflict",
            "branch": branch,
            "head_sha": head_commit.id().to_string(),
            "message": message,
            "conflicts": paths,
        })
        .to_string());
    }

    let id = commit_merge(&repo, &message, author_name, author_email, target.id())?;
    Ok(json!({
        "outcome": "merged",
        "branch": branch,
        "head_sha": id.to_string(),
        "message": message,
        "conflicts": [],
    })
    .to_string())
}

/// 当前的合并状态（只读）：`{ merging, branch, head_sha, merge_head_sha, message, conflicts }`。
///
/// 供两处用：合并弹窗/详情页的事实区，以及**「合并到一半被杀」之后重新进面板**
/// （`git-mode-design.md` §6.4 风险 ①）—— 那时上层手上没有任何列表，
/// 只有这里能告诉它「仓库正停在合并中、还剩哪几个文件」。
///
/// `conflicts` 是**当前还没解决**的文件（`[{path, kind}]`）。已解决的那些**不落盘、也不重算**：
/// 引擎不留「合并开始时有哪些冲突」的额外状态（那是上层在合并那一刻就拿到的东西）——
/// 多存一份就多一份会与索引分家的账。
pub fn merge_state(dir: &str) -> Result<String> {
    use git2::{Repository, RepositoryState};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut index = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    let conflicts = conflict_entries(&mut index)?;
    let head_sha = repo
        .head()
        .ok()
        .and_then(|h| h.target())
        .map(|o| o.to_string())
        .unwrap_or_default();
    Ok(json!({
        "merging": repo.state() == RepositoryState::Merge,
        "branch": head_branch_name(&repo),
        "head_sha": head_sha,
        "merge_head_sha": merge_head_oid(&repo).map(|o| o.to_string()).unwrap_or_default(),
        "message": std::fs::read_to_string(repo.path().join("MERGE_MSG"))
            .map(|m| m.trim_end().to_string())
            .unwrap_or_default(),
        "conflicts": conflicts,
    })
    .to_string())
}

/// **预解析**冲突（只读、不落盘）：每个冲突文件的 kind、三方 blob 与 `ours ↔ theirs` 的 patch。
///
/// 触发点是**冲突弹窗出现那一刻**（`git-mode-design.md` §6.4 的 D-h），不是用户点进详情页时 ——
/// 所以它是纯本地读：不动索引、不动工作区、不写任何文件（`analyze_conflicts_只读且不动工作区` 钉着）。
///
/// 输出：`{ files: [{ path, kind, binary, ours_sha, theirs_sha, base_sha, ours_size,
/// theirs_size, base_size, patch, truncated, ours, theirs, worktree, content_truncated }], truncated }`
///
/// - **冲突块就是 patch 里的 hunk**：不再单独算一套「冲突块」—— `ours ↔ theirs` 之间变了的
///   那几段正是要人做决定的地方，两处各算一份只会出现两套互相矛盾的范围；
/// - `binary = true` 时 `patch` 为空（libgit2 不给二进制内容），三方 size 照给 ——
///   上层据此说「二进制文件，请选一边」，而不是画一个空 diff；
/// - 某一侧 `*_sha` 为空 = **那一侧删了这个文件**（`kind` 已经说明是哪一侧）；
/// - `ours` / `theirs` / `worktree`：三方内容（**工作区那份带冲突标记**），供详情页
///   「用我方 / 用对方」预览与手工编辑的初值。单份上限 [`CONFLICT_CONTENT_LIMIT`]，
///   截断时置 `content_truncated`（与 patch 的 `truncated` 分开记 —— 两件事，别混成一个标志）；
/// - **base 只给 sha 与大小、不给内容**：界面要做的决定是「用我方还是用对方」，
///   base 不参与这个决定；真要看得走 patch（它就在里面）；
/// - 没有进行中的合并时**报错**而不是给空数组：空数组会被读成「没有冲突」，
///   而真相是「现在没有合并这回事」——两者要做的事完全不同。
pub fn analyze_conflicts(dir: &str) -> Result<String> {
    use git2::{DiffOptions, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    require_merging(&repo)?;

    let entries = conflicted_blob_entries(&repo)?;
    let mut files: Vec<serde_json::Value> = Vec::new();
    let mut any_truncated = false;
    for e in &entries {
        let old = e.ours.map(|oid| repo.find_blob(oid)).transpose().ok().flatten();
        let new = e.theirs.map(|oid| repo.find_blob(oid)).transpose().ok().flatten();
        let binary = old.as_ref().map(|b| b.is_binary()).unwrap_or(false)
            || new.as_ref().map(|b| b.is_binary()).unwrap_or(false);
        let mut opts = DiffOptions::new();
        opts.context_lines(3);
        // 冲突块的渲染走 `Patch`（blob ↔ blob，某一侧被删就是与空内容比）：
        // 这条路才拿得到「文件头 + hunk」，与 diff_worktree / diff_commit 的 patch 同一种读法，
        // 上层那份 unified diff 解析器（`BranchDiff.kt` 的 parseUnifiedDiff）直接就能吃
        let patch = if binary {
            (String::new(), false)
        } else {
            let p = std::path::Path::new(e.path.as_str());
            let mut rendered = match (old.as_ref(), new.as_ref()) {
                (Some(o), Some(n)) => git2::Patch::from_blobs(o, Some(p), n, Some(p), Some(&mut opts)),
                // 对方删了这个文件：新侧是空内容 —— 画出来就是「这些行都没了」
                (Some(o), None) => git2::Patch::from_blob_and_buffer(o, Some(p), b"", Some(p), Some(&mut opts)),
                // 我们这边删了它：旧侧是空内容 —— 画出来是「对方加了这些行」
                (None, Some(n)) => git2::Patch::from_buffers(b"", Some(p), n.content(), Some(p), Some(&mut opts)),
                // 三方都缺的冲突不存在（libgit2 保证至少一方在）
                (None, None) => continue,
            }
            .map_err(|err| CoreError::Other(format!("计算冲突文件的差异失败: {err}")))?;
            patch_to_text(&mut rendered)?
        };
        any_truncated |= patch.1;
        let ours = old.as_ref().map(|b| cap_content(b.content()));
        let theirs = new.as_ref().map(|b| cap_content(b.content()));
        // 工作区那份（带 `<<<<<<<` 标记）—— 手工编辑的初值就该是它：git 留给人的就是这个形状，
        // 从空文本开始编辑等于让用户自己把两边的内容再抄一遍
        let worktree = std::fs::read(
            repo.workdir()
                .map(|w| w.join(&e.path))
                .unwrap_or_else(|| std::path::PathBuf::from(&e.path)),
        )
        .ok()
        .map(|bytes| cap_content(&bytes));
        let content_truncated = [&ours, &theirs, &worktree]
            .iter()
            .any(|c| c.as_ref().map(|(_, cut)| *cut).unwrap_or(false));
        files.push(json!({
            "path": e.path,
            "kind": e.kind,
            "binary": binary,
            "ours_sha": e.ours.map(|o| o.to_string()).unwrap_or_default(),
            "theirs_sha": e.theirs.map(|o| o.to_string()).unwrap_or_default(),
            "base_sha": e.base.map(|o| o.to_string()).unwrap_or_default(),
            "ours_size": old.as_ref().map(|b| b.size()).unwrap_or(0),
            "theirs_size": new.as_ref().map(|b| b.size()).unwrap_or(0),
            "base_size": e.base.and_then(|oid| repo.find_blob(oid).ok()).map(|b| b.size()).unwrap_or(0),
            "patch": patch.0,
            "truncated": patch.1,
            "ours": ours.as_ref().map(|(t, _)| t.clone()).unwrap_or_default(),
            "theirs": theirs.as_ref().map(|(t, _)| t.clone()).unwrap_or_default(),
            "worktree": worktree.as_ref().map(|(t, _)| t.clone()).unwrap_or_default(),
            "content_truncated": content_truncated,
        }));
    }
    Ok(json!({ "files": files, "truncated": any_truncated }).to_string())
}

/// 用**某一侧**的内容解决一个冲突文件（`side` = `"ours"` / `"theirs"`），并把它记进索引。
///
/// - 内容从**索引的三方条目**取，不从工作区读：工作区那一份此刻是带 `<<<<<<<` 标记的，
///   拿它当「我方」等于把冲突标记当成用户的内容提交上去；
/// - 那一侧**没有内容**（对方删了这个文件）= 解决成「删除」：删工作区文件 + 从索引移除；
/// - 只接受**当前正在冲突**的路径：不在冲突清单里就报错，而不是悄悄覆盖一个已经解决的文件
///   （真机上那就是「点一下把手工解决的结果冲掉了」，而且没有任何提示）。
pub fn resolve_conflict(dir: &str, path: &str, side: &str) -> Result<()> {
    use git2::Repository;

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    require_merging(&repo)?;
    let rel = safe_rel_path(path)?;
    let wanted = match side {
        "ours" => Side::Ours,
        "theirs" => Side::Theirs,
        other => return Err(CoreError::Other(format!("未知的一侧: {other}（只认 ours / theirs）"))),
    };
    let entries = conflicted_blob_entries(&repo)?;
    let entry = entries
        .iter()
        .find(|e| e.path == rel)
        .ok_or_else(|| CoreError::Other(format!("{rel} 现在不在冲突清单里")))?;

    let oid = match wanted {
        Side::Ours => entry.ours,
        Side::Theirs => entry.theirs,
    };
    let mut index = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    let workdir = repo
        .workdir()
        .ok_or_else(|| CoreError::Other("这个仓库没有工作区".into()))?
        .to_path_buf();
    match oid {
        Some(oid) => {
            let blob = repo
                .find_blob(oid)
                .map_err(|e| CoreError::Other(format!("读取内容失败: {e}")))?;
            let target = workdir.join(&rel);
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)
                    .map_err(|e| CoreError::Other(format!("创建目录失败: {e}")))?;
            }
            std::fs::write(&target, blob.content())
                .map_err(|e| CoreError::Other(format!("写入文件失败: {e}")))?;
            // `add_path` 会把该路径的三方条目换成普通条目（libgit2 的 `git_index_add_bypath`
            // 就会清掉冲突）—— 这正是「标记已解决」那一步
            index
                .add_path(std::path::Path::new(&rel))
                .map_err(|e| CoreError::Other(format!("登记索引失败: {e}")))?;
        }
        None => {
            // 这一侧没有内容 = 这一侧删了它：解决成删除
            let _ = std::fs::remove_file(workdir.join(&rel));
            index
                .remove_path(std::path::Path::new(&rel))
                .map_err(|e| CoreError::Other(format!("从索引移除失败: {e}")))?;
        }
    }
    index.write().map_err(|e| CoreError::Other(format!("写索引失败: {e}")))?;
    Ok(())
}

/// 手工解决一个冲突文件：写入用户（或编辑器）给的内容，并把它记进索引。
///
/// 与 [`resolve_conflict`] 同一条口径：必须是**当前正在冲突**的路径（否则报错），
/// 写的是仓库相对路径（绝对路径 / `..` / `.git` 一律拒绝，见 [`safe_rel_path`]）。
pub fn write_resolved(dir: &str, path: &str, content: &str) -> Result<()> {
    use git2::Repository;

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    require_merging(&repo)?;
    let rel = safe_rel_path(path)?;
    let entries = conflicted_blob_entries(&repo)?;
    if !entries.iter().any(|e| e.path == rel) {
        return Err(CoreError::Other(format!("{rel} 现在不在冲突清单里")));
    }
    let workdir = repo
        .workdir()
        .ok_or_else(|| CoreError::Other("这个仓库没有工作区".into()))?
        .to_path_buf();
    let target = workdir.join(&rel);
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| CoreError::Other(format!("创建目录失败: {e}")))?;
    }
    std::fs::write(&target, content).map_err(|e| CoreError::Other(format!("写入文件失败: {e}")))?;
    let mut index = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    index
        .add_path(std::path::Path::new(&rel))
        .map_err(|e| CoreError::Other(format!("登记索引失败: {e}")))?;
    index.write().map_err(|e| CoreError::Other(format!("写索引失败: {e}")))?;
    Ok(())
}

/// 收尾：把解决完的索引落成**两父合并提交**，并清掉合并状态。返回新提交 sha。
///
/// - 还有没解决的文件时**报错并给出条数**（不是「失败」两个字：用户需要知道还差几个）；
/// - `message` 为空时用 `.git/MERGE_MSG`（libgit2 合并时写下的默认信息），
///   仍然为空才退回 `Merge branch` 那句 —— 合并提交**必须**有信息，空信息在日志里是一行空白；
/// - **敏感信息扫描口径与普通提交一致**：由上层在调用前对 `message` 跑 [`scan_sensitive`]
///   （引擎不替上层决定要不要拦），引擎自己生成的默认信息不含任何用户输入。
pub fn merge_continue(
    dir: &str,
    message: &str,
    author_name: &str,
    author_email: &str,
) -> Result<String> {
    use git2::Repository;

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    require_merging(&repo)?;
    let mut index = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    if index.has_conflicts() {
        let left = conflict_entries(&mut index)?.len();
        return Err(CoreError::Other(format!(
            "还有 {left} 个文件没解决，先解决完再提交合并"
        )));
    }
    let merge_head = merge_head_oid(&repo).ok_or_else(|| {
        CoreError::Other("仓库里没有 MERGE_HEAD —— 这次合并不完整，请「放弃合并」后重来".into())
    })?;
    let msg = if message.trim().is_empty() {
        let from_state = std::fs::read_to_string(repo.path().join("MERGE_MSG"))
            .map(|m| m.trim().to_string())
            .unwrap_or_default();
        if from_state.is_empty() {
            format!("Merge commit (MRG_HEAD {})", &merge_head.to_string()[..7])
        } else {
            from_state
        }
    } else {
        message.to_string()
    };
    let id = commit_merge(&repo, &msg, author_name, author_email, merge_head)?;
    Ok(id.to_string())
}

/// 放弃合并：**回到合并前**，一次 hard reset（D-h 的「任何时候 → 放弃合并」）。
///
/// 为什么敢 hard reset：合并的前置条件里就有「工作区必须干净」——
/// 所以此刻工作区里那些改动**全部**是这次合并产生的（冲突标记、对方带过来的内容），
/// reset 掉它们就是把仓库还原成合并前那一刻，不会碰用户自己的东西。
///
/// 顺序是**先清状态、再 reset**：反过来做的话，reset 到 HEAD 之后 MERGE_HEAD 还在，
/// 仓库就停在「合并中、但工作区是合并前的」这种自相矛盾的状态里（面板会一直问「继续还是放弃」）。
pub fn merge_abort(dir: &str) -> Result<()> {
    use git2::{Repository, ResetType};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    require_merging(&repo)?;
    let head = repo
        .head()
        .map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 提交失败: {e}")))?;
    repo.cleanup_state()
        .map_err(|e| CoreError::Other(format!("清理合并状态失败: {e}")))?;
    repo.reset(head.as_object(), ResetType::Hard, None)
        .map_err(|e| CoreError::Other(format!("回到合并前失败: {e}")))?;
    Ok(())
}

/// 冲突的一侧（[`resolve_conflict`] 的选择）。
enum Side {
    Ours,
    Theirs,
}

/// 一个冲突文件的三方 blob 与类型（[`conflict_entries`] 的瘦身版，供预解析与解决用）。
struct ConflictBlobs {
    path: String,
    kind: &'static str,
    base: Option<git2::Oid>,
    ours: Option<git2::Oid>,
    theirs: Option<git2::Oid>,
}

/// 当前索引里的冲突（按路径稳定排序）。
///
/// `kind` 是**粗粒度但如实**的分类：三方条目里谁缺了就是谁删了它，
/// 祖先缺失 = 双方各自新增（add/add）。细分到 git 那套 `UU/AA/DU/UD` 需要比对树，
/// 而 UI 要回答的问题只有一个 ——「这个文件该看着哪两侧做决定」，所以不细分。
fn conflict_blobs(index: &mut git2::Index) -> Result<Vec<ConflictBlobs>> {
    let mut out: Vec<ConflictBlobs> = Vec::new();
    let conflicts = index
        .conflicts()
        .map_err(|e| CoreError::Other(format!("读取冲突列表失败: {e}")))?;
    for c in conflicts {
        let c = c.map_err(|e| CoreError::Other(format!("读取冲突项失败: {e}")))?;
        let pick = |e: &Option<git2::IndexEntry>| -> Option<(String, git2::Oid)> {
            e.as_ref().map(|e| {
                (String::from_utf8_lossy(&e.path).to_string(), e.id)
            })
        };
        // 三方至少有一方在（libgit2 的保证），路径从任一方取
        let path = pick(&c.our)
            .or_else(|| pick(&c.their))
            .or_else(|| pick(&c.ancestor))
            .map(|(p, _)| p)
            .unwrap_or_default();
        if path.is_empty() {
            continue;
        }
        let kind = if c.ancestor.is_none() {
            "both_added"
        } else if c.our.is_none() {
            "deleted_by_us"
        } else if c.their.is_none() {
            "deleted_by_them"
        } else {
            "both_modified"
        };
        out.push(ConflictBlobs {
            path,
            kind,
            base: c.ancestor.map(|e| e.id),
            ours: c.our.map(|e| e.id),
            theirs: c.their.map(|e| e.id),
        });
    }
    out.sort_by(|a, b| a.path.cmp(&b.path));
    Ok(out)
}

/// 冲突清单（`[{path, kind}]`，按路径排序）—— 清单顺序要稳：
/// 它是按行渲染的列表，顺序抖一下用户就要重新找位置（与 `repo_status` 的 dirty 同一条口径）。
fn conflict_entries(index: &mut git2::Index) -> Result<Vec<serde_json::Value>> {
    Ok(conflict_blobs(index)?
        .into_iter()
        .map(|c| json!({ "path": c.path, "kind": c.kind }))
        .collect())
}

/// 冲突文件的三方 blob（[`resolve_conflict`] / [`analyze_conflicts`] 用）。
fn conflicted_blob_entries(repo: &git2::Repository) -> Result<Vec<ConflictBlobs>> {
    let mut index = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    conflict_blobs(&mut index)
}

/// 合并状态的统一前置检查：不在合并中就报错（**不**静默当成空操作）。
fn require_merging(repo: &git2::Repository) -> Result<()> {
    if repo.state() != git2::RepositoryState::Merge {
        return Err(CoreError::Other("当前没有进行中的合并".into()));
    }
    Ok(())
}

/// `MERGE_HEAD` 指向的提交（不在合并中 → None）。
///
/// 读引用而不是读文件：`MERGE_HEAD` 是 libgit2 认的伪引用（`refdb_fs` 顶层就处理它），
/// 手撕 `.git/MERGE_HEAD` 只会多一份「文件格式」的知识要维护。
fn merge_head_oid(repo: &git2::Repository) -> Option<git2::Oid> {
    repo.find_reference("MERGE_HEAD").ok().and_then(|r| r.target())
}

/// 工作区是否有**未提交改动**（不含未跟踪文件，与 `revert_commit` 同一条口径）。
fn worktree_dirty(repo: &git2::Repository) -> Result<bool> {
    let mut opts = git2::StatusOptions::new();
    opts.include_untracked(false);
    let statuses = repo
        .statuses(Some(&mut opts))
        .map_err(|e| CoreError::Other(format!("读取工作区状态失败: {e}")))?;
    Ok(!statuses.is_empty())
}

/// 合并的目标提交。四种写法，按这个顺序试：
///
/// 1. **显式远端形态**（`origin/main`）：按 `refs/remotes/{branch}` 原样查。
///    这一步**必须排在本地分支之前** —— 「本地与上游分叉，把远端那条合进来」的场景里
///    两边**同名**（都叫 `main`），先查 `refs/heads/main` 会把「合并远端」解析成
///    本地那条分支（也就是 HEAD 自己）：`merge_analysis` 判成 `up_to_date`，
///    界面上就是「点了合并，什么都没发生」，而且**不报错**；
/// 2. **本地分支**：短名（`feature`）的老语义，一字未改；
/// 3. **`origin/{branch}`**：目标只在远端时不用写前缀（PR 冲突「拉到本地解决」那条路，
///    目标往往是别人的分支 / PR 的 head，本地从来没有过）；
/// 4. 都没有就用 token `fetch` 一次，再把 1 与 3 两种名字各查一遍 ——
///    让上层先手动 fetch 是把一件事拆两步，而两步之间用户会看到「找不到分支」这种中间态错误。
fn resolve_merge_target<'r>(
    repo: &'r git2::Repository,
    branch: &str,
    token: Option<&str>,
) -> Result<git2::AnnotatedCommit<'r>> {
    // 显式远端形态只在名字里带 `/` 时才算（`refs/remotes/main` 这种形状不存在，
    // 但短名走这条只会白查一次；带 `/` 才是「远端名/分支名」的形状）
    let explicit = if branch.contains('/') {
        Some(format!("refs/remotes/{branch}"))
    } else {
        None
    };
    let remote_ref = format!("refs/remotes/origin/{branch}");

    if let Some(reference) = explicit.as_deref().and_then(|name| repo.find_reference(name).ok()) {
        return repo
            .reference_to_annotated_commit(&reference)
            .map_err(|e| CoreError::Other(format!("解析远端分支 {branch} 失败: {e}")));
    }
    let local = format!("refs/heads/{branch}");
    if let Ok(reference) = repo.find_reference(&local) {
        return repo
            .reference_to_annotated_commit(&reference)
            .map_err(|e| CoreError::Other(format!("解析分支 {branch} 失败: {e}")));
    }
    if let Ok(reference) = repo.find_reference(&remote_ref) {
        return repo
            .reference_to_annotated_commit(&reference)
            .map_err(|e| CoreError::Other(format!("解析远端分支 origin/{branch} 失败: {e}")));
    }
    if repo.find_remote("origin").is_ok() {
        fetch_origin(repo, token, false)?;
        for name in explicit.iter().chain(std::iter::once(&remote_ref)) {
            if let Ok(reference) = repo.find_reference(name) {
                return repo
                    .reference_to_annotated_commit(&reference)
                    .map_err(|e| CoreError::Other(format!("解析远端分支 {branch} 失败: {e}")));
            }
        }
        // 两种写法的失败原因不一样，别用一句话糊过去：显式远端写错时，用户要的是
        // 「这个名字在远端没有」，而不是「本地也没有」——后者会让人去本地找一条本来就不该在的分支
        return Err(CoreError::Other(if explicit.is_some() {
            format!("找不到远端分支 {branch}（fetch 之后也没有）")
        } else {
            format!("找不到分支 {branch}（本地与 origin 上都没有）")
        }));
    }
    Err(CoreError::Other(if explicit.is_some() {
        format!("找不到远端分支 {branch}（这个仓库没有 origin 远端）")
    } else {
        format!("找不到分支 {branch}（本地与 origin 上都没有）")
    }))
}

/// 落一个**两父**合并提交并清掉合并状态（干净合并与 [`merge_continue`] 共用）。
///
/// 两个父的顺序是 `[HEAD, 对方]`：第一父是「合到哪」、第二父是「合了谁」——
/// 反过来的话 `git log --first-parent` 看到的是一场相反的合并，而提交图也会把泳道画歪。
fn commit_merge(
    repo: &git2::Repository,
    message: &str,
    author_name: &str,
    author_email: &str,
    other: git2::Oid,
) -> Result<git2::Oid> {
    use git2::Signature;

    let mut index = repo
        .index()
        .map_err(|e| CoreError::Other(format!("读取索引失败: {e}")))?;
    let tree_id = index
        .write_tree()
        .map_err(|e| CoreError::Other(format!("写树失败: {e}")))?;
    let tree = repo
        .find_tree(tree_id)
        .map_err(|e| CoreError::Other(format!("找树失败: {e}")))?;
    let head = repo
        .head()
        .map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 提交失败: {e}")))?;
    let other = repo
        .find_commit(other)
        .map_err(|e| CoreError::Other(format!("找不到待合并提交: {e}")))?;
    let sig = Signature::now(author_name, author_email)
        .map_err(|e| CoreError::Other(format!("签名失败: {e}")))?;
    let id = repo
        .commit(
            Some("HEAD"),
            &sig,
            &sig,
            message,
            &tree,
            &[&head, &other],
        )
        .map_err(|e| CoreError::Other(format!("提交合并失败: {e}")))?;
    // 提交成功之后才清状态：先清后提交的话，提交失败就只剩一个「没有 MERGE_HEAD 的合并中仓库」，
    // 那时「继续」已经不可能（找不到第二个父），用户唯一的出路是放弃
    repo.cleanup_state()
        .map_err(|e| CoreError::Other(format!("清理合并状态失败: {e}")))?;
    Ok(id)
}

/// 仓库相对路径的收口：拒绝空、绝对路径、`..`、以及 `.git` 下的任何东西。
///
/// 冲突路径来自索引（本来就是我们自己写的），但 [`write_resolved`] 的路径来自**上层传来的字符串**——
/// 少了这道检查，一个 `../` 就能让它写到仓库外面去。
fn safe_rel_path(path: &str) -> Result<String> {
    let trimmed = path.trim().trim_start_matches("./");
    if trimmed.is_empty() {
        return Err(CoreError::Other("文件路径不能为空".into()));
    }
    let p = std::path::Path::new(trimmed);
    if p.is_absolute() {
        return Err(CoreError::Other(format!("只接受仓库内的相对路径: {path}")));
    }
    for comp in p.components() {
        match comp {
            std::path::Component::Normal(name) => {
                if name == ".git" {
                    return Err(CoreError::Other("不允许写到 .git 里".into()));
                }
            }
            _ => {
                return Err(CoreError::Other(format!(
                    "路径里不允许出现 .. 或根: {path}"
                )))
            }
        }
    }
    Ok(p.to_string_lossy().replace('\\', "/"))
}

// ── 工作台的本地读接口（阶段 3：提交图 / 引用树 / 文件历史 / 本地 diff） ──
//
// 这一组全是**只读**：不 fetch、不写工作区、不动 ref。它们存在的理由只有一个 ——
// 仓库已经在本地（本地优先），工作台的这三档没必要再去打 REST：离线可读、不消耗 API 限额、
// 也不受「默认分支」这种服务端口径影响（`GET /contents/{path}` 就不带 ref，
// 于是「默认分支不是 main」的仓库会串内容）。
//
// 输出一律是**扁平的 native JSON**（不是 GitHub REST 那份嵌套结构）：这是我们自己的接口，
// 没必要模仿别人的响应体；Kotlin 侧各有各的解析（REST 一份、本地一份，见 CommitGraphModels.kt）。

/// 提交图的本地来源（对齐 `git-mode-design.md` §4.1「本地版（阶段 3）」）。
///
/// 输出 JSON 数组（**新的在前**）：
/// `[{ sha, parents: [sha…], subject, author, date, unpushed }]`
///
/// - `limit` / `skip`：分页。不设上限是产品决策（head 全取、「加载更早」不限次数），
///   但**一次调用只取一页** —— 调用方按 `skip += limit` 续取，尾部如实写「已加载 N 条」；
/// - `parents` 一定要带上：泳道布局靠它，缺了只能画成一条直线（图就废了）；
/// - `unpushed`：这条提交**还没推送到上游**（见 [unpushed_oids]）。它是「未推送段」的画法依据
///   （`git-mode-design.md` §4.1），**恒有值**（false 也发出来）—— 老 `.so` 缺这个键时
///   Kotlin 侧按 false 退化，所以没有版本协商的麻烦；
/// - 空仓库（没有 HEAD）**不是错误**：返回空数组。上层据此显示「这个分支还没有提交」，
///   而不是红字报错 —— 「没有提交」是正常状态，不是失败。
pub fn log_graph(dir: &str, limit: usize, skip: usize) -> Result<String> {
    use git2::{Repository, Sort};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut out: Vec<serde_json::Value> = Vec::new();

    // 空仓库 / detached 且无提交：head() 会失败，这不是「错了」
    let Ok(head) = repo.head() else {
        return Ok(json!(out).to_string());
    };
    let Ok(head_commit) = head.peel_to_commit() else {
        return Ok(json!(out).to_string());
    };

    // 未推送集合：**只算一次**，与逐条出图无关（集合本身很小：HEAD..上游）
    let unpushed = unpushed_oids(&repo, head.shorthand().unwrap_or(""), head_commit.id());

    let mut walk = repo.revwalk().map_err(|e| CoreError::Other(format!("创建 revwalk 失败: {e}")))?;
    // TOPOLOGICAL：父在子之后出现（图从上往下画的前提）。**不要**加 SORT_TIME 之外的排序：
    // 泳道布局假定「一条提交的所有父都在它下方」，时间序在时钟回拨的仓库上不保证这一点。
    walk.set_sorting(Sort::TOPOLOGICAL | Sort::TIME)
        .map_err(|e| CoreError::Other(format!("设置 revwalk 排序失败: {e}")))?;
    walk.push(head_commit.id())
        .map_err(|e| CoreError::Other(format!("revwalk push_head 失败: {e}")))?;

    for oid in walk.skip(skip).take(limit) {
        let oid = oid.map_err(|e| CoreError::Other(format!("revwalk 迭代失败: {e}")))?;
        let commit = repo
            .find_commit(oid)
            .map_err(|e| CoreError::Other(format!("读取提交失败: {e}")))?;
        let parents: Vec<String> = commit.parent_ids().map(|p| p.to_string()).collect();
        out.push(json!({
            "sha": commit.id().to_string(),
            "parents": parents,
            "subject": commit.summary().unwrap_or(""),
            "author": commit.author().name().unwrap_or("").to_string(),
            "date": commit_time_iso(commit.author().when()),
            "unpushed": unpushed.contains(&oid),
        }));
    }

    Ok(json!(out).to_string())
}

/// 未推送提交的集合：**HEAD 可达、上游不可达**（`git log @{u}..HEAD`）。
///
/// 与 [`repo_status`] 的 `ahead` 是**同一套口径**（那里是 `graph_ahead_behind(local, upstream)`）——
/// 两个数字必须相等，工作区档写着「待推送 3」而图上一个标记都没有，比两边都没有更坏。
///
/// 三种「返回空集」都是有意的，别改成「全都标上」：
/// - **没有上游**（分支没有 upstream 配置 / detached HEAD）：那时「未推送」无从谈起 ——
///   把所有提交都标成未推送等于每一行都在喊同一件事，用户学到的只是「这个标记没有信息量」；
/// - **revwalk 建不起来 / hide 失败**：这是显示用的提示，不该让整张图变成错误页；
/// - 上游就是 HEAD（没有本地提交）：集合本来就空。
fn unpushed_oids(
    repo: &git2::Repository,
    branch: &str,
    head: git2::Oid,
) -> std::collections::HashSet<git2::Oid> {
    use std::collections::HashSet;

    let mut set = HashSet::new();
    if branch.is_empty() {
        return set;
    }
    let upstream = repo
        .find_branch(branch, git2::BranchType::Local)
        .ok()
        .and_then(|b| b.upstream().ok())
        .and_then(|u| u.get().target());
    let Some(upstream_oid) = upstream else {
        return set;
    };
    let Ok(mut walk) = repo.revwalk() else {
        return set;
    };
    if walk.push(head).is_err() || walk.hide(upstream_oid).is_err() {
        return set;
    }
    for oid in walk.flatten() {
        set.insert(oid);
    }
    set
}

/// tag 清单（对齐 D-f：**取全字段**）。
///
/// 输出 JSON 数组：`[{ name, sha, annotated, target_sha, tagger, message }]`
///
/// - `annotated = true`：`sha` 是 **tag 对象**的 id，`target_sha` 是它指向的提交；
///   `tagger` = `{ name, email, time }`（time 是 ISO 8601 本地偏移串），`message` = 说明；
/// - `annotated = false`（轻量 tag）：`sha` 就是提交 id，`target_sha` 与它相同，
///   **`tagger = null`、`message` 为空串** —— D-f 要求「非 annotated 留空、不填假值」：
///   给轻量 tag 编一个 tagger 等于在界面上撒谎；
/// - 按名字排序（与 `local_branches` 的「当前分支置顶」不同：tag 没有「当前」）。
pub fn list_tags(dir: &str) -> Result<String> {
    use git2::Repository;

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let names = repo
        .tag_names(None)
        .map_err(|e| CoreError::Other(format!("读取 tag 失败: {e}")))?;

    let mut out: Vec<serde_json::Value> = Vec::new();
    for name in names.iter().flatten() {
        let Ok(reference) = repo.find_reference(&format!("refs/tags/{name}")) else {
            continue;
        };
        let target = reference.target();
        // 指向 tag 对象 = annotated；直接指向提交 = 轻量
        let annotated = target
            .and_then(|oid| repo.find_tag(oid).ok())
            .is_some();
        let (sha, target_sha, tagger, message) = if annotated {
            let oid = target.unwrap();
            let tag = repo
                .find_tag(oid)
                .map_err(|e| CoreError::Other(format!("读取 tag 对象失败: {e}")))?;
            let peeled = tag.target_id().to_string();
            let t = tag.tagger();
            let tagger = t.map(|sig| {
                json!({
                    "name": sig.name().unwrap_or(""),
                    "email": sig.email().unwrap_or(""),
                    "time": commit_time_iso(sig.when()),
                })
            });
            (oid.to_string(), peeled, tagger, tag.message().unwrap_or("").to_string())
        } else {
            // 轻量 tag：sha 就是提交；没有 tagger / 说明，留空
            let peeled = reference
                .peel_to_commit()
                .map(|c| c.id().to_string())
                .unwrap_or_else(|_| target.map(|o| o.to_string()).unwrap_or_default());
            (peeled.clone(), peeled, None, String::new())
        };
        out.push(json!({
            "name": name,
            "sha": sha,
            "annotated": annotated,
            "target_sha": target_sha,
            "tagger": tagger,
            "message": message,
        }));
    }
    out.sort_by(|a, b| {
        a.get("name").and_then(|v| v.as_str()).unwrap_or("")
            .cmp(b.get("name").and_then(|v| v.as_str()).unwrap_or(""))
    });
    Ok(json!(out).to_string())
}

/// 某个文件的提交历史（本地优先，REST 兜底的那一条路里的「本地」）。
///
/// 输出 JSON 数组（新的在前）：`[{ sha, subject, author, date }]` —— 与 [log_graph] 同字段，
/// 少一个 `parents`（文件历史不画图）。
///
/// 走 revwalk + `diff_tree_to_tree` 逐个提交比对：libgit2 没有 `log -- path` 的直接接口，
/// 只能自己走。代价是「越深的仓库越慢」，所以：
/// - 命中 `limit` 就停（上层按「加载更早」续取，不一次翻遍全史）；
/// - **一发现这个提交没碰过该路径就跳过，但仍要继续走**（历史是链式的，不能提前退出）。
pub fn log_file(dir: &str, path: &str, limit: usize, skip: usize) -> Result<String> {
    use git2::{DiffOptions, Repository, Sort};

    if path.trim().is_empty() {
        return Err(CoreError::Other("文件路径不能为空".into()));
    }
    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut out: Vec<serde_json::Value> = Vec::new();

    let head = repo.head().map_err(|e| CoreError::Other(format!("读取 HEAD 失败: {e}")))?;
    let head_commit = head
        .peel_to_commit()
        .map_err(|e| CoreError::Other(format!("解析 HEAD 提交失败: {e}")))?;

    let mut walk = repo.revwalk().map_err(|e| CoreError::Other(format!("创建 revwalk 失败: {e}")))?;
    walk.set_sorting(Sort::TOPOLOGICAL | Sort::TIME)
        .map_err(|e| CoreError::Other(format!("设置 revwalk 排序失败: {e}")))?;
    walk.push(head_commit.id())
        .map_err(|e| CoreError::Other(format!("revwalk push_head 失败: {e}")))?;

    let mut matched = 0usize;
    let mut opts = DiffOptions::new();
    opts.pathspec(path);
    for oid in walk {
        if out.len() >= limit {
            break;
        }
        let oid = oid.map_err(|e| CoreError::Other(format!("revwalk 迭代失败: {e}")))?;
        let commit = repo
            .find_commit(oid)
            .map_err(|e| CoreError::Other(format!("读取提交失败: {e}")))?;
        let tree = commit.tree().map_err(|e| CoreError::Other(format!("读取提交树失败: {e}")))?;
        let parent_tree = match commit.parent(0) {
            Ok(parent) => Some(
                parent
                    .tree()
                    .map_err(|e| CoreError::Other(format!("读取父提交树失败: {e}")))?,
            ),
            Err(_) => None,
        };
        let diff = repo
            .diff_tree_to_tree(parent_tree.as_ref(), Some(&tree), Some(&mut opts))
            .map_err(|e| CoreError::Other(format!("比较提交树失败: {e}")))?;
        // 根提交（没有父）只要该路径在树里就算碰过；其余看 diff 有没有这个路径
        let touched = if parent_tree.is_none() {
            tree.get_path(std::path::Path::new(path)).is_ok()
        } else {
            diff.deltas().len() > 0
        };
        if !touched {
            continue;
        }
        matched += 1;
        if matched <= skip {
            continue;
        }
        out.push(json!({
            "sha": commit.id().to_string(),
            "subject": commit.summary().unwrap_or(""),
            "author": commit.author().name().unwrap_or("").to_string(),
            "date": commit_time_iso(commit.author().when()),
        }));
    }

    Ok(json!(out).to_string())
}

/// 工作区相对 HEAD 的本地 diff（未提交改动）。
///
/// 输出：`{ patch, files: [{ path, status, additions, deletions }], truncated }`
///
/// **两份数据按同一次 diff 的同序生成**：`files[i]` 来自第 i 个 delta，
/// `patch` 里的第 i 段（以 `diff --git` 开头）也来自第 i 个 delta —— 上层因此可以按**下标**对齐，
/// 不必去解析路径（路径里有空格 / 中文时，解析 header 的写法很脆）。这条不变量由
/// `diff_worktree_未跟踪文件带内容且两段按下标对齐` 钉着。
///
/// - `patch`：unified diff 文本（直接给 UI 渲染 / 给编辑器做冲突高亮）；
/// - `status`：`A` / `D` / `M` / `R`（与 `repo_status` 的 dirty 同一套字母）；
/// - `truncated`：patch 超过 [`DIFF_PATCH_LIMIT`] 字节被截断。**必须如实告诉上层** ——
///   悄悄截断会让人以为「改动就这么点」。
pub fn diff_worktree(dir: &str) -> Result<String> {
    use git2::{DiffOptions, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut opts = DiffOptions::new();
    opts.include_untracked(true);
    opts.recurse_untracked_dirs(true);
    // **未跟踪文件必须带内容**：默认情况下 libgit2 只把未跟踪文件列进 `deltas`、
    // 不给它的 patch（`GIT_DIFF_SHOW_UNTRACKED_CONTENT` 未置位），于是「新增一个文件」
    // —— 脏工作区里最常见的一种 —— 在面板上会点开一片空白（真机上表现为「点开没有差异」）。
    // 置位之后：`files` 的 additions 是真的行数，`patch` 里也能看到它的内容（同样是 + 行）。
    opts.show_untracked_content(true);
    opts.context_lines(3);
    // **必须显式给 HEAD 树**：`diff_tree_to_workdir_with_index(None, …)` 的 old 侧是**空树**，
    // 于是「改过的已跟踪文件」会被报成 Untracked/新增（真机上表现为「改动清单说这是新文件」）。
    // 给了树才等价于 `git diff HEAD`（暂存 + 未暂存一起看）。
    // 空仓库（没有 HEAD）保持 None —— 那时「全是新增」本来就是对的。
    let head_tree = repo.head().ok().and_then(|h| h.peel_to_tree().ok());
    let diff = repo
        .diff_tree_to_workdir_with_index(head_tree.as_ref(), Some(&mut opts))
        .map_err(|e| CoreError::Other(format!("计算工作区 diff 失败: {e}")))?;
    render_diff(&diff)
}

/// 某个提交相对其第一父的本地 diff（根提交与空树比）。
///
/// 与 [diff_worktree] 同一套输出结构 —— 上层因此可以**共用一份渲染**，
/// 不必为「看工作区」和「看某个提交」写两套。
pub fn diff_commit(dir: &str, sha: &str) -> Result<String> {
    use git2::{DiffOptions, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let oid = git2::Oid::from_str(sha.trim())
        .map_err(|e| CoreError::Other(format!("无效的提交 sha: {e}")))?;
    let commit = repo
        .find_commit(oid)
        .map_err(|e| CoreError::Other(format!("找不到提交: {e}")))?;
    let tree = commit.tree().map_err(|e| CoreError::Other(format!("读取提交树失败: {e}")))?;
    let parent_tree = match commit.parent(0) {
        Ok(parent) => Some(
            parent
                .tree()
                .map_err(|e| CoreError::Other(format!("读取父提交树失败: {e}")))?,
        ),
        Err(_) => None,
    };
    let mut opts = DiffOptions::new();
    opts.context_lines(3);
    let diff = repo
        .diff_tree_to_tree(parent_tree.as_ref(), Some(&tree), Some(&mut opts))
        .map_err(|e| CoreError::Other(format!("比较提交树失败: {e}")))?;
    render_diff(&diff)
}

/// 冲突三方内容（`ours` / `theirs` / `worktree`）**每一份**的上限（字节）。
///
/// 与 patch 的上限分开：patch 是给眼睛看的差异，内容是给编辑器当初值的 ——
/// 一个 5 MB 的冲突文件整份走 JNI 回来会挤占主线程内存，而这种文件人也不会在手机上手工合。
/// 截断时置 `content_truncated`，界面据此说「内容太大，只带回了开头」。
const CONFLICT_CONTENT_LIMIT: usize = 64 * 1024;

/// 把一段字节变成**可传给上层**的文本：UTF-8 宽松解码 + 超限按字符边界截断。
/// 返回 `(文本, 是否截断)`。
fn cap_content(bytes: &[u8]) -> (String, bool) {
    let text = String::from_utf8_lossy(bytes).to_string();
    if text.len() <= CONFLICT_CONTENT_LIMIT {
        return (text, false);
    }
    let mut cut = CONFLICT_CONTENT_LIMIT;
    while cut > 0 && !text.is_char_boundary(cut) {
        cut -= 1;
    }
    (text[..cut].to_string(), true)
}

/// patch 文本的上限（字节）。
///
/// 一个几百 KB 的 diff 走 JNI 回来会挤占主线程与内存，而用户真正会看的是前几屏 ——
/// 所以这里**截断并且如实标记**（[render_diff] 的 `truncated`），不静默丢内容。
const DIFF_PATCH_LIMIT: usize = 200 * 1024;

/// 把 `git2::Diff` 渲染成统一结构（[diff_worktree] 与 [diff_commit] 共用）。
fn render_diff(diff: &git2::Diff<'_>) -> Result<String> {
    let (patch, truncated) = render_patch(diff)?;

    // 逐文件的增删行数（走 hunk 统计，不重跑 diff）
    let mut files: Vec<serde_json::Value> = Vec::new();
    for (idx, delta) in diff.deltas().enumerate() {
        let path = delta
            .new_file()
            .path()
            .or_else(|| delta.old_file().path())
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default();
        let status = match delta.status() {
            git2::Delta::Added | git2::Delta::Untracked => "A",
            git2::Delta::Deleted => "D",
            git2::Delta::Renamed => "R",
            _ => "M",
        };
        // 第 idx 个 delta ↔ 第 idx 个 patch（顺序一致，别用 files.len() 隐式对齐）
        let (additions, deletions) = match git2::Patch::from_diff(diff, idx) {
            Ok(Some(patch)) => match patch.line_stats() {
                Ok((_, add, del)) => (add, del),
                Err(_) => (0, 0),
            },
            _ => (0, 0),
        };
        files.push(json!({
            "path": path,
            "status": status,
            "additions": additions,
            "deletions": deletions,
        }));
    }

    Ok(json!({
        "patch": patch,
        "files": files,
        "truncated": truncated,
    })
    .to_string())
}

/// 把 diff 渲染成 patch 文本，返回 `(patch, truncated)`。
///
/// 截断上限与「按字符边界切」的规矩只在这里写一遍：[render_diff]（工作区 / 提交的 diff）
/// 与 [analyze_conflicts]（冲突文件的 ours ↔ theirs）都要它，两处各写一份的话，
/// 上限或切法迟早有一处漏改 —— 而它们的表现都是「内容少了一截，界面却说这就是全部」。
fn render_patch(diff: &git2::Diff<'_>) -> Result<(String, bool)> {
    use git2::DiffFormat;

    let mut patch = String::new();
    diff.print(DiffFormat::Patch, |_delta, _hunk, line| {
        // 行首那个字符是 diff 的语义（+ / - / 空格 / \），一个字都不能丢
        let origin = line.origin();
        if matches!(origin, '+' | '-' | ' ') {
            patch.push(origin);
        }
        patch.push_str(&String::from_utf8_lossy(line.content()));
        true
    })
    .map_err(|e| CoreError::Other(format!("渲染 diff 失败: {e}")))?;

    Ok(truncate_patch(patch))
}

/// 把一个 `Patch`（blob ↔ blob）渲染成同一种 patch 文本。冲突预解析用这条 ——
/// 它拿得到文件头与 hunk，正是 unified diff 解析器要吃的东西。
fn patch_to_text(patch: &mut git2::Patch<'_>) -> Result<(String, bool)> {
    let buf = patch
        .to_buf()
        .map_err(|e| CoreError::Other(format!("渲染 diff 失败: {e}")))?;
    Ok(truncate_patch(String::from_utf8_lossy(buf.as_ref()).to_string()))
}

/// patch 文本的超限截断：超过 [`DIFF_PATCH_LIMIT`] 就截，并**如实返回** `truncated`。
///
/// 只有这一处写「上限是多少、怎么切」：[render_diff] 那条路与冲突预解析那条路都从这里过，
/// 两处各写一份的话，上限或切法迟早有一处漏改，而表现都是「内容少了一截，界面却说这是全部」。
fn truncate_patch(mut patch: String) -> (String, bool) {
    let truncated = patch.len() > DIFF_PATCH_LIMIT;
    if truncated {
        // 按字符边界截断（diff 里有中文时按字节切会把一个字符切成两半）
        let mut cut = DIFF_PATCH_LIMIT;
        while cut > 0 && !patch.is_char_boundary(cut) {
            cut -= 1;
        }
        patch.truncate(cut);
    }
    (patch, truncated)
}

/// 把 git 时间（秒 + 时区偏移）写成 ISO 8601 本地偏移串。
///
/// 为什么不用 `chrono`：为一个时间格式再引一个依赖不值当，而 git2 给的偏移本身就是
/// 「分钟数」，手算一次比引依赖清楚。输出形如 `2026-09-25T04:41:23+08:00` ——
/// 与 REST 那份 `author.date` 同形，UI 侧因此不用分辨数据来自哪边。
fn commit_time_iso(time: git2::Time) -> String {
    let offset_min = time.offset_minutes();
    let secs = time.seconds() + (offset_min as i64) * 60;
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (h, mi, s) = (rem / 3600, (rem % 3600) / 60, rem % 60);
    let (y, m, d) = civil_from_days(days);
    let sign = if offset_min < 0 { '-' } else { '+' };
    let abs = offset_min.abs();
    format!(
        "{y:04}-{m:02}-{d:02}T{h:02}:{mi:02}:{s:02}{sign}{:02}:{:02}",
        abs / 60,
        abs % 60
    )
}

/// 天数（1970-01-01 起）→ 公历年月日（Howard Hinnant 的 `civil_from_days`）。
///
/// 自己算是为了**不引 chrono**：这个换算只有 6 行、有单测钉着，
/// 而多一个日期库就多一份交叉编译负担（这个仓库已经在为 vendored openssl / libgit2 付代价）。
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as i64; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32; // [1, 12]
    (if m <= 2 { y + 1 } else { y }, m, d)
}

#[cfg(test)]
mod tests {
    // 注：CI（build-core.yml）会把失败关键行以 annotation 输出，便于无日志下载权限时定位。
    use super::*;

    // ───────────────────── dns_matches：证书主机名匹配 ─────────────────────

    #[test]
    fn dns_matches_requires_dot_separator() {
        assert!(dns_matches("*.github.com", "api.github.com"));
        // 缺少点分隔不应匹配（早期实现会把 xgithub.com 误判为匹配）
        assert!(!dns_matches("*.github.com", "xgithub.com"));
    }

    #[test]
    fn dns_matches_rejects_multi_level_subdomain() {
        assert!(!dns_matches("*.github.com", "a.b.github.com"));
    }

    #[test]
    fn dns_matches_rejects_bare_apex() {
        assert!(!dns_matches("*.github.com", "github.com"));
    }

    #[test]
    fn dns_matches_exact_pattern() {
        assert!(dns_matches("github.com", "github.com"));
        assert!(!dns_matches("github.com", "api.github.com"));
        assert!(!dns_matches("github.com", "githubb.com"));
    }

    // ───────────────────── map_push_error：nff 归一 ─────────────────────

    /// 取 `CoreError::Other` 的内部消息。
    ///
    /// 注意不能用 `format!("{}", e)`：`CoreError` 的 Display 会加
    /// `未知错误: ` 前缀（见 lib.rs 的 thiserror 定义），断言前缀会失败。
    fn other_message(e: CoreError) -> String {
        match e {
            CoreError::Other(s) => s,
            other => panic!("应为 CoreError::Other，实际: {other:?}"),
        }
    }

    #[test]
    fn push_error_maps_rejection_to_nff() {
        for msg in [
            "remote rejected (non-fast-forward)",
            "cannot lock ref 'refs/heads/main'",
            "! [rejected] main -> main",
        ] {
            let text = other_message(map_push_error(msg));
            assert!(text.starts_with("nff: "), "应归一为 nff 前缀，实际: {text}");
        }
    }

    #[test]
    fn push_error_keeps_other_failures() {
        let text = other_message(map_push_error("authentication failed"));
        assert!(text.starts_with("push 失败: "), "实际: {text}");
        assert!(!text.contains("nff:"), "非快进之外不应带 nff 前缀");
    }

    // ───────────────────── scan_sensitive：提交前敏感信息扫描 ─────────────────────

    fn hits(text: &str) -> Vec<serde_json::Value> {
        let json = scan_sensitive(text).expect("扫描应成功");
        serde_json::from_str(&json).expect("结果应是 JSON 数组")
    }

    #[test]
    fn scan_detects_github_pat_and_masks_value() {
        let out = hits("+ ghp_abcdefghijklmnopqrstuvwxyz01");
        assert_eq!(out.len(), 1, "应恰好命中 1 条: {out:?}");
        assert_eq!(out[0]["kind"], "GitHub PAT");
        assert_eq!(out[0]["line"], 1);
        // 只保留前 4 字符，其余打码
        assert_eq!(out[0]["mask"], "abcd****");
    }

    #[test]
    fn scan_detects_private_key_block() {
        let out = hits("-----BEGIN RSA PRIVATE KEY-----");
        assert_eq!(out.len(), 1);
        assert_eq!(out[0]["kind"], "私钥");
    }

    #[test]
    fn scan_detects_key_value_and_reports_line_number() {
        let out = hits("第一行\napi_key = \"abcdefgh1234\"\n");
        assert_eq!(out.len(), 1, "应命中 1 条: {out:?}");
        assert_eq!(out[0]["line"], 2);
        assert_eq!(out[0]["mask"], "abcd****");
    }

    #[test]
    fn scan_ignores_plain_and_short_values() {
        assert!(hits("").is_empty());
        assert!(hits("这是一个普通的提交信息").is_empty());
        // 长度不足 8 的疑似值不报警
        assert!(hits("password = ab").is_empty());
        // 含空格的值不像密钥
        assert!(hits("password = abc def ghi").is_empty());
    }

    // ───────────────────── verify_cert_chain：内置 CA bundle ─────────────────────

    #[test]
    fn builtin_bundle_contains_self_signed_root_that_verifies() {
        let certs = ca_certs();
        assert!(certs.len() > 10, "内置 bundle 应含多个 CA，实际 {}", certs.len());
        let root = certs
            .iter()
            .find(|c| c.subject_name().try_cmp(c.issuer_name()).ok() == Some(std::cmp::Ordering::Equal))
            .expect("bundle 里应存在自签名根证书");
        assert!(verify_cert_chain(root), "自签名根应通过链验证");
    }

    #[test]
    fn builtin_intermediate_chains_to_root() {
        let certs = ca_certs();
        let intermediate = certs.iter().find(|c| {
            c.subject_name().try_cmp(c.issuer_name()).ok() != Some(std::cmp::Ordering::Equal)
        });
        if let Some(ca) = intermediate {
            assert!(verify_cert_chain(ca), "中间证书应能链到内置根");
        }
    }

    // ───────────────────── repo_status：事实区字段 ─────────────────────

    /// `dirty` 的顺序必须**稳定**：决策页的勾选清单直接按它渲染，顺序抖一下用户就要重新找位置。
    ///
    /// 这条钉的是「不用显式排序也不该乱」：libgit2 的状态表由两个**已排序**的 diff 归并而来
    /// （`git_diff__paired_foreach`，见 libgit2 的 status.c），只有开重命名检测时才需要
    /// `SORT_CASE_*` 标志。哪天这里红了，说明上游行为变了 —— 那时在 `repo_status` 里补一次显式排序。
    #[test]
    fn repo_status_dirty_keeps_path_order() {
        let dir = std::env::temp_dir().join(format!("bb-repo-status-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        // repo_status 需要 HEAD：先落一个初始提交，再乱序制造三个未跟踪文件
        let repo = git2::Repository::init(&dir).unwrap();
        std::fs::write(dir.join("seed.txt"), "seed").unwrap();
        let mut index = repo.index().unwrap();
        index.add_path(std::path::Path::new("seed.txt")).unwrap();
        index.write().unwrap();
        let tree = repo.find_tree(index.write_tree().unwrap()).unwrap();
        let sig = git2::Signature::now("t", "t@example.com").unwrap();
        repo.commit(Some("HEAD"), &sig, &sig, "init", &tree, &[]).unwrap();
        for name in ["z.txt", "a.txt", "m.txt"] {
            std::fs::write(dir.join(name), "x").unwrap();
        }

        let json = repo_status(dir.to_str().unwrap()).unwrap();
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        let paths: Vec<&str> = value["dirty"]
            .as_array()
            .expect("dirty 应是数组")
            .iter()
            .map(|e| e["path"].as_str().unwrap_or_default())
            .collect();
        assert_eq!(paths, vec!["a.txt", "m.txt", "z.txt"], "dirty 应按路径稳定排序");
        assert_eq!(value["has_upstream"], serde_json::json!(false), "新仓库没有上游");

        let _ = std::fs::remove_dir_all(&dir);
    }

    // ───────────── repo_status：只增的三个「前提事实」字段 ─────────────

    /// 建一个带初始提交的临时仓库。`repo_status` 需要 HEAD 才能工作（见上一条测试）。
    ///
    /// 目录名带 `tag`：cargo 单测在同进程内并行跑，只用 pid 会撞车。
    fn init_repo_with_initial_commit(tag: &str) -> (std::path::PathBuf, git2::Repository) {
        let dir = std::env::temp_dir().join(format!("bb-repo-status-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        let repo = git2::Repository::init(&dir).unwrap();
        std::fs::write(dir.join("seed.txt"), "seed").unwrap();
        // index/tree 借用 repo，先关在块里再返回 repo（git2::Tree 的 Drop 会用到 repo）
        {
            let mut index = repo.index().unwrap();
            index.add_path(std::path::Path::new("seed.txt")).unwrap();
            index.write().unwrap();
            let tree = repo.find_tree(index.write_tree().unwrap()).unwrap();
            let sig = git2::Signature::now("t", "t@example.com").unwrap();
            repo.commit(Some("HEAD"), &sig, &sig, "init", &tree, &[]).unwrap();
        }
        (dir, repo)
    }

    /// 读一次 `repo_status` 并解析成 JSON。
    fn status_json(dir: &std::path::Path) -> serde_json::Value {
        let json = repo_status(dir.to_str().unwrap()).unwrap();
        serde_json::from_str(&json).expect("repo_status 应返回 JSON 对象")
    }

    /// `has_parent` / `head_sha`：撤销上一次提交（`reset_soft` = `HEAD~1`）在**第一个提交**上
    /// 必然失败，UI 要靠 `has_parent` 提前说明，而不是报「引擎不可用」。
    #[test]
    fn repo_status_reports_parent_and_full_head_sha() {
        let (dir, repo) = init_repo_with_initial_commit("fields");

        // ① 初始提交：无父；head_sha = HEAD 提交的**完整** sha
        let first = status_json(&dir);
        assert_eq!(first["has_parent"], serde_json::json!(false), "初始提交没有父提交");
        let first_head = repo.head().unwrap().target().unwrap().to_string();
        assert_eq!(
            first["head_sha"].as_str().unwrap_or_default(),
            first_head,
            "head_sha 应与 repo.head().target() 一致"
        );
        assert_eq!(first_head.len(), 40, "head_sha 应是完整 sha，而不是 7 位短 sha");
        assert!(!first["head_sha"].as_str().unwrap_or_default().is_empty(), "head_sha 不应为空串");

        // ② 再落一个提交：有父；sha 跟着 HEAD 走
        std::fs::write(dir.join("second.txt"), "second").unwrap();
        let mut index = repo.index().unwrap();
        index.add_path(std::path::Path::new("second.txt")).unwrap();
        index.write().unwrap();
        let tree = repo.find_tree(index.write_tree().unwrap()).unwrap();
        let parent = repo.head().unwrap().peel_to_commit().unwrap();
        let sig = git2::Signature::now("t", "t@example.com").unwrap();
        repo.commit(Some("HEAD"), &sig, &sig, "second", &tree, &[&parent]).unwrap();

        let second = status_json(&dir);
        assert_eq!(second["has_parent"], serde_json::json!(true), "第二个提交有父提交");
        let second_head = repo.head().unwrap().target().unwrap().to_string();
        assert_eq!(second["head_sha"].as_str().unwrap_or_default(), second_head);
        assert_ne!(second["head_sha"], first["head_sha"], "head_sha 应跟随 HEAD 变化");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// `has_remote_ref`：只认 `refs/remotes/origin/{branch}` **这个 ref**，不认「配了 origin」——
    /// `reset_hard_to_remote` 查的正是这个 ref，没有就报「找不到 ref」。
    #[test]
    fn repo_status_remote_ref_flag_follows_origin_ref() {
        let (dir, repo) = init_repo_with_initial_commit("remote-ref");

        // ① 连 origin 都没有
        let bare = status_json(&dir);
        assert_eq!(bare["has_remote_ref"], serde_json::json!(false), "无 origin 时不应报有远端 ref");

        // ② 有 origin 配置但没有 remote-tracking ref（本地新建的仓库就是这样）
        repo.remote("origin", "https://example.com/x.git").unwrap();
        let no_ref = status_json(&dir);
        assert_eq!(
            no_ref["has_remote_ref"],
            serde_json::json!(false),
            "只有 origin 配置、没有 remote-tracking ref 时应为 false"
        );
        assert_eq!(
            no_ref["remote_url"].as_str().unwrap_or_default(),
            "https://example.com/x.git",
            "remote_url 与 has_remote_ref 是两件事：前者有 URL 不等于后者为 true"
        );

        // ③ fetch（或 clone）过之后 ref 出现 → true
        let branch = no_ref["branch"].as_str().unwrap_or_default().to_string();
        assert!(!branch.is_empty(), "初始提交应落在某个分支上，否则这条测试没有意义");
        let head_oid = repo.head().unwrap().target().unwrap();
        repo.reference(&format!("refs/remotes/origin/{branch}"), head_oid, true, "test")
            .unwrap();
        let with_ref = status_json(&dir);
        assert_eq!(
            with_ref["has_remote_ref"],
            serde_json::json!(true),
            "refs/remotes/origin/{branch} 存在时应为 true"
        );

        // 字段只增：老键名与类型都不能动（Kotlin 侧 parseGitStatus 依赖它们）
        for key in ["branch", "ahead", "behind", "has_upstream", "remote_url", "dirty", "unpushed"] {
            assert!(with_ref.get(key).is_some(), "老字段 {key} 不能消失");
        }
        assert!(with_ref["branch"].is_string(), "branch 应仍是字符串");
        assert!(with_ref["ahead"].is_u64() && with_ref["behind"].is_u64(), "ahead/behind 应仍是数字");
        assert!(with_ref["has_upstream"].is_boolean(), "has_upstream 应仍是布尔");
        assert!(with_ref["dirty"].is_array() && with_ref["unpushed"].is_array(), "dirty/unpushed 应仍是数组");
        assert!(with_ref["head_sha"].is_string(), "head_sha 应是字符串");
        assert!(with_ref["has_parent"].is_boolean() && with_ref["has_remote_ref"].is_boolean());

        // ④ 符号引用：ref **存在**但取不到 target —— 正是「只判存在」会漏掉的那种。
        // reset_hard_to_remote 在这条 ref 上同样会失败（`target()` → 「远端引用无目标」），
        // 所以预检必须报 false，否则用户点下去才炸。
        // 目标故意指向不存在的 ref，把「悬挂符号引用」这个最坏形状也一起盖上。
        let ref_name = format!("refs/remotes/origin/{branch}");
        repo.reference_symbolic(&ref_name, "refs/remotes/origin/does-not-exist", true, "test")
            .unwrap();
        // 先自证这条用例真的走在「符号引用」分支上，而不是退化成「ref 不存在」：
        assert!(
            repo.find_reference(&ref_name).is_ok(),
            "符号引用也应能被 find_reference 找到，否则这条用例没测到符号引用分支"
        );
        assert!(
            repo.find_reference(&ref_name).unwrap().target().is_none(),
            "符号引用没有直接 target"
        );

        let symbolic = status_json(&dir);
        assert_eq!(
            symbolic["has_remote_ref"],
            serde_json::json!(false),
            "ref 存在但解析不出 target 时应为 false（与 reset_hard_to_remote 的真实前提一致）"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    // ───────────── clone 目标目录预检 / 失败清场 / 错误归一 ─────────────

    /// 独立的临时目录（cargo 单测同进程并行，目录名必须带 tag 区分）。
    fn temp_dir(tag: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("bb-clone-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    /// 目录不存在时预检放行，且**不创建**任何东西（创建是 libgit2 的事）。
    #[test]
    fn clone_target_prepare_allows_missing_dir() {
        let dir = temp_dir("missing");
        assert_eq!(prepare_clone_target(dir.to_str().unwrap()).unwrap(), 0);
        assert!(!dir.exists(), "预检不该顺手把目录建出来");
    }

    /// 空目录 / 只有零散文件的半成品目录：清掉重建 ——
    /// 否则用户永远卡在 libgit2 那句 `exists and is not an empty directory` 上。
    #[test]
    fn clone_target_prepare_clears_partial_dir() {
        let dir = temp_dir("partial");
        std::fs::create_dir_all(dir.join("nested")).unwrap();
        std::fs::write(dir.join("nested/leftover.txt"), "x").unwrap();
        assert_eq!(prepare_clone_target(dir.to_str().unwrap()).unwrap(), 1);
        assert!(!dir.exists(), "半成品目录应被整体删除");
    }

    /// 含 `.git` 的目录是**另一个仓库**：预检必须拒绝，而且一个字节都不能动。
    #[test]
    fn clone_target_prepare_refuses_existing_repo() {
        let dir = temp_dir("has-git");
        std::fs::create_dir_all(dir.join(".git")).unwrap();
        std::fs::write(dir.join(".git/HEAD"), "ref: refs/heads/main\n").unwrap();
        std::fs::write(dir.join("keep.txt"), "未推送的工作区改动").unwrap();

        let err = prepare_clone_target(dir.to_str().unwrap()).unwrap_err();
        assert!(
            other_message(err).contains("已经有一个仓库"),
            "应给出「目录里已有仓库」的可读原因"
        );
        assert!(dir.join(".git/HEAD").exists(), "拒绝之后仓库必须原样保留");
        assert!(dir.join("keep.txt").exists(), "工作区文件不能被预检碰掉");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 失败清场：把这次 clone 留下的目录整体删掉（下一次才能从零开始）。
    #[test]
    fn clone_failure_cleanup_removes_partial_repo() {
        let dir = temp_dir("cleanup");
        std::fs::create_dir_all(dir.join(".git/objects")).unwrap();
        std::fs::write(dir.join(".git/objects/tmp_pack_x"), "half").unwrap();
        assert_eq!(discard_partial_clone(dir.to_str().unwrap()), 1);
        assert!(!dir.exists(), "失败后不该留半个仓库");
        // 目录本来就不存在时是空操作
        assert_eq!(discard_partial_clone(dir.to_str().unwrap()), 0);
    }

    /// 锁文件错误：**完整路径必须留在文案里**（日志里那一行是唯一定位线索），
    /// 并附带一句用户能照做的处置。
    #[test]
    fn clone_error_keeps_lock_path_and_reports_scene() {
        // 真机 1.0.90 日志里的原文（HEAD.lock 那一条）
        let raw = "failed to lock file '/storage/emulated/0/Android/data/com.branchbase/files/repos/SunsetRNE/Branchbase-Android/.git/HEAD.lock' for writing";
        let text = other_message(map_clone_error(&git2::Error::from_str(raw), &[]));
        assert!(
            text.contains("/repos/SunsetRNE/Branchbase-Android/.git/HEAD.lock"),
            "路径不能被截断或改写: {text}"
        );
        assert!(text.contains("现场没有找到锁文件"), "空清单要如实说「没有锁文件」: {text}");
    }

    /// 现场真的删到了锁文件时，清单要进文案 —— 这一行决定了「重试有没有用」。
    #[test]
    fn clone_error_lists_cleaned_locks() {
        let raw = "failed to lock file '/tmp/r/.git/HEAD.lock' for writing";
        let cleaned = vec!["HEAD.lock".to_string(), "refs/heads/main.lock".to_string()];
        let text = other_message(map_clone_error(&git2::Error::from_str(raw), &cleaned));
        assert!(text.contains("已清掉 2 个残留锁文件"), "实际: {text}");
        assert!(text.contains("HEAD.lock"), "清单要能看出是哪个文件: {text}");
        assert!(text.contains("refs/heads/main.lock"), "实际: {text}");
    }

    /// 清锁只清锁：非锁文件、对象库、以及「叫 x.lock 的目录」都不许碰。
    #[test]
    fn clear_stale_locks_only_removes_lock_files() {
        let dir = temp_dir("locks");
        std::fs::create_dir_all(dir.join(".git/refs/heads")).unwrap();
        std::fs::create_dir_all(dir.join(".git/objects/pack")).unwrap();
        std::fs::create_dir_all(dir.join(".git/lfs")).unwrap();
        std::fs::create_dir_all(dir.join(".git/weird.lock")).unwrap();

        std::fs::write(dir.join(".git/HEAD.lock"), "ref: refs/heads/main\n").unwrap();
        std::fs::write(dir.join(".git/refs/heads/main.lock"), "").unwrap();
        std::fs::write(dir.join(".git/config"), "[core]\n").unwrap();
        std::fs::write(dir.join(".git/objects/pack/tmp.lock"), "对象库里的同名文件").unwrap();
        std::fs::write(dir.join(".git/lfs/x.lock"), "lfs 不扫").unwrap();
        std::fs::write(dir.join(".git/weird.lock/inner.txt"), "目录不是锁").unwrap();

        let removed = clear_stale_locks(dir.to_str().unwrap());
        assert_eq!(removed, vec!["HEAD.lock".to_string(), "refs/heads/main.lock".to_string()]);
        assert!(!dir.join(".git/HEAD.lock").exists());
        assert!(!dir.join(".git/refs/heads/main.lock").exists());
        assert!(dir.join(".git/config").exists(), "非锁文件不能被删");
        assert!(dir.join(".git/objects/pack/tmp.lock").exists(), "对象库要跳过");
        assert!(dir.join(".git/lfs/x.lock").exists(), "lfs 要跳过");
        assert!(dir.join(".git/weird.lock/inner.txt").exists(), "目录不是锁文件，不能被删");

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 没有 `.git` 时是空操作（clone 目标目录本来就还没有仓库）。
    #[test]
    fn clear_stale_locks_is_noop_without_git_dir() {
        let dir = temp_dir("no-git");
        std::fs::create_dir_all(&dir).unwrap();
        assert!(clear_stale_locks(dir.to_str().unwrap()).is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// 其余失败原样透出（只加 `clone 失败: ` 前缀），不做二次解释。
    #[test]
    fn clone_error_keeps_other_failures_verbatim() {
        let text = other_message(map_clone_error(
            &git2::Error::from_str("authentication required but no callback set"),
            &[],
        ));
        assert_eq!(text, "clone 失败: authentication required but no callback set");
    }

    /// 取消：回调中断之后 libgit2 只会给一句笼统的失败，
    /// 上层要能区分「用户取消」与「真的失败」（前者不该报红字错误）。
    #[test]
    fn clone_error_reports_cancel() {
        CANCEL_REQUESTED.store(true, Ordering::SeqCst);
        let text = other_message(map_clone_error(&git2::Error::from_str("callback returned non-zero"), &[]));
        CANCEL_REQUESTED.store(false, Ordering::SeqCst);
        assert_eq!(text, "clone 已取消");
    }

    /// 取消标记的生命周期：`clone_repo` 开始时清、请求时置位。
    #[test]
    fn cancel_flag_round_trip() {
        CANCEL_REQUESTED.store(false, Ordering::SeqCst);
        assert!(!cancelled());
        request_cancel();
        assert!(cancelled());
        CANCEL_REQUESTED.store(false, Ordering::SeqCst);
    }

    // ───────────────────── 阶段 4：加深克隆 ─────────────────────
    //
    // 说清这一组**测不到**什么：libgit2 的 local transport 不支持 depth
    // （`transports/local.c` 的 `local_shallow_roots` 直接返回空、下载时也不看 depth），
    // 所以「从本地路径浅 clone 出 `.git/shallow`」这条路在单测里造不出来 ——
    // 真的浅克隆只会出现在 HTTP / git://（GitHub、git daemon）上。这里因此**手工写下浅边界**，
    // 钉的是加深之后那条界面依赖的性质：**边界消失、全史可走**。
    //
    // 「真浅 clone」那条路在 `core/tests/git_shallow_repair.rs`：它起一个只监听 127.0.0.1 的
    // `git daemon`，让 `clone_repo` 的 `depth(1)` 真的生效，从而复现真机事故
    // （`object not found - no match for id`）并验自愈。

    /// 没有 origin 的仓库：加深这件事本身不成立，要如实报错而不是 panic。
    #[test]
    fn fetch_deepen_没有_origin_时如实报错() {
        let (dir, _repo) = temp_repo("deepen-no-origin");
        let err = fetch_deepen(dir.to_str().unwrap(), 0, None).unwrap_err();
        let text = other_message(err);
        assert!(text.contains("找不到 origin"), "实际：{text}");
    }

    /// 全量加深：远端的历史都到本地，浅边界（`.git/shallow`）消失。
    #[test]
    fn fetch_deepen_全量之后浅边界消失且历史完整() {
        // 远端：3 个提交（用本地路径当远端 —— 与其它 clone 测试同一手法）
        let (origin_dir, origin) = temp_repo("deepen-origin");
        let first = commit_file(&origin, "a.txt", "one\n", "第一个提交", "Alice");
        let second = commit_file(&origin, "a.txt", "two\n", "第二个提交", "Bob");
        let tip = commit_file(&origin, "a.txt", "three\n", "第三个提交", "Carol");

        // 克隆（local transport 会连全史一起搬过来）
        let into = std::env::temp_dir().join(format!("bb-git-deepen-clone-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&into);
        clone_repo(origin_dir.to_str().unwrap(), into.to_str().unwrap(), None, None).unwrap();

        // 手工造出浅边界：`.git/shallow` 里写上 tip —— 这正是 `depth(1)` clone 的样子
        let shallow = into.join(".git").join("shallow");
        std::fs::write(&shallow, format!("{tip}\n")).unwrap();
        assert!(shallow.exists());

        fetch_deepen(into.to_str().unwrap(), 0, None).unwrap();

        // ① 浅边界没了（界面据此把提交图切回本地来源：留着就一直走 REST）
        assert!(!shallow.exists(), "加深之后 .git/shallow 必须被删掉");
        // ② 历史完整：加深不改写提交，前两个提交一个不少
        let json = log_graph(into.to_str().unwrap(), 10, 0).unwrap();
        let arr: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        assert_eq!(arr.len(), 3, "加深后应能走完整史：{json}");
        assert_eq!(arr[0]["sha"], tip.to_string());
        assert_eq!(arr[1]["sha"], second.to_string());
        assert_eq!(arr[2]["sha"], first.to_string());
    }

    /// 加深是**幂等**的：已经全量的仓库再加深一次不该报错、也不该多出东西。
    #[test]
    fn fetch_deepen_已全量时再跑一次也无害() {
        let (origin_dir, origin) = temp_repo("deepen-idempotent-origin");
        commit_file(&origin, "a.txt", "one\n", "只有一次", "Alice");
        let into = std::env::temp_dir().join(format!("bb-git-deepen-idem-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&into);
        clone_repo(origin_dir.to_str().unwrap(), into.to_str().unwrap(), None, None).unwrap();

        fetch_deepen(into.to_str().unwrap(), 0, None).unwrap();
        fetch_deepen(into.to_str().unwrap(), 0, None).unwrap();

        assert!(!into.join(".git").join("shallow").exists());
        let json = log_graph(into.to_str().unwrap(), 10, 0).unwrap();
        let arr: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        assert_eq!(arr.len(), 1);
    }

    /// 缺了祖先对象、又没有任何浅边界声明的仓库：`repair_shallow_boundary` 要把**缺父的那条
    /// 提交**补成浅边界。
    ///
    /// 这就是真机上那个「事实浅、声明不浅」的形状：本地有 tip，tip 的祖先没了，
    /// 而 `.git/shallow` 不在 —— 于是仓库自称完整（`is_shallow() == false`），界面切回本地来源，
    /// 下一次 fetch 又在同一个对象上判死。补完边界之后仓库重新自称浅，界面转 REST + 出现加深入口。
    #[test]
    fn repair_shallow_boundary_把缺父的提交补成浅边界() {
        let (dir, repo) = temp_repo("shallow-repair");
        let first = commit_file(&repo, "a.txt", "one\n", "第一个提交", "Alice");
        let second = commit_file(&repo, "a.txt", "two\n", "第二个提交", "Bob");
        let tip = commit_file(&repo, "a.txt", "three\n", "第三个提交", "Carol");
        drop(repo);

        // 抹掉根提交的 loose object：现在 second 的父不在本地，而没人声明过浅边界
        let text = first.to_string();
        let object = dir
            .join(".git")
            .join("objects")
            .join(&text[..2])
            .join(&text[2..]);
        assert!(object.exists(), "根提交应当是 loose object：{object:?}");
        std::fs::remove_file(&object).unwrap();
        let shallow = dir.join(".git").join("shallow");
        assert!(!shallow.exists(), "起点不能有边界声明");

        // ① 扫出 1 条新边界，写下的是**缺父的那条提交**（second），不是缺的那个对象
        assert_eq!(repair_shallow_boundary(dir.to_str().unwrap()).unwrap(), 1);
        assert_eq!(
            std::fs::read_to_string(&shallow).unwrap(),
            format!("{second}\n")
        );
        // ② 边界一旦声明，仓库自称浅 —— Kotlin 侧 `isShallowClone` 读的就是这个文件
        let repo = git2::Repository::open(&dir).unwrap();
        assert!(repo.is_shallow());
        // ③ 再跑一次是幂等的：已有边界不再重写
        assert_eq!(repair_shallow_boundary_in(&repo).unwrap(), 0);
        assert_eq!(
            std::fs::read_to_string(&shallow).unwrap(),
            format!("{second}\n")
        );
        // ④ 没动 refs：HEAD 还是那条 tip
        assert_eq!(repo.head().unwrap().peel_to_commit().unwrap().id(), tip);
    }

    /// 仓库本身完整时不多写一条边界（否则界面会永远停在 REST 来源）。
    #[test]
    fn repair_shallow_boundary_完整仓库不写边界() {
        let (dir, repo) = temp_repo("shallow-repair-clean");
        let tip = commit_file(&repo, "a.txt", "one\n", "唯一提交", "Alice");

        assert_eq!(repair_shallow_boundary_in(&repo).unwrap(), 0);
        assert!(
            !dir.join(".git").join("shallow").exists(),
            "完整仓库不该多出边界声明"
        );
        assert_eq!(repo.head().unwrap().peel_to_commit().unwrap().id(), tip);
    }

    // ───────────────────── 阶段 3：本地读接口 ─────────────────────
    //
    // 这一组会真的建仓库（临时目录 + git2 直接提交），因为它们的价值全在「libgit2 到底
    // 吐出了什么」—— 纯函数测不出来：revwalk 的排序、轻量 tag 与 annotated tag 的区别、
    // diff 的行首字符，任何一条写错都只会在真机上表现成「面板空着」或「点开是错的」。

    /// 建一个临时仓库（目录名带测试名，避免并行跑时互相踩）。
    fn temp_repo(name: &str) -> (std::path::PathBuf, git2::Repository) {
        let dir = std::env::temp_dir().join(format!("bb-git-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let repo = git2::Repository::init(&dir).unwrap();
        (dir, repo)
    }

    /// 写文件 + 暂存 + 提交，返回新提交的 id（parent = 当前 HEAD，没有就提交根提交）。
    fn commit_file(
        repo: &git2::Repository,
        file: &str,
        content: &str,
        message: &str,
        author: &str,
    ) -> git2::Oid {
        std::fs::write(repo.workdir().unwrap().join(file), content).unwrap();
        let mut index = repo.index().unwrap();
        index.add_path(std::path::Path::new(file)).unwrap();
        index.write().unwrap();
        let tree_id = index.write_tree().unwrap();
        let tree = repo.find_tree(tree_id).unwrap();
        let sig = git2::Signature::now(author, &format!("{author}@example.com")).unwrap();
        let parent = repo.head().ok().and_then(|h| h.peel_to_commit().ok());
        let parents: Vec<&git2::Commit> = parent.iter().collect();
        repo.commit(Some("HEAD"), &sig, &sig, message, &tree, &parents).unwrap()
    }

    #[test]
    fn log_graph_新的在前且带_parents() {
        let (dir, repo) = temp_repo("log-graph");
        let first = commit_file(&repo, "a.txt", "one\n", "第一个提交", "Alice");
        let second = commit_file(&repo, "a.txt", "two\n", "第二个提交", "Bob");

        let json = log_graph(dir.to_str().unwrap(), 10, 0).unwrap();
        let arr: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        assert_eq!(arr.len(), 2);
        assert_eq!(arr[0]["sha"], second.to_string());
        assert_eq!(arr[0]["subject"], "第二个提交");
        assert_eq!(arr[0]["author"], "Bob");
        // 图靠 parents 画泳道：第一个提交是根，没有父
        assert_eq!(arr[0]["parents"][0], first.to_string());
        assert_eq!(arr[1]["sha"], first.to_string());
        assert_eq!(arr[1]["parents"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn log_graph_分页与空仓库() {
        let (dir, repo) = temp_repo("log-graph-page");
        for i in 0..3 {
            commit_file(&repo, "a.txt", &format!("{i}\n"), &format!("c{i}"), "Alice");
        }
        let page1 = log_graph(dir.to_str().unwrap(), 2, 0).unwrap();
        let page2 = log_graph(dir.to_str().unwrap(), 2, 2).unwrap();
        let a1: Vec<serde_json::Value> = serde_json::from_str(&page1).unwrap();
        let a2: Vec<serde_json::Value> = serde_json::from_str(&page2).unwrap();
        assert_eq!(a1.len(), 2);
        assert_eq!(a2.len(), 1);
        assert_ne!(a1[0]["sha"], a2[0]["sha"], "第二页不能重复第一页");

        // 空仓库：没有 HEAD 也要给空数组，而不是报错（「还没有提交」是正常状态）
        let empty = temp_repo("log-graph-empty");
        let json = log_graph(empty.0.to_str().unwrap(), 10, 0).unwrap();
        assert_eq!(json, "[]");
    }

    /// 造一个上游：`origin` 远端 + `refs/remotes/origin/{branch}` + 分支的 upstream 配置。
    ///
    /// **三样缺一不可**（缺了不报错，只是 `Branch::upstream()` 给 NotFound —— 于是测的就变成
    /// 「没有上游」那条分支，而断言照样可能绿：假绿比红更坏）：
    /// 配置说「上游在 origin」，而 libgit2 解析上游名时会**先查远端存不存在**
    /// （实测报 `remote 'origin' does not exist`），最后才是那条远端引用本身。
    fn set_upstream(repo: &git2::Repository, branch: &str, oid: git2::Oid) {
        repo.remote("origin", "https://example.com/owner/repo.git").unwrap();
        repo.reference(&format!("refs/remotes/origin/{branch}"), oid, true, "测试造的上游")
            .unwrap();
        let mut cfg = repo.config().unwrap();
        cfg.set_str(&format!("branch.{branch}.remote"), "origin").unwrap();
        cfg.set_str(&format!("branch.{branch}.merge"), &format!("refs/heads/{branch}"))
            .unwrap();
    }

    #[test]
    fn log_graph_只有没推送到上游的提交带标记() {
        let (dir, repo) = temp_repo("log-graph-unpushed");
        let pushed = commit_file(&repo, "a.txt", "one\n", "已推送", "Alice");
        let local = commit_file(&repo, "a.txt", "two\n", "还没推", "Bob");
        // 分支名不写死 master / main：libgit2 认 `init.defaultBranch`，写死会在别的环境上假绿
        let branch = repo.head().unwrap().shorthand().unwrap().to_string();
        set_upstream(&repo, &branch, pushed);

        let json = log_graph(dir.to_str().unwrap(), 10, 0).unwrap();
        let arr: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        assert_eq!(arr.len(), 2);
        assert_eq!(arr[0]["sha"], local.to_string());
        assert_eq!(arr[0]["unpushed"], true, "HEAD 那条在上游里没有，必须标出来");
        assert_eq!(arr[1]["unpushed"], false, "上游里已有的提交不许标");

        // 与工作区档的「待推送 N」同源：两个数字必须相等
        // （口径分家的话，面板上会同时出现「待推送 1」和一张一个标记都没有的图）
        let status: serde_json::Value =
            serde_json::from_str(&repo_status(dir.to_str().unwrap()).unwrap()).unwrap();
        let marked = arr.iter().filter(|v| v["unpushed"] == true).count();
        assert_eq!(status["ahead"].as_u64().unwrap() as usize, marked);
        assert_eq!(marked, 1);
    }

    #[test]
    fn log_graph_没有上游时一条都不标() {
        let (dir, repo) = temp_repo("log-graph-no-upstream");
        commit_file(&repo, "a.txt", "one\n", "c1", "Alice");
        commit_file(&repo, "a.txt", "two\n", "c2", "Alice");

        // 没有 upstream 配置：**不标**。全都标上等于每行都在喊同一件事（「未推送」就没有信息量了），
        // 而且工作区档那时写的是「已同步」—— 两处对不上
        let json = log_graph(dir.to_str().unwrap(), 10, 0).unwrap();
        let arr: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        assert_eq!(arr.len(), 2);
        assert!(arr.iter().all(|v| v["unpushed"] == false), "没有上游时不许标：{arr:?}");

        // 上游 == HEAD（没有本地提交）：同样一条都不标
        let branch = repo.head().unwrap().shorthand().unwrap().to_string();
        let head = repo.head().unwrap().peel_to_commit().unwrap().id();
        set_upstream(&repo, &branch, head);
        let json = log_graph(dir.to_str().unwrap(), 10, 0).unwrap();
        let arr: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        assert!(arr.iter().all(|v| v["unpushed"] == false), "上游就是 HEAD：{arr:?}");
    }

    #[test]
    fn list_tags_annotated_全字段_轻量留空() {
        let (_dir, repo) = temp_repo("list-tags");
        let oid = commit_file(&repo, "a.txt", "one\n", "带 tag 的提交", "Alice");
        let object = repo.find_object(oid, None).unwrap();
        let sig = git2::Signature::now("Tagger", "tagger@example.com").unwrap();
        repo.tag("v1.0", &object, &sig, "第一个版本\n", false).unwrap();
        repo.tag_lightweight("nightly", &object, false).unwrap();

        let dir = repo.workdir().unwrap().to_str().unwrap().to_string();
        let json = list_tags(&dir).unwrap();
        let arr: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        assert_eq!(arr.len(), 2);
        // 按名字排序：nightly < v1.0
        assert_eq!(arr[0]["name"], "nightly");
        // 轻量 tag：**不填假值** —— tagger 为 null、说明为空串
        assert_eq!(arr[0]["annotated"], false);
        assert_eq!(arr[0]["sha"], oid.to_string());
        assert_eq!(arr[0]["target_sha"], oid.to_string());
        assert!(arr[0]["tagger"].is_null());
        assert_eq!(arr[0]["message"], "");
        // annotated：sha 是 tag 对象、target_sha 是被指的提交、tagger 与说明都在
        assert_eq!(arr[1]["annotated"], true);
        assert_eq!(arr[1]["target_sha"], oid.to_string());
        assert_ne!(arr[1]["sha"], oid.to_string());
        assert_eq!(arr[1]["tagger"]["name"], "Tagger");
        assert_eq!(arr[1]["tagger"]["email"], "tagger@example.com");
        assert!(arr[1]["tagger"]["time"].as_str().unwrap().contains('T'));
        assert!(arr[1]["message"].as_str().unwrap().contains("第一个版本"));
    }

    #[test]
    fn log_file_只列碰过该路径的提交() {
        let (dir, repo) = temp_repo("log-file");
        let only_a = commit_file(&repo, "a.txt", "one\n", "改 a", "Alice");
        commit_file(&repo, "b.txt", "bee\n", "加 b", "Alice");
        let again_a = commit_file(&repo, "a.txt", "two\n", "再改 a", "Bob");

        let json = log_file(dir.to_str().unwrap(), "a.txt", 10, 0).unwrap();
        let arr: Vec<serde_json::Value> = serde_json::from_str(&json).unwrap();
        let shas: Vec<&str> = arr.iter().map(|v| v["sha"].as_str().unwrap()).collect();
        assert_eq!(shas, vec![again_a.to_string().as_str(), only_a.to_string().as_str()]);
        assert_eq!(arr[0]["author"], "Bob");
        // 不碰 b.txt 的那个提交必须被跳过（碰过的才留下）
        assert!(arr.iter().all(|v| v["subject"] != "加 b"));

        // 路径为空是**调用错误**（不是「没有历史」），要报出来
        assert!(log_file(dir.to_str().unwrap(), "  ", 10, 0).is_err());
    }

    #[test]
    fn diff_worktree_带行首语义与逐文件统计() {
        let (dir, repo) = temp_repo("diff-worktree");
        commit_file(&repo, "a.txt", "one\ntwo\n", "初次提交", "Alice");
        std::fs::write(dir.join("a.txt"), "one\nTWO\nthree\n").unwrap();

        let json = diff_worktree(dir.to_str().unwrap()).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v["truncated"], false);
        assert_eq!(v["files"][0]["path"], "a.txt");
        assert_eq!(v["files"][0]["status"], "M");
        let patch = v["patch"].as_str().unwrap();
        // 行首字符是 diff 的语义，丢了 UI 就分不出加行与删行
        assert!(patch.contains("-two"), "patch 少了删除行：{patch}");
        assert!(patch.contains("+TWO"), "patch 少了新增行：{patch}");
        assert!(v["files"][0]["additions"].as_i64().unwrap() >= 1);
        assert!(v["files"][0]["deletions"].as_i64().unwrap() >= 1);

        // 未跟踪的新文件：算 A（新增），而且**不能**把已跟踪文件也报成 A ——
        // 这一条正是 `diff_tree_to_workdir_with_index(None, …)` 那个坑的现场
        std::fs::write(dir.join("fresh.txt"), "brand new\n").unwrap();
        let json = diff_worktree(dir.to_str().unwrap()).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        let files = v["files"].as_array().unwrap();
        let status_of = |name: &str| {
            files
                .iter()
                .find(|f| f["path"] == name)
                .map(|f| f["status"].as_str().unwrap().to_string())
        };
        assert_eq!(status_of("fresh.txt").as_deref(), Some("A"));
        assert_eq!(status_of("a.txt").as_deref(), Some("M"), "已跟踪的改动文件必须是 M");
    }

    /// 未跟踪文件**必须带内容**，而且 `files` 与 `patch` 两段要能**按下标对齐**。
    ///
    /// 这条钉的是界面赖以成立的两件事：
    /// 1. 默认情况下 libgit2 只把未跟踪文件列进 `files`、patch 里没有它 —— 「新增一个文件」
    ///    在面板上点开就是一片空白（`show_untracked_content(true)` 之前的行为）；
    /// 2. 「第 i 个文件对应 patch 里的第 i 段」这条不变量：上层按**下标**对齐（不解析路径，
    ///    路径里有空格 / 中文时解析 header 很脆），一旦不成立，界面会把 A 文件的差异画到 B 名下。
    #[test]
    fn diff_worktree_未跟踪文件带内容且两段按下标对齐() {
        let (dir, repo) = temp_repo("diff-untracked");
        commit_file(&repo, "keep.txt", "one\n", "初始", "Alice");
        commit_file(&repo, "mod.txt", "aaa\nbbb\nccc\n", "加 mod", "Alice");
        // 工作区：改一行（已跟踪）+ 新文件（未跟踪）+ 一个二进制文件（未跟踪）
        std::fs::write(dir.join("mod.txt"), "aaa\nBBB\nccc\nddd\n").unwrap();
        std::fs::write(dir.join("new.txt"), "全新的\n").unwrap();
        std::fs::write(dir.join("bin.dat"), [0u8, 1, 2, 3, 0, 255]).unwrap();

        let json = diff_worktree(dir.to_str().unwrap()).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        let files = v["files"].as_array().unwrap();
        assert_eq!(files.len(), 3, "三个改动都要列出来：{json}");

        // ① 未跟踪的新文件：内容以 + 行进 patch，统计也是真的行数
        let patch = v["patch"].as_str().unwrap();
        assert!(patch.contains("+全新的"), "未跟踪文件的内容必须进 patch：{patch}");
        let new_file = files.iter().find(|f| f["path"] == "new.txt").unwrap();
        assert_eq!(new_file["additions"], 1);
        assert_eq!(new_file["status"], "A");

        // ② 二进制文件：有自己的那一段（内容是「Binary files … differ」），但**没有 hunk**
        let bin_file = files.iter().find(|f| f["path"] == "bin.dat").unwrap();
        assert_eq!(bin_file["status"], "A");
        let sections: Vec<&str> = patch.split("diff --git ").skip(1).collect();
        assert_eq!(
            sections.len(),
            files.len(),
            "patch 段数必须与 files 条数一致（上层按下标对齐）：{patch}"
        );
        let bin_section = sections
            .iter()
            .find(|s| s.contains("b/bin.dat"))
            .expect("二进制文件也要有自己的一段");
        assert!(
            !bin_section.contains("@@ "),
            "二进制段不该有 hunk（界面据此显示「没有可显示的文本差异」而不是把这句话当代码行）：{bin_section}"
        );
    }

    #[test]
    fn diff_commit_拿父提交比_根提交也不报错() {
        let (dir, repo) = temp_repo("diff-commit");
        let root = commit_file(&repo, "a.txt", "one\n", "根提交", "Alice");
        let second = commit_file(&repo, "a.txt", "one\ntwo\n", "加一行", "Alice");

        let json = diff_commit(dir.to_str().unwrap(), &second.to_string()).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(v["patch"].as_str().unwrap().contains("+two"));

        // 根提交没有父：与空树比，不能报错（否则「第一个提交」永远点不开）
        let json = diff_commit(dir.to_str().unwrap(), &root.to_string()).unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(v["patch"].as_str().unwrap().contains("+one"));

        // sha 写错：明确报「无效 / 找不到」，不要静默给空 diff
        assert!(diff_commit(dir.to_str().unwrap(), "not-a-sha").is_err());
    }

    #[test]
    fn commit_time_iso_按本地偏移渲染() {
        // 2026-09-25T04:41:23+08:00 = 1789... 直接用构造值验算更清楚：
        // 取 1970-01-01T00:00:00 与 UTC 偏移
        assert_eq!(commit_time_iso(git2::Time::new(0, 0)), "1970-01-01T00:00:00+00:00");
        // 同一时刻、+08:00：钟面要往前推 8 小时
        assert_eq!(commit_time_iso(git2::Time::new(0, 480)), "1970-01-01T08:00:00+08:00");
        // 负偏移（西半球）不能把符号吞掉
        assert_eq!(commit_time_iso(git2::Time::new(0, -300)), "1969-12-31T19:00:00-05:00");
    }

    #[test]
    fn civil_from_days_对几个已知日期() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(civil_from_days(19_723), (2024, 1, 1));
        // 闰日：2024-02-29 是 1970 起的第 19782 天
        assert_eq!(civil_from_days(19_782), (2024, 2, 29));
    }

    // ───────────────────── 阶段 5：本地合并与冲突解决 ─────────────────────
    //
    // 这一组全是「libgit2 到底怎么动索引 / 工作区 / ref」的取证。merge 的四条出口
    // （已包含 / 快进 / 干净合并 / 冲突）在界面上长得都很像，只有把仓库真的建出来，
    // 才分得清「快进没产生提交」与「产生了提交但内容一样」这类区别；
    // 而「放弃合并」是不是真的回到合并前，也只有比对工作区内容才算数。

    /// 切分支（工作区跟着走）。测试里的强制检出：工作区脏了就直接覆盖，不在这里造第二套保护。
    fn switch_to(repo: &git2::Repository, name: &str) {
        let ref_name = format!("refs/heads/{name}");
        repo.set_head(&ref_name).unwrap();
        repo.checkout_head(Some(git2::build::CheckoutBuilder::new().force()))
            .unwrap();
    }

    /// 当前分支名（libgit2 认 `init.defaultBranch`，测试里**不许**写死 main / master）。
    fn current_branch(repo: &git2::Repository) -> String {
        repo.head().unwrap().shorthand().unwrap().to_string()
    }

    /// 在当前 HEAD 上开一条新分支并切过去。
    fn branch_here(repo: &git2::Repository, name: &str) {
        let head = repo.head().unwrap().peel_to_commit().unwrap();
        repo.branch(name, &head, false).unwrap();
        switch_to(repo, name);
    }

    fn read(dir: &std::path::Path, file: &str) -> String {
        std::fs::read_to_string(dir.join(file)).unwrap()
    }

    /// 调一次 `merge_branch` 并把 JSON 解出来（作者身份固定，测试不关心它）。
    fn merge(dir: &std::path::Path, branch: &str) -> serde_json::Value {
        let json = merge_branch(dir.to_str().unwrap(), branch, None, "合并者", "merge@example.com")
            .unwrap();
        serde_json::from_str(&json).unwrap()
    }

    /// 造一个「双方都改了同一个文件」的冲突仓库，返回 (目录, 主分支名, 我方内容, 对方内容)。
    fn conflict_repo(name: &str) -> (std::path::PathBuf, git2::Repository, String) {
        let (dir, repo) = temp_repo(name);
        commit_file(&repo, "a.txt", "base\n", "基线", "Alice");
        let main_branch = current_branch(&repo);
        branch_here(&repo, "feature");
        commit_file(&repo, "a.txt", "theirs\n", "对方改", "Bob");
        switch_to(&repo, &main_branch);
        commit_file(&repo, "a.txt", "ours\n", "我方改", "Alice");
        (dir, repo, main_branch)
    }

    #[test]
    fn merge_快进不产生合并提交() {
        let (dir, repo) = temp_repo("merge-ff");
        commit_file(&repo, "a.txt", "one\n", "基线", "Alice");
        let main_branch = current_branch(&repo);
        branch_here(&repo, "feature");
        let tip = commit_file(&repo, "a.txt", "two\n", "feature 的提交", "Bob");
        switch_to(&repo, &main_branch);

        let out = merge(&dir, "feature");
        assert_eq!(out["outcome"], "fast_forward");
        assert_eq!(out["head_sha"], tip.to_string());
        // HEAD 就是对方那个提交本身：**没有**多出合并提交（这是「快进」与「合并提交」的分界）
        let head = repo.head().unwrap().peel_to_commit().unwrap();
        assert_eq!(head.id(), tip);
        assert_eq!(head.parent_count(), 1);
        assert_eq!(read(&dir, "a.txt"), "two\n", "工作区要跟上");
        assert_eq!(repo.state(), git2::RepositoryState::Clean);
    }

    #[test]
    fn merge_干净合并落一个两父提交() {
        let (dir, repo) = temp_repo("merge-clean");
        commit_file(&repo, "a.txt", "one\n", "基线", "Alice");
        let main_branch = current_branch(&repo);
        branch_here(&repo, "feature");
        let theirs = commit_file(&repo, "theirs.txt", "theirs\n", "对方提交", "Bob");
        switch_to(&repo, &main_branch);
        let ours = commit_file(&repo, "ours.txt", "ours\n", "我方提交", "Alice");

        let out = merge(&dir, "feature");
        assert_eq!(out["outcome"], "merged");
        let head = repo.head().unwrap().peel_to_commit().unwrap();
        assert_eq!(head.parent_count(), 2, "合并提交必须是两父");
        // 第一父是「合到哪」、第二父是「合了谁」—— 反了的话 first-parent 历史是一场相反的合并
        assert_eq!(head.parent_id(0).unwrap(), ours);
        assert_eq!(head.parent_id(1).unwrap(), theirs);
        assert_eq!(out["head_sha"], head.id().to_string());
        assert_eq!(
            head.summary().unwrap(),
            format!("Merge branch 'feature' into {main_branch}")
        );
        // 两边的文件都在，而且合并状态已清干净
        assert!(dir.join("ours.txt").exists() && dir.join("theirs.txt").exists());
        assert_eq!(repo.state(), git2::RepositoryState::Clean);
        let st: serde_json::Value =
            serde_json::from_str(&merge_state(dir.to_str().unwrap()).unwrap()).unwrap();
        assert_eq!(st["merging"], false);
    }

    #[test]
    fn merge_已经包含对方时是_up_to_date() {
        let (dir, repo) = temp_repo("merge-uptodate");
        commit_file(&repo, "a.txt", "one\n", "基线", "Alice");
        let main_branch = current_branch(&repo);
        branch_here(&repo, "feature");
        let tip = commit_file(&repo, "a.txt", "two\n", "feature 的提交", "Bob");
        switch_to(&repo, &main_branch);
        // 先快进一次
        assert_eq!(merge(&dir, "feature")["outcome"], "fast_forward");
        // 再合一次：什么都别做（既不报错也别多一个提交）
        let out = merge(&dir, "feature");
        assert_eq!(out["outcome"], "up_to_date");
        assert_eq!(out["head_sha"], tip.to_string());
        assert_eq!(repo.head().unwrap().peel_to_commit().unwrap().id(), tip);
    }

    #[test]
    fn merge_冲突时停在合并中并列出文件() {
        let (dir, repo, main_branch) = conflict_repo("merge-conflict");

        let out = merge(&dir, "feature");
        assert_eq!(out["outcome"], "conflict", "冲突**不是错误**，是一条出口");
        assert_eq!(out["conflicts"], serde_json::json!(["a.txt"]));
        assert_eq!(out["head_sha"], repo.head().unwrap().target().unwrap().to_string());
        // 仓库停在合并中：MERGE_HEAD 在（libgit2 自己写的），工作区带冲突标记
        assert_eq!(repo.state(), git2::RepositoryState::Merge);
        let work = read(&dir, "a.txt");
        assert!(work.contains("<<<<<<<") && work.contains(">>>>>>>"), "工作区应带冲突标记：{work}");

        // merge_state 是「合并到一半被杀」之后唯一的入口（风险 ①）
        let st: serde_json::Value =
            serde_json::from_str(&merge_state(dir.to_str().unwrap()).unwrap()).unwrap();
        assert_eq!(st["merging"], true);
        assert_eq!(st["branch"], main_branch);
        assert_eq!(st["conflicts"][0]["path"], "a.txt");
        assert_eq!(st["conflicts"][0]["kind"], "both_modified");
        assert!(st["message"].as_str().unwrap().contains("Merge branch 'feature'"));
        assert!(!st["merge_head_sha"].as_str().unwrap().is_empty(), "MERGE_HEAD 要报出来");

        // repo_status 的 merging 跟着翻（面板据此给「继续 / 放弃」，而不是让用户对着标记猜）
        let status: serde_json::Value =
            serde_json::from_str(&repo_status(dir.to_str().unwrap()).unwrap()).unwrap();
        assert_eq!(status["merging"], true);
        assert_eq!(status["head_sha"], out["head_sha"]);

        // 已在合并中：不许再叠一次 —— MERGE_HEAD 被覆盖后「放弃合并」会回到错的地方
        let err = other_message(
            merge_branch(dir.to_str().unwrap(), "feature", None, "合并者", "m@example.com")
                .unwrap_err(),
        );
        assert!(err.contains("上一次合并还没结束"), "实际：{err}");
    }

    #[test]
    fn analyze_conflicts_给出三方与差异且一个字都不落盘() {
        let (dir, _repo, _) = conflict_repo("analyze-conflicts");
        assert_eq!(merge(&dir, "feature")["outcome"], "conflict");
        let before = read(&dir, "a.txt");

        let v: serde_json::Value =
            serde_json::from_str(&analyze_conflicts(dir.to_str().unwrap()).unwrap()).unwrap();
        let f = &v["files"][0];
        assert_eq!(f["path"], "a.txt");
        assert_eq!(f["kind"], "both_modified");
        assert_eq!(f["binary"], false);
        assert_eq!(f["truncated"], false);
        assert_eq!(v["truncated"], false);
        // 三方 sha 与大小：上层据此说「我方 5 字节 / 对方 7 字节」
        assert!(!f["ours_sha"].as_str().unwrap().is_empty());
        assert!(!f["theirs_sha"].as_str().unwrap().is_empty());
        assert!(!f["base_sha"].as_str().unwrap().is_empty());
        assert_eq!(f["ours_size"], 5);
        assert_eq!(f["theirs_size"], 7);
        // 冲突块就是 ours ↔ theirs 的 hunk：行首字符是语义，丢了上层就分不出加行 / 删行
        let patch = f["patch"].as_str().unwrap();
        assert!(patch.contains("-ours"), "patch 少了删除行：{patch}");
        assert!(patch.contains("+theirs"), "patch 少了新增行：{patch}");

        // 三方内容：ours / theirs 是各自那一份，worktree 是**带冲突标记**的那一份
        // （手工编辑的初值就该是它 —— 从空文本开始编辑等于让用户自己抄一遍两边）
        assert_eq!(f["ours"], "ours\n");
        assert_eq!(f["theirs"], "theirs\n");
        let worktree = f["worktree"].as_str().unwrap();
        assert!(worktree.contains("<<<<<<<"), "worktree 应是带标记的那一份：{worktree}");
        assert_eq!(f["content_truncated"], false);

        // **只读**：工作区那份（带标记的）一个字都没动 —— 预解析在弹窗出现那一刻就跑，
        // 它要是会写盘，用户还没点任何按钮仓库就已经变了
        assert_eq!(before, read(&dir, "a.txt"));

        // 不在合并中时**报错**，而不是给空数组（空数组会被读成「没有冲突」）
        let (clean, _repo) = temp_repo("analyze-no-merge");
        assert!(analyze_conflicts(clean.to_str().unwrap()).is_err());
    }

    #[test]
    fn resolve_conflict_用我方_内容进工作区且清掉冲突() {
        let (dir, repo, _) = conflict_repo("resolve-ours");
        assert_eq!(merge(&dir, "feature")["outcome"], "conflict");

        resolve_conflict(dir.to_str().unwrap(), "a.txt", "ours").unwrap();
        assert_eq!(read(&dir, "a.txt"), "ours\n", "取的是索引里那一侧，不是带标记的工作区");
        assert!(!repo.index().unwrap().has_conflicts(), "解决后该路径不该再是冲突");

        let st: serde_json::Value =
            serde_json::from_str(&merge_state(dir.to_str().unwrap()).unwrap()).unwrap();
        assert_eq!(st["conflicts"].as_array().unwrap().len(), 0);
        assert_eq!(st["merging"], true, "解决完不等于合并结束 —— 还要「提交合并」");

        // 拼错的一侧要被拒绝：静默成默认值的话，「用对方」会变成「用我方」而没人发现
        let err = other_message(resolve_conflict(dir.to_str().unwrap(), "a.txt", "their").unwrap_err());
        assert!(err.contains("ours / theirs"), "实际：{err}");
        // 已经解决过的路径也不许再「解决」一遍（那会把手工结果冲掉）
        let err = other_message(resolve_conflict(dir.to_str().unwrap(), "a.txt", "theirs").unwrap_err());
        assert!(err.contains("不在冲突清单里"), "实际：{err}");
    }

    #[test]
    fn merge_continue_落两父提交并清掉合并状态() {
        let (dir, repo, _) = conflict_repo("merge-continue");
        assert_eq!(merge(&dir, "feature")["outcome"], "conflict");

        // 还有没解决的 → 报错并给出**条数**（不是一句「失败」）
        let err = other_message(
            merge_continue(dir.to_str().unwrap(), "msg", "合并者", "m@example.com").unwrap_err(),
        );
        assert!(err.contains("还有 1 个文件没解决"), "实际：{err}");

        resolve_conflict(dir.to_str().unwrap(), "a.txt", "ours").unwrap();
        let sha = merge_continue(
            dir.to_str().unwrap(),
            "Merge branch 'feature' 的冲突已解决",
            "合并者",
            "merge@example.com",
        )
        .unwrap();
        let head = repo.head().unwrap().peel_to_commit().unwrap();
        assert_eq!(head.id().to_string(), sha);
        assert_eq!(head.parent_count(), 2);
        assert_eq!(head.summary().unwrap(), "Merge branch 'feature' 的冲突已解决");
        // 解决后的内容进了提交（不是带冲突标记的那一份）
        let tree = head.tree().unwrap();
        let entry = tree.get_name("a.txt").unwrap();
        assert_eq!(repo.find_blob(entry.id()).unwrap().content(), b"ours\n");
        // 状态清干净了：MERGE_HEAD 没了，merging 翻回 false
        assert_eq!(repo.state(), git2::RepositoryState::Clean);
        let st: serde_json::Value =
            serde_json::from_str(&merge_state(dir.to_str().unwrap()).unwrap()).unwrap();
        assert_eq!(st["merging"], false);
        assert!(st["merge_head_sha"].as_str().unwrap().is_empty());
    }

    #[test]
    fn merge_continue_不给信息时用_merge_msg() {
        let (dir, repo, main_branch) = conflict_repo("merge-continue-msg");
        assert_eq!(merge(&dir, "feature")["outcome"], "conflict");
        resolve_conflict(dir.to_str().unwrap(), "a.txt", "theirs").unwrap();
        // 空信息 = 用 libgit2 写下的 MERGE_MSG（合并提交**必须**有信息：空信息在日志里是一行空白）
        merge_continue(dir.to_str().unwrap(), "   ", "合并者", "m@example.com").unwrap();
        let head = repo.head().unwrap().peel_to_commit().unwrap();
        // libgit2 写下的 MERGE_MSG 是 `Merge branch 'feature'`（不带 into <branch>，
        // 与 git 自己的行为一致）——这里钉的是「空信息时用的是它」，不是那句具体的措辞
        assert!(
            head.summary().unwrap().contains("Merge branch 'feature'"),
            "实际：{:?}",
            head.summary()
        );
        assert!(!main_branch.is_empty());
        // 取的是对方那一侧
        let tree = head.tree().unwrap();
        let entry = tree.get_name("a.txt").unwrap();
        assert_eq!(repo.find_blob(entry.id()).unwrap().content(), b"theirs\n");
    }

    #[test]
    fn merge_abort_回到合并前且不留冲突标记() {
        let (dir, repo, _) = conflict_repo("merge-abort");
        assert_eq!(merge(&dir, "feature")["outcome"], "conflict");
        let head_before = repo.head().unwrap().target().unwrap();

        merge_abort(dir.to_str().unwrap()).unwrap();
        assert_eq!(repo.state(), git2::RepositoryState::Clean);
        assert_eq!(repo.head().unwrap().target().unwrap(), head_before, "HEAD 不许动");
        assert_eq!(read(&dir, "a.txt"), "ours\n", "工作区回到合并前（我方那一份）");
        let st: serde_json::Value =
            serde_json::from_str(&merge_state(dir.to_str().unwrap()).unwrap()).unwrap();
        assert_eq!(st["merging"], false);
        assert!(st["conflicts"].as_array().unwrap().is_empty());
        // 没有进行中的合并时：报错，而不是静默成功（静默成功会让上层以为「已经放弃了」）
        assert!(merge_abort(dir.to_str().unwrap()).is_err());
    }

    #[test]
    fn write_resolved_写手工内容并拒绝越界路径() {
        let (dir, repo, _) = conflict_repo("write-resolved");
        assert_eq!(merge(&dir, "feature")["outcome"], "conflict");

        write_resolved(dir.to_str().unwrap(), "a.txt", "手工合并的结果\n").unwrap();
        assert_eq!(read(&dir, "a.txt"), "手工合并的结果\n");
        assert!(!repo.index().unwrap().has_conflicts());

        // 路径收口：绝对路径 / `..` / `.git` 一律拒绝 —— 上层传来的字符串不能写到仓库外面去
        for bad in ["/etc/passwd", "../outside.txt", ".git/config", "  "] {
            assert!(
                write_resolved(dir.to_str().unwrap(), bad, "x").is_err(),
                "路径 {bad:?} 必须被拒绝"
            );
        }
    }

    #[test]
    fn merge_工作区脏时拒绝而不是覆盖() {
        let (dir, repo, _) = conflict_repo("merge-dirty");
        std::fs::write(dir.join("a.txt"), "还没提交的改动\n").unwrap();
        let err = other_message(
            merge_branch(dir.to_str().unwrap(), "feature", None, "合并者", "m@example.com")
                .unwrap_err(),
        );
        assert!(err.contains("未提交改动"), "实际：{err}");
        // 拒绝之后工作区必须原样：合并的前置检查要是漏了，这里就是用户改动被吞掉的地方
        assert_eq!(read(&dir, "a.txt"), "还没提交的改动\n");
        assert_eq!(repo.state(), git2::RepositoryState::Clean);
    }

    #[test]
    fn merge_浅克隆拒绝并指出去哪加深() {
        let (dir, repo) = temp_repo("merge-shallow");
        commit_file(&repo, "a.txt", "one\n", "基线", "Alice");
        branch_here(&repo, "feature");
        commit_file(&repo, "a.txt", "two\n", "feature 的提交", "Bob");
        // 造出浅边界（真机上由 depth(1) clone 产生；local transport 会把全史搬过来，所以要手工写）
        let tip = repo.head().unwrap().target().unwrap();
        std::fs::write(dir.join(".git/shallow"), format!("{tip}\n")).unwrap();
        assert!(repo.is_shallow(), "前置：这个仓库此刻应是浅克隆");

        let err = other_message(
            merge_branch(dir.to_str().unwrap(), "feature", None, "合并者", "m@example.com")
                .unwrap_err(),
        );
        // 报的是「没有共同祖先」这件事，并给出路（加深历史）—— 风险 ③ 的对策
        assert!(err.contains("浅克隆") && err.contains("加深"), "实际：{err}");
    }

    #[test]
    fn merge_目标分支只在远端时引擎自己拉一次() {
        // 远端：一个带 `from-remote` 分支的仓库（本地路径当远端 —— 与其它 clone 测试同一手法）
        let (origin_dir, origin) = temp_repo("merge-remote-origin");
        commit_file(&origin, "a.txt", "one\n", "基线", "Alice");
        let origin_main = current_branch(&origin);
        branch_here(&origin, "from-remote");
        let tip = commit_file(&origin, "b.txt", "bee\n", "只在远端的分支", "Bob");
        switch_to(&origin, &origin_main);

        let into = std::env::temp_dir().join(format!("bb-git-merge-remote-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&into);
        clone_repo(origin_dir.to_str().unwrap(), into.to_str().unwrap(), None, None).unwrap();
        let repo = git2::Repository::open(&into).unwrap();
        // 前置：本地没有这个分支。clone 会把 `refs/remotes/origin/*` 一起搬过来，
        // 所以还要把那条远端跟踪引用删掉 —— 否则这条测试根本走不到「引擎自己拉一次」
        // （local transport 与真机上的浅克隆不同，它连全史一起搬）
        assert!(repo.find_reference("refs/heads/from-remote").is_err());
        repo.find_reference("refs/remotes/origin/from-remote")
            .unwrap()
            .delete()
            .unwrap();
        assert!(repo.find_reference("refs/remotes/origin/from-remote").is_err());

        let out = merge(&into, "from-remote");
        assert_eq!(out["outcome"], "fast_forward");
        assert_eq!(out["head_sha"], tip.to_string());
        assert_eq!(read(&into, "b.txt"), "bee\n");

        // 拉不到的分支要给可读原因，而不是一句 libgit2 的英文
        let err = other_message(
            merge_branch(into.to_str().unwrap(), "根本不存在", None, "合并者", "m@example.com")
                .unwrap_err(),
        );
        assert!(err.contains("找不到分支"), "实际：{err}");
    }

    #[test]
    fn merge_显式远端名合的是远端那条而不是同名的本地分支() {
        // 分叉现场：本地 main 与 origin/main 都有对方没有的提交，**两边同名**。
        // 这条测试盯着 `resolve_merge_target` 的顺序 —— 先查本地分支的话，
        // `origin/main` 会被解析成本地那条（HEAD 自己），结果是「点了合并没有任何动静」
        let (dir, repo) = temp_repo("merge-explicit-remote");
        commit_file(&repo, "a.txt", "base\n", "基线", "Alice");
        let main_branch = current_branch(&repo);

        // 远端那一条：在临时分支上造出来，再挂到 refs/remotes/origin/{main}
        branch_here(&repo, "remote-side");
        let remote_tip = commit_file(&repo, "b.txt", "remote\n", "远端提交", "Bob");
        switch_to(&repo, &main_branch);
        commit_file(&repo, "c.txt", "local\n", "本地提交", "Alice");
        set_upstream(&repo, &main_branch, remote_tip);
        // 前置：远端跟踪引用与本地分支**同名** —— 这正是短名会撞车的现场
        assert!(repo
            .find_reference(&format!("refs/remotes/origin/{main_branch}"))
            .is_ok());
        assert!(repo
            .find_reference(&format!("refs/heads/{main_branch}"))
            .is_ok());

        let out = merge(&dir, &format!("origin/{main_branch}"));
        assert_eq!(out["outcome"], "merged", "两边各有提交 → 三方合并，实际：{out}");
        let head = repo.head().unwrap().peel_to_commit().unwrap();
        assert_eq!(head.parent_count(), 2, "合并提交必须是两个父");
        assert_eq!(read(&dir, "b.txt"), "remote\n", "远端那条的内容要真的合进来");
        assert_eq!(read(&dir, "c.txt"), "local\n", "本地那条也不能丢");

        // 反例（这条就是上面那一步存在的理由）：短名解析到的是**本地**那条分支 = HEAD 自己，
        // 于是 merge_analysis 判 up_to_date —— 不报错、也不做任何事
        let noop = merge(&dir, &main_branch);
        assert_eq!(noop["outcome"], "up_to_date", "短名合自己必然是 up_to_date");
    }

    #[test]
    fn merge_带斜杠的本地分支不会被当成远端引用() {
        // `feature/x` 这种本地分支名要走「本地分支」那条路 —— 显式远端形态的查找
        // 不能把带斜杠的短名抢过去（抢过去的表现就是「找不到远端分支 feature/x」）
        let (dir, repo) = temp_repo("merge-slash-local");
        commit_file(&repo, "a.txt", "one\n", "基线", "Alice");
        let main_branch = current_branch(&repo);
        branch_here(&repo, "feature/x");
        let tip = commit_file(&repo, "b.txt", "two\n", "分支上的提交", "Bob");
        switch_to(&repo, &main_branch);

        let out = merge(&dir, "feature/x");
        assert_eq!(out["outcome"], "fast_forward");
        assert_eq!(out["head_sha"], tip.to_string());
    }

    #[test]
    fn merge_显式远端名拉不到时说的是远端找不到() {
        // 失败原因要指对地方：写错远端名时，用户该知道「远端没有这个名字」，
        // 而不是被引去本地找一条本来就不该存在的分支。
        // 这里**不造 origin**：造了就会真的去 fetch（测试不该发网络请求）
        let (dir, repo) = temp_repo("merge-explicit-missing");
        commit_file(&repo, "a.txt", "one\n", "基线", "Alice");

        let err = other_message(
            merge_branch(
                dir.to_str().unwrap(),
                "origin/根本不存在",
                None,
                "合并者",
                "m@example.com",
            )
            .unwrap_err(),
        );
        assert!(err.contains("找不到远端分支 origin/根本不存在"), "实际：{err}");
        assert!(err.contains("没有 origin 远端"), "实际：{err}");
    }
}
