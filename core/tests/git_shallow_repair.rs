//! 回归测试：本地副本「缺对象但自称完整」时，fetch 不该永久死掉。
//!
//! 用户报的事故（真机 Branchbase）：
//!
//! ```text
//! 刷新远端失败：未知错误：fetch 失败:object not found - no match for id (c8301a5…); class=Odb (9); code=NotFound (-3)
//! ```
//!
//! 现场形状：整体是 depth=1 的浅 clone，但 `.git/shallow` 不在了 —— 于是仓库自称完整，
//! 提交图/文件历史/diff 的本地读取全部失败退回 REST（日志里就是这么写的），而且远端只要
//! 有新提交，fetch 协商阶段就会从 `refs/*` 走本地历史、撞上缺的父提交，libgit2 把整次
//! fetch 判死。修复（`repair_shallow_boundary` + fetch 失败后的自愈重试）见 `src/git/mod.rs`。
//!
//! 现场用**真 git** 造：
//!   1. `git init -b main` 造 origin，提交若干次；
//!   2. `git daemon --listen=127.0.0.1` 起真 git:// 智能传输（不联网）；
//!   3. `git clone --depth=1` 造真浅 clone（git 自己的 depth 请求，没问题）；
//!   4. 删掉 `.git/shallow`；
//!   5. 在 origin 再提交一次 —— 这一步是关键：只有远端有新东西，fetch 才有 want，
//!      才会进协商阶段走本地历史。
//!
//! 为什么不直接用 libgit2 的 `clone_repo(depth=1)` 造浅 clone：libgit2 1.7.2 把服务端公告的
//! `shallow` 当成客户端能力回显进 want 行（`want <sha> … shallow `），git ≥ 2.4x 的
//! upload-pack 直接 `fatal: expected SHA1 list` 把连接掐了。真机 GitHub 容忍这个回显
//! （用直连 GitHub 的探针验证过：浅 clone / fetch / 加深三步都成功），只是本机造现场造不出来。
//! 所以这里用 git CLI 造浅 clone，另加一个把 want 行里 ` shallow` 洗掉的转发代理，
//! 让 libgit2 的 depth fetch（`fetch_deepen`）也能对着本机 upload-pack 跑。

use std::io::{Read, Write};
use std::net::{Shutdown, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

// ────────────────────────── 本机 git 能力 ──────────────────────────

fn git_available() -> bool {
    Command::new("git")
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .expect("拿一个空闲端口")
        .local_addr()
        .unwrap()
        .port()
}

/// 跑一条 git 命令，失败即 panic（测试现场就该是确定的）。
fn git(cwd: &Path, args: &[&str]) -> String {
    let out = Command::new("git")
        .args(args)
        .current_dir(cwd)
        .output()
        .unwrap_or_else(|e| panic!("git {args:?} 起不来: {e}"));
    assert!(
        out.status.success(),
        "git {args:?} 失败: {}{}",
        String::from_utf8_lossy(&out.stderr),
        String::from_utf8_lossy(&out.stdout)
    );
    String::from_utf8_lossy(&out.stdout).to_string()
}

// ────────────────────────── git daemon ──────────────────────────

struct Daemon {
    child: Child,
    port: u16,
}

impl Drop for Daemon {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Daemon {
    fn url(&self, name: &str) -> String {
        format!("git://127.0.0.1:{}/{name}/.git", self.port)
    }
}

fn start_daemon(base: &Path) -> Daemon {
    let port = free_port();
    let log = std::fs::File::create(base.join("daemon.log")).expect("写 daemon 日志");
    let child = Command::new("git")
        .args([
            "daemon",
            "--verbose",
            "--listen=127.0.0.1",
            &format!("--port={port}"),
            "--export-all",
            "--reuseaddr",
            &format!("--base-path={}", base.display()),
        ])
        .stdout(Stdio::from(log.try_clone().unwrap()))
        .stderr(Stdio::from(log))
        .spawn()
        .expect("起 git daemon");

    let deadline = Instant::now() + Duration::from_secs(5);
    while Instant::now() < deadline {
        if TcpStream::connect(("127.0.0.1", port)).is_ok() {
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    Daemon { child, port }
}

// ─────────────────── 洗掉 want 行里 ` shallow` 的转发代理 ───────────────────

/// 监听一个临时端口，把每条连接转发到 `upstream`；客户端→服务端方向上按 pkt-line 解析，
/// 只把 `want` 行里的 ` shallow` 能力回显删掉（长度前缀重算），其余字节双向原样过。
///
/// 必须按 pkt-line 切而不是整体透传：一次 read 可能只拿到半个 pkt-line。
struct Proxy {
    port: u16,
}

fn start_proxy(upstream: u16) -> Proxy {
    let listener = TcpListener::bind("127.0.0.1:0").expect("代理监听");
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for conn in listener.incoming() {
            let Ok(client) = conn else { break };
            let Ok(server) = TcpStream::connect(("127.0.0.1", upstream)) else {
                continue;
            };
            std::thread::spawn(move || pump(client, server, port, upstream));
        }
    });
    Proxy { port }
}

fn pump(client: TcpStream, server: TcpStream, proxy_port: u16, upstream_port: u16) {
    let mut server = server;
    let mut up_read = client.try_clone().unwrap();
    let mut down_read = server.try_clone().unwrap();

    let up = std::thread::spawn(move || forward_wants(&mut up_read, &mut server, proxy_port, upstream_port));
    let mut down_write = client;
    let _ = std::io::copy(&mut down_read, &mut down_write);
    let _ = down_write.shutdown(Shutdown::Write);
    let _ = up.join();
}

/// 客户端 → 服务端：按 pkt-line 边界解析后转发，`want` 行先洗一遍。
fn forward_wants(src: &mut TcpStream, dst: &mut TcpStream, proxy_port: u16, upstream_port: u16) {
    let mut pending: Vec<u8> = Vec::new();
    let mut chunk = [0u8; 8192];
    // libgit2 1.7.2 在 depth>0 时会把 want 段发两遍（depth 分支发一次，收尾那次 send 的 buffer
    // 里还留着同一份），本机 git 的 upload-pack 在 have 阶段收到 want 行会直接
    // fatal `expected SHA1 list`。重复的那段丢给测试自己吞掉，测的是 `fetch_deepen` 本身。
    let mut sent_wants = false;
    let mut dropping = false;
    loop {
        let n = match src.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => n,
        };
        pending.extend_from_slice(&chunk[..n]);
        loop {
            if pending.len() < 4 {
                break;
            }
            let Some(len) = parse_pkt_len(&pending[..4]) else {
                // 不是 pkt-line（不该发生）：把已攒的原样倒出去，避免卡死
                if dst.write_all(&pending).is_err() {
                    return;
                }
                pending.clear();
                break;
            };
            let line_len = if len == 0 { 4 } else { len };
            if line_len < 4 || pending.len() < line_len {
                break;
            }
            let line: Vec<u8> = pending.drain(..line_len).collect();
            let mut out = rewrite_line(&line, proxy_port, upstream_port);
            let text = String::from_utf8_lossy(line.get(4..).unwrap_or_default()).to_string();
            if dropping {
                if len == 0 {
                    dropping = false;
                }
                out.clear();
            } else if text.starts_with("want ") {
                if sent_wants {
                    dropping = true;
                    out.clear();
                } else {
                    sent_wants = true;
                }
            }
            if std::env::var_os("BB_PROXY_DEBUG").is_some() {
                eprintln!("C>S {:?} -> {:?}", text, String::from_utf8_lossy(&out));
            }
            if out.is_empty() {
                continue;
            }
            if dst.write_all(&out).is_err() {
                return;
            }
        }
        if dst.flush().is_err() {
            return;
        }
    }
    let _ = dst.write_all(&pending);
    let _ = dst.shutdown(Shutdown::Write);
}

fn parse_pkt_len(prefix: &[u8]) -> Option<usize> {
    let s = std::str::from_utf8(prefix).ok()?;
    usize::from_str_radix(s, 16).ok()
}

/// 握手首行的 `host=` 指回上游；其余原样透传。
///
/// **不要**动 `want` 行里那个 ` shallow` 能力回显：它是 libgit2 1.7.2 的怪癖，但 git 的
/// upload-pack 受得，而一旦把它摘掉，服务端就当客户端「不懂 shallow」，转头把客户端声明为
/// 浅边界的那条提交的父辈也一并塞进 pack —— 测试现场就不再是「缺父对象」的形状了。
fn rewrite_line(line: &[u8], proxy_port: u16, upstream_port: u16) -> Vec<u8> {
    if line.len() < 4 {
        return line.to_vec();
    }
    let payload = &line[4..];
    let text = String::from_utf8_lossy(payload);
    if !text.starts_with("git-upload-pack") {
        return line.to_vec();
    }
    let fixed = text.replace(
        &format!("host=127.0.0.1:{proxy_port}"),
        &format!("host=127.0.0.1:{upstream_port}"),
    );
    if fixed == text {
        return line.to_vec();
    }
    let body = fixed.as_bytes();
    let mut out = format!("{:04x}", body.len() + 4).into_bytes();
    out.extend_from_slice(body);
    out
}

// ────────────────────────── 测试现场 ──────────────────────────

struct Fixture {
    base: PathBuf,
    origin: PathBuf,
    clone: PathBuf,
    daemon: Daemon,
    proxy: Option<Proxy>,
}

impl Fixture {
    /// 造 origin（`commits` 个提交）+ 起 daemon + 真浅 clone。
    fn new(tag: &str, commits: usize, with_proxy: bool) -> Fixture {
        let base = std::env::temp_dir().join(format!("bb-shallow-{tag}"));
        let _ = std::fs::remove_dir_all(&base);
        std::fs::create_dir_all(&base).expect("建测试目录");
        let origin = base.join("origin");
        std::fs::create_dir_all(&origin).unwrap();

        git(&base, &["init", "-q", "-b", "main", origin.to_str().unwrap()]);
        git(&origin, &["config", "user.email", "t@example.com"]);
        git(&origin, &["config", "user.name", "branchbase-test"]);
        git(&origin, &["config", "commit.gpgsign", "false"]);
        for i in 0..commits {
            std::fs::write(origin.join(format!("f{i}.txt")), format!("{i}\n")).unwrap();
            git(&origin, &["add", "-A"]);
            git(&origin, &["commit", "-qm", &format!("c{i}")]);
        }

        let daemon = start_daemon(&base);
        let clone = base.join("clone");
        git(
            &base,
            &[
                "clone",
                "-q",
                "--depth=1",
                &daemon.url("origin"),
                clone.to_str().unwrap(),
            ],
        );

        let proxy = if with_proxy {
            let p = start_proxy(daemon.port);
            git(
                &clone,
                &["remote", "set-url", "origin", &format!("git://127.0.0.1:{}/origin/.git", p.port)],
            );
            Some(p)
        } else {
            None
        };

        Fixture {
            base,
            origin,
            clone,
            daemon,
            proxy,
        }
    }

    fn dir(&self) -> &str {
        self.clone.to_str().unwrap()
    }

    fn shallow_file(&self) -> PathBuf {
        self.clone.join(".git/shallow")
    }

    /// 删掉浅边界文件 —— 事故的起点：仓库从此自称完整。
    fn lose_shallow_file(&self) {
        assert!(self.shallow_file().exists(), "现场应当先有 .git/shallow");
        std::fs::remove_file(self.shallow_file()).unwrap();
        assert!(!self.shallow_file().exists());
    }

    fn local_tip(&self) -> String {
        git(&self.clone, &["rev-parse", "HEAD"]).trim().to_string()
    }

    fn tracked_remote_tip(&self) -> String {
        git(&self.clone, &["rev-parse", "refs/remotes/origin/main"])
            .trim()
            .to_string()
    }

    fn origin_tip(&self) -> String {
        git(&self.origin, &["rev-parse", "HEAD"]).trim().to_string()
    }

    fn origin_commits(&self) -> String {
        git(&self.origin, &["rev-list", "--count", "HEAD"]).trim().to_string()
    }

    /// 在远端追加一个提交，返回新 tip。没有它，fetch 就没有 want，也就不会走协商。
    fn add_remote_commit(&self, name: &str) -> String {
        std::fs::write(self.origin.join(format!("{name}.txt")), format!("{name}\n")).unwrap();
        git(&self.origin, &["add", "-A"]);
        git(&self.origin, &["commit", "-qm", name]);
        self.origin_tip()
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = self.proxy;
        let _ = self.daemon;
        if std::env::var_os("BB_KEEP_TMP").is_none() {
            let _ = std::fs::remove_dir_all(&self.base);
        }
    }
}

/// 裸 libgit2 fetch：等价 `fetch_origin` 的第一次尝试（绕过自愈）。
fn raw_fetch(dir: &Path) -> Result<(), git2::Error> {
    let repo = git2::Repository::open(dir).unwrap();
    let mut fo = git2::FetchOptions::new();
    fo.prune(git2::FetchPrune::On);
    let mut remote = repo.find_remote("origin").unwrap();
    remote.fetch(&["refs/heads/*:refs/remotes/origin/*"], Some(&mut fo), None)
}

// ────────────────────────── 用例 ──────────────────────────

/// 复刻用户报错：缺对象且没有浅边界时，裸 fetch 死在协商阶段（class=Odb / code=NotFound）。
#[test]
fn 缺对象且无浅边界时_fetch_死在协商阶段() {
    if !git_available() {
        eprintln!("跳过：本机没有 git");
        return;
    }
    let fx = Fixture::new("accident", 5, false);
    let tip_before = fx.local_tip();
    // 浅 clone 里 `git rev-parse HEAD^` 走不通（父被边界截断），从 origin 侧取父提交
    let missing_parent = git(&fx.origin, &["rev-parse", "HEAD^"]).trim().to_string();
    fx.lose_shallow_file();
    let new_tip = fx.add_remote_commit("new");

    let err = raw_fetch(&fx.clone).expect_err("缺对象时协商应当失败");
    eprintln!("裸 fetch 报错: {err}");
    assert_eq!(err.class(), git2::ErrorClass::Odb, "错误类别应当是 Odb");
    assert_eq!(err.code(), git2::ErrorCode::NotFound, "错误码应当是 NotFound");
    let msg = err.message().to_string();
    assert!(
        msg.contains("object not found") && msg.contains(&missing_parent),
        "报错应当指向缺的那个父提交 {missing_parent}，实际: {msg}"
    );

    // 失败的 fetch 不该把远端引用改到新 tip
    assert_eq!(fx.tracked_remote_tip(), tip_before);
    assert_ne!(new_tip, tip_before);
}

/// 自愈：`fetch_remote` 先失败、补上浅边界、重试一次后成功刷新。
#[test]
fn fetch_remote_缺对象时自愈并刷新成功() {
    if !git_available() {
        eprintln!("跳过：本机没有 git");
        return;
    }
    let fx = Fixture::new("heal", 5, false);
    let tip_before = fx.local_tip();
    fx.lose_shallow_file();
    let new_tip = fx.add_remote_commit("new");

    let bare = raw_fetch(&fx.clone);
    eprintln!("前置裸 fetch（应当失败）= {bare:?}");
    assert!(bare.is_err(), "现场应当先坏掉：裸 fetch 必须先失败");
    assert!(!fx.shallow_file().exists(), "失败的 fetch 不该凭空造出边界");

    let healed = branchbase_core::git::fetch_remote(fx.dir(), None, true);
    eprintln!(
        "fetch_remote = {healed:?}; shallow = {:?}",
        std::fs::read_to_string(fx.shallow_file())
    );
    healed.expect("自愈之后 fetch 应当成功");

    let shallow = std::fs::read_to_string(fx.shallow_file()).expect("应当补出 .git/shallow");
    assert_eq!(shallow.trim(), tip_before, "浅边界应当是缺父的那个本地 tip");
    assert_eq!(fx.tracked_remote_tip(), new_tip, "远端引用应当刷到新 tip");
    let repo = git2::Repository::open(&fx.clone).unwrap();
    assert!(repo.is_shallow(), "补完边界后仓库应当自称浅");

    // 再刷一次仍然好（幂等，不会因为已有边界又出错）
    branchbase_core::git::fetch_remote(fx.dir(), None, true).expect("第二次 fetch 也应当成功");
}

/// `repair_shallow_boundary` 只补必要的那一条，且可重复调用。
#[test]
fn repair_shallow_boundary_只补必要的边界() {
    if !git_available() {
        eprintln!("跳过：本机没有 git");
        return;
    }
    let fx = Fixture::new("repair", 5, false);
    let tip_before = fx.local_tip();
    fx.lose_shallow_file();

    let added = branchbase_core::git::repair_shallow_boundary(fx.dir()).expect("修复应当成功");
    assert_eq!(added, 1, "只缺 tip 的父，应当只补一条");
    assert_eq!(
        std::fs::read_to_string(fx.shallow_file()).unwrap().trim(),
        tip_before
    );
    let again = branchbase_core::git::repair_shallow_boundary(fx.dir()).expect("再修一次应当成功");
    assert_eq!(again, 0, "已经补过就不该再有新增");
}

/// **事故入口**：一个健康的浅 clone 刷新一次远端，`.git/shallow` 必须还在。
///
/// 这是 libgit2 1.7.2 `setup_shallow_roots` 的 size bug 的现场（见 `core/src/git/mod.rs` 的
/// `restore_shallow_after_fetch`）：修之前这一趟 fetch 收尾会把边界文件删掉，仓库从此
/// 「自称完整、其实缺对象」，**下一次** fetch 就死在 `object not found - no match for id (…)`。
/// 所以这里连着刷两次，第二次也必须好。
#[test]
fn 刷新远端之后浅边界仍在() {
    if !git_available() {
        eprintln!("跳过：本机没有 git");
        return;
    }
    let fx = Fixture::new("refresh", 5, true);
    let tip_before = fx.local_tip();
    assert!(fx.shallow_file().exists(), "现场应当是浅 clone");
    // 远端必须真动过：没有新东西时 libgit2 直接 `need_pack = 0`，根本不进协商，也就摸不到 bug
    let first = fx.add_remote_commit("first");

    branchbase_core::git::fetch_remote(fx.dir(), None, true).expect("第一次刷新应当成功");

    let shallow = std::fs::read_to_string(fx.shallow_file()).expect(
        "刷新远端不该把浅边界删掉（libgit2 1.7.2：非加深 fetch 收尾写的是空 roots）",
    );
    assert_eq!(
        shallow.trim(),
        tip_before,
        "边界应当仍指向缺父的那个本地 tip"
    );
    assert_eq!(fx.tracked_remote_tip(), first, "远端引用应当刷到新 tip");

    // 第二次：修好之前这一步就是用户报的那条错，且此后每次都会错
    let second = fx.add_remote_commit("second");
    branchbase_core::git::fetch_remote(fx.dir(), None, true).expect("第二次刷新也应当成功");
    assert_eq!(fx.tracked_remote_tip(), second);
    assert!(
        fx.shallow_file().exists(),
        "第二次刷新之后边界仍该在（仓库依旧只克隆了一层）"
    );
}

/// 真浅 clone 上加深：全量加深、浅边界被清掉、全史可走。
/// （要过 git ≥2.4x 的 upload-pack，所以走洗 want 行的代理。）
#[test]
fn fetch_deepen_在真浅_clone_上全量加深() {
    if !git_available() {
        eprintln!("跳过：本机没有 git");
        return;
    }
    let fx = Fixture::new("deepen", 5, true);
    assert!(fx.shallow_file().exists(), "现场应当是浅 clone");
    let new_tip = fx.add_remote_commit("new");

    branchbase_core::git::fetch_deepen(fx.dir(), 0, None).expect("全量加深应当成功");

    assert!(!fx.shallow_file().exists(), "加深之后不该再自称浅");
    assert_eq!(fx.tracked_remote_tip(), new_tip);
    let walked = git(
        &fx.clone,
        &["rev-list", "--count", "refs/remotes/origin/main"],
    );
    assert_eq!(walked.trim(), fx.origin_commits(), "全史应当都能走到");
}

/// 真机恢复动作：既缺祖先又没有浅边界时，一次「加深历史」应当先自愈再全量加深。
#[test]
fn fetch_deepen_在缺对象且无浅边界时自愈并加深() {
    if !git_available() {
        eprintln!("跳过：本机没有 git");
        return;
    }
    let fx = Fixture::new("deepen-heal", 5, true);
    fx.lose_shallow_file();
    let new_tip = fx.add_remote_commit("new");

    branchbase_core::git::fetch_deepen(fx.dir(), 0, None).expect("自愈之后加深应当成功");

    assert!(!fx.shallow_file().exists(), "加深之后不该留浅边界");
    assert_eq!(fx.tracked_remote_tip(), new_tip);
    let walked = git(
        &fx.clone,
        &["rev-list", "--count", "refs/remotes/origin/main"],
    );
    assert_eq!(walked.trim(), fx.origin_commits(), "全史应当都能走到");
}
