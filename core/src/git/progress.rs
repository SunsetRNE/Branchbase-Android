//! clone 进度快照：给上层「进度条弹窗」轮询的一份只读视图。
//!
//! ## 为什么是轮询，而不是从 Rust 回调 Kotlin
//!
//! 进度回调由 libgit2 在 **clone 所在的那条线程**（JNI 调用线程）上同步触发。
//! 要在回调里通知 Kotlin，得持有 `JavaVM`、`AttachCurrentThread`、再存一个全局 jobject 引用 ——
//! 线程附着、异常、生命周期三件事任何一件写错都是崩溃或泄漏，而收益仅仅是省掉每几百毫秒一次的读。
//! 所以这里只在进程内维护一份**快照**：Rust 在回调里更新，Kotlin 轮询 [`snapshot_json`]。
//!
//! ## 契约（`phase` 是稳定字符串，UI 负责翻文案）
//!
//! | phase | 含义 | 上层显示 |
//! |---|---|---|
//! | `idle` | 没有 clone 在跑 | 不显示弹窗 |
//! | `connect` | 已开始、还没有对象计数（握手 / 协商） | 连接远端… |
//! | `receive` | 正在接收对象 | 接收对象… |
//! | `resolve` | 对象收齐、正在解增量 | 解析增量… |
//! | `checkout` | 正在检出工作区 | 检出文件… |
//! | `finalize` | 检出完毕、正在写引用 / HEAD | 写入引用… |
//! | `done` | 成功结束 | 完成 |
//! | `failed` | 失败结束 | 失败（原因由 clone 返回值给出） |
//!
//! 数字字段全部是**原始计数**（不在这里折算百分比）：百分比是**展示口径**，
//! 要按阶段加权、还要在中英两套文案下说得通，放在 UI 侧做纯函数更好测。

use std::sync::{Mutex, MutexGuard, OnceLock};

use serde_json::json;

/// 进程内进度快照（字段含义见模块文档）。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Snapshot {
    /// 是否有 clone 在跑。
    pub active: bool,
    /// 成功结束。
    pub done: bool,
    /// 失败结束。
    pub failed: bool,
    /// 已接收对象数。
    pub received: usize,
    /// 对象总数（远端未给时为 0 —— 上层据此显示不确定进度）。
    pub total: usize,
    /// 已解析（入库）对象数。
    pub indexed: usize,
    /// 已接收字节数。
    pub bytes: u64,
    /// 已检出文件数。
    pub checkout_done: usize,
    /// 待检出文件数（libgit2 未给时为 0）。
    pub checkout_total: usize,
}

impl Snapshot {
    /// 当前阶段（取值见模块文档的契约表）。
    pub fn phase(&self) -> &'static str {
        if self.failed {
            return "failed";
        }
        if self.done {
            return "done";
        }
        if !self.active {
            return "idle";
        }
        // 检出阶段的计数只在 checkout 回调里出现；它一旦开始就压过 fetch 的三个阶段。
        if self.checkout_total > 0 && self.checkout_done < self.checkout_total {
            return "checkout";
        }
        if self.total == 0 {
            // 远端没报总数（或还没拿到）：收到过对象就算「接收中」，否则还在握手。
            return if self.received > 0 { "receive" } else { "connect" };
        }
        if self.received < self.total {
            return "receive";
        }
        if self.indexed < self.total {
            return "resolve";
        }
        "finalize"
    }
}

static SNAPSHOT: OnceLock<Mutex<Snapshot>> = OnceLock::new();

/// 取锁：回调线程 panic 只会污染这把锁，不该让「进度」从此永远读不到 ——
/// 中毒的锁照常取用（快照本身没有跨字段不变量，读到中间态也无害）。
fn lock() -> MutexGuard<'static, Snapshot> {
    SNAPSHOT
        .get_or_init(|| Mutex::new(Snapshot::default()))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// 开始一次 clone：把上一次的结果（含 failed）清掉。
pub(crate) fn begin() {
    *lock() = Snapshot { active: true, ..Snapshot::default() };
}

/// fetch 回调：接收 / 解析计数。
pub(crate) fn transfer(received: usize, total: usize, indexed: usize, bytes: u64) {
    let mut s = lock();
    s.received = received;
    s.total = total;
    s.indexed = indexed;
    s.bytes = bytes;
}

/// checkout 回调：已检出 / 待检出文件数。
pub(crate) fn checkout(done: usize, total: usize) {
    let mut s = lock();
    s.checkout_done = done;
    s.checkout_total = total;
}

/// 结束一次 clone（成功 / 失败都保留计数，便于失败后回看卡在哪一段）。
pub(crate) fn complete(ok: bool) {
    let mut s = lock();
    s.active = false;
    s.done = ok;
    s.failed = !ok;
}

/// 当前进度（单行 JSON）。没有 clone 在跑时是 `phase:"idle"` 的全零快照。
pub fn snapshot_json() -> String {
    let s = *lock();
    json!({
        "phase": s.phase(),
        "active": s.active,
        "received": s.received,
        "total": s.total,
        "indexed": s.indexed,
        "bytes": s.bytes,
        "checkoutDone": s.checkout_done,
        "checkoutTotal": s.checkout_total,
    })
    .to_string()
}

/// 仅供测试：把快照恢复成「没有 clone 在跑」。
#[cfg(test)]
pub(crate) fn reset_for_test() {
    *lock() = Snapshot::default();
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snap(active: bool, received: usize, total: usize, indexed: usize) -> Snapshot {
        Snapshot { active, received, total, indexed, ..Snapshot::default() }
    }

    #[test]
    fn phase_is_idle_when_nothing_runs() {
        assert_eq!(Snapshot::default().phase(), "idle");
    }

    #[test]
    fn phase_walks_connect_receive_resolve_finalize() {
        // 刚开始：没有对象计数
        assert_eq!(snap(true, 0, 0, 0).phase(), "connect");
        // 收到一部分
        assert_eq!(snap(true, 3, 10, 2).phase(), "receive");
        // 对象收齐但没解析完
        assert_eq!(snap(true, 10, 10, 7).phase(), "resolve");
        // 都完成了：剩下的只有写引用 / HEAD
        assert_eq!(snap(true, 10, 10, 10).phase(), "finalize");
    }

    #[test]
    fn phase_receives_even_when_total_unknown() {
        // 远端没报总数：不能永远停在「连接远端」，收到对象就该是接收中
        assert_eq!(snap(true, 5, 0, 5).phase(), "receive");
    }

    #[test]
    fn phase_checkout_wins_over_fetch_phases() {
        let mut s = snap(true, 10, 10, 10);
        s.checkout_done = 2;
        s.checkout_total = 9;
        assert_eq!(s.phase(), "checkout");
        // 检出完成之后回落成 finalize（由 clone 收尾），而不是卡在 checkout
        s.checkout_done = 9;
        assert_eq!(s.phase(), "finalize");
    }

    #[test]
    fn phase_reports_outcome_after_finish() {
        let mut s = snap(true, 10, 10, 10);
        s.active = false;
        s.done = true;
        assert_eq!(s.phase(), "done");
        let mut f = snap(true, 10, 10, 10);
        f.active = false;
        f.failed = true;
        assert_eq!(f.phase(), "failed");
    }

    #[test]
    fn snapshot_json_exposes_phase_and_counters() {
        reset_for_test();
        begin();
        transfer(4, 8, 3, 1024);
        let v: serde_json::Value = serde_json::from_str(&snapshot_json()).unwrap();
        assert_eq!(v["phase"], "receive");
        assert_eq!(v["received"], 4);
        assert_eq!(v["total"], 8);
        assert_eq!(v["indexed"], 3);
        assert_eq!(v["bytes"], 1024);
        complete(true);
        let v: serde_json::Value = serde_json::from_str(&snapshot_json()).unwrap();
        assert_eq!(v["phase"], "done");
        reset_for_test();
    }
}
