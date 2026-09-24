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
/// 宁可报错交给用户决定，也不替他把可能还有未推送提交的目录删掉。
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
            discard_partial_clone(into);
            Err(map_clone_error(&e))
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

/// clone 失败归一：保留 libgit2 原文（诊断全靠它），只对**残留锁文件**这一句
/// 追加一句用户能照做的处置。
///
/// 为什么单挑这一句：`failed to lock file '<path>' for writing` 的字面意思
/// （「加锁失败」）对用户零信息量，而它的真身是「`<path>.lock` 这个残留文件还在」——
/// 上一次 git 操作被系统掐掉（进程冻结 / 低内存回收 / 用户强停）就会留下它，
/// 而 libgit2 **自己不会清理**，于是每一次操作都以同一句话失败。
/// 路径原文一个字都不能省：日志包里的那一行是唯一能定位到具体锁文件的线索。
fn map_clone_error(e: &git2::Error) -> CoreError {
    let msg = e.message();
    if msg.contains("failed to lock file") {
        return CoreError::Other(format!(
            "clone 失败: 仓库目录里有残留锁文件（上一次操作被中断留下的）：\
             请重试一次；若仍然失败，请把这条错误连同路径一起反馈。原始错误: {msg}"
        ));
    }
    if cancelled() {
        return CoreError::Other("clone 已取消".to_string());
    }
    CoreError::Other(format!("clone 失败: {msg}"))
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
    use git2::{FetchOptions, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;

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

    let mut fo = FetchOptions::new();
    fo.remote_callbacks(callbacks);
    remote
        .fetch(&["refs/heads/*:refs/remotes/origin/*"], Some(&mut fo), None)
        .map_err(|e| CoreError::Other(format!("fetch 失败: {e}")))?;

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
    use git2::{FetchOptions, FetchPrune, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;

    let mut callbacks = RemoteCallbacks::new();
    callbacks.certificate_check(check_cert);
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

    let mut fo = FetchOptions::new();
    fo.remote_callbacks(callbacks);
    fo.prune(if prune { FetchPrune::On } else { FetchPrune::Off });
    remote
        .fetch(&["refs/heads/*:refs/remotes/origin/*"], Some(&mut fo), None)
        .map_err(|e| CoreError::Other(format!("fetch 失败: {e}")))?;
    Ok(())
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
    use git2::{PushOptions, RemoteCallbacks, Repository};

    let repo = Repository::open(dir).map_err(|e| CoreError::Other(format!("打开仓库失败: {e}")))?;
    let mut remote = repo
        .find_remote("origin")
        .map_err(|e| CoreError::Other(format!("找不到 origin: {e}")))?;

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
        "has_remote_ref": has_remote_ref
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
    fn clone_error_keeps_lock_path_and_adds_hint() {
        let raw = "failed to lock file '/storage/emulated/0/Android/data/com.branchbase/files/repos/SunsetRNE/Branchbase-Android/.git/config.lock' for writing";
        let text = other_message(map_clone_error(&git2::Error::from_str(raw)));
        assert!(text.contains("/repos/SunsetRNE/Branchbase-Android/.git/config.lock"), "路径不能被截断或改写: {text}");
        assert!(text.contains("残留锁文件"), "应点明这是残留锁文件: {text}");
        assert!(text.contains("重试"), "应给出一句可照做的处置: {text}");
    }

    /// 其余失败原样透出（只加 `clone 失败: ` 前缀），不做二次解释。
    #[test]
    fn clone_error_keeps_other_failures_verbatim() {
        let text = other_message(map_clone_error(&git2::Error::from_str("authentication required but no callback set")));
        assert_eq!(text, "clone 失败: authentication required but no callback set");
    }

    /// 取消：回调中断之后 libgit2 只会给一句笼统的失败，
    /// 上层要能区分「用户取消」与「真的失败」（前者不该报红字错误）。
    #[test]
    fn clone_error_reports_cancel() {
        CANCEL_REQUESTED.store(true, Ordering::SeqCst);
        let text = other_message(map_clone_error(&git2::Error::from_str("callback returned non-zero")));
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
}
