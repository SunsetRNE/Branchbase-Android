#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""页面重建 · 帧率基线（FrameWatch 取数 → 聚合报表 → 改前/改后对比）

## 为什么走这条通道，而不是 `dumpsys gfxinfo framestats`

1. **取不到**：设备守护把 `dumpsys gfxinfo` / `dumpsys SurfaceFlinger` 判为「未识别的 dumpsys 参数」
   直接拦掉（`perfetto` 同样不在放行名单里）；
2. **本来也不需要**：App 自己的 `ui/log/FrameWatch.kt` 用的就是 `Window.addOnFrameMetricsAvailableListener`
   —— 与 `framestats` 同源（系统每帧下发），而且没有那两个硬伤（只留 120 帧、dump 自身跑在被测进程主线程）；
3. **只读取数**：日志落在 `Android/data/com.branchbase/files/logs/<北京时间日期>/branchbase.log`，
   `adb-shell cat` 读出来即可（策略允许读任何目录，Android/data 只读）；
4. **自带归因**：每条慢帧后面都跟着「当时最近一条 UI 日志」当页面注脚（`· 页面「切换到「动态」」`），
   所以**谁点的、点了什么**都能归因 —— 脚本不必自己去驱动 UI（读屏被拒时这条尤其关键）。

## 口径（与 FrameWatch.kt 的常量一一对应；改那边就要改这里）

| 项 | 值 | 含义 |
|---|---|---|
| 慢帧明细 | 单帧 ≥ 32ms | 记一条完整分段（同类限流 400ms；**更慢的帧不会被限流吃掉** —— 单调升级） |
| 小结 | 每 60s | 窗口内出现过慢帧才记；「超 16ms M/T 帧」= 这一分钟里超预算的帧比例 |
| 分段 8 栏 | 等待/输入/动画/布局/绘制/上传/下发/交换 | 带 `*` 的是这一帧**最大**的一段，报表按它做「主段直方图」 |

阈值按 **60Hz** 写死（16ms 预算 / 32ms 慢帧）。本机 `settings get system peak_refresh_rate` = 60，
口径正确；换到 120Hz 设备要按刷新率折算，否则「超 16ms」会系统性低估。

## 用法

    # 1) 现场采集（这段时间里自己操作手机：切 Tab、进详情、返回…）
    python3 tools/perf/frame-baseline.py capture --label 重建基线-v1.0.53 --seconds 180

    # 1b) 不想被时长绑住：持续跟读，操作完再停（人操作期间推荐）
    python3 tools/perf/frame-baseline.py watch --label 重建基线-v1.0.53

    # 2) 只解析已有日志（含设备上历史那一天的）
    python3 tools/perf/frame-baseline.py analyze --log tools/perf/reports/xxx.log

    # 3) 一步到位：采完立刻出报表
    python3 tools/perf/frame-baseline.py run --label 重建基线-v1.0.53 --seconds 180

    # 4) 改前 / 改后（给两份 json）
    python3 tools/perf/frame-baseline.py compare --before a.json --after b.json

产物：`tools/perf/reports/<label>-<时间>.log`（原始切片）+ `.md`（人看的）+ `.json`（机器比的）。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

# ── 设备侧常量（改 App 的日志路径时同步这里） ──

DEVICE_LOG_ROOT = "/storage/emulated/0/Android/data/com.branchbase/files/logs"
LOG_NAME = "branchbase.log"
REPORTS_DIR = Path(__file__).resolve().parent / "reports"

# ── 行格式 ──
# 10:38:41.435 [UI] [帧] INFO 慢帧 69.3ms（等待 0.5 / … / 交换 0.4） · 页面「进入消息页」 · 漏采 2
LINE_RE = re.compile(
    r"^(?P<t>\d{2}:\d{2}:\d{2}\.\d{3}) \[(?P<cat>[^\]]+)\] \[(?P<tag>[^\]]*)\] (?P<lvl>\w+) (?P<msg>.*)$"
)
SLOW_RE = re.compile(r"^慢帧 (?P<total>[\d.]+)ms（(?P<parts>[^）]*)）(?P<rest>.*)$")
# 贪婪匹配到**行尾最后一个** `」`：注脚本身常带引号（`页面「切换到「动态」」`），
# 非贪婪会在内层那个 `」` 上停住，把页面名截成 `切换到「动态`。
PAGE_RE = re.compile(r"· 页面「(?P<page>.*)」")
DROPPED_RE = re.compile(r"· 漏采 (?P<n>\d+)")
SUMMARY_RE = re.compile(
    r"^近 (?P<win>\d+)s 慢帧 (?P<slow>\d+) 次（最慢 (?P<worst>[\d.]+)ms(?:，(?P<page>[^）]*))?）"
    r"；超 16ms (?P<over>\d+)/(?P<frames>\d+) 帧"
)
SEG_RE = re.compile(r"^(?P<name>[^\d\s]+) (?P<ms>[\d.]+)(?P<star>\*)?$")

SEGMENTS = ["等待", "输入", "动画", "布局", "绘制", "上传", "下发", "交换"]

# 场景分类：把「页面」注脚归到可对比的桶里。
# 注意顺序 —— 后面的规则更宽，先匹配到的赢（`气泡` 要排在 `进入` 前面，
# 「展开 ⋮ 气泡菜单」不该被算成「进入子页」）。
#
# 前两条是**代码事实**，不是文案猜测：
# - 「进入消息页」是 NotificationScreen 的打点，而它渲染在 `MainScreen` 的 `TabSwitcher`
#   （`main-tab`）里 —— 底栏切 Tab = 同级切换 + **页面重建**，不是层级推进；
# - 「进入个人主页 / 进入设置页」走的是 `MainRoute.Profile`（depth=1）与设置子页，
#   才是真正的层级推进（有方向位移的那一类）。
SCENARIO_RULES: list[tuple[str, re.Pattern[str]]] = [
    # 启动段必须排在最前：`启动 ▸ …` / `启动 ■ 首帧已上屏` 是 BranchbaseApp / MainActivity /
    # FrameWatch 打的阶段标记（1.0.65 起）。此前启动那一帧落进「其它」桶 —— 而它恰恰是全场景里
    # 最慢的一帧（真机 14/14 次启动各 1 条，115~266ms）。前缀是「启动」，与下面几条的
    # `进入/打开/…` 天然不冲突，但排在最前更保险。
    ("App 启动", re.compile(r"^启动")),
    ("Tab 同级切换（含重建）", re.compile(r"^切换到「|^进入消息页")),
    ("层级推进 / 返回", re.compile(r"^(进入|打开|展开|返回|关闭|退出|收起)")),
    ("气泡 / 菜单", re.compile(r"(气泡|菜单|More|⋮)")),
    ("其它", re.compile(r".")),
]

NAMED_GROUPS = ["等待", "输入", "动画", "布局", "绘制", "上传", "下发", "交换"]


# ────────────────────────── 设备取数 ──────────────────────────


def adb_shell(cmd: str, timeout: int = 180) -> str:
    """执行一条设备命令。

    ⚠️ 只能传**单条、无管道/重定向/变量**的命令：受保护的设备 shell 会直接拦掉复合命令。
    包装脚本把退出码打在输出末尾（`[EXIT=n]`），成功时也会打 —— 所以要剥掉再判。
    """
    proc = subprocess.run(["adb-shell", cmd], capture_output=True, text=True, timeout=timeout)
    text = (proc.stdout or "") + (proc.stderr or "")
    codes = re.findall(r"^\[EXIT=(\d+)\]\s*$", text, re.M)
    text = re.sub(r"^\[EXIT=\d+\]\s*$", "", text, flags=re.M)
    code = int(codes[-1]) if codes else proc.returncode
    if code != 0:
        raise RuntimeError(f"设备命令失败（{code}）：{cmd}\n{text.strip()[:400]}")
    return text


def newest_log_path() -> str:
    """设备上最新一天的日志文件（按北京时间日期目录取最大者）。"""
    out = adb_shell(f"ls {DEVICE_LOG_ROOT}")
    days = sorted(d.strip() for d in out.splitlines() if re.fullmatch(r"\d{4}-\d{2}-\d{2}", d.strip()))
    if not days:
        raise RuntimeError("设备上没有日志目录：App 今天还没写过日志（或 FrameWatch 关着）")
    return f"{DEVICE_LOG_ROOT}/{days[-1]}/{LOG_NAME}"


def read_device_log(path: str) -> str:
    return adb_shell(f"cat {path}")


# ────────────────────────── 解析 ──────────────────────────


@dataclass
class SlowFrame:
    t: str
    total: float
    parts: dict[str, float]
    star: str
    page: str | None
    dropped: int = 0

    @property
    def scenario(self) -> str:
        return classify(self.page)


@dataclass
class Summary:
    t: str
    win: int
    slow: int
    worst: float
    page: str | None
    over: int
    frames: int


@dataclass
class Parsed:
    slow: list[SlowFrame] = field(default_factory=list)
    summaries: list[Summary] = field(default_factory=list)
    ui_markers: list[tuple[str, str]] = field(default_factory=list)
    requests: list[tuple[str, str]] = field(default_factory=list)
    total_lines: int = 0
    first_ts: str | None = None
    last_ts: str | None = None


def classify(page: str | None) -> str:
    if not page:
        return "无注脚"
    for name, pat in SCENARIO_RULES:
        if pat.search(page):
            return name
    return "其它"


def parse_slow(msg: str, t: str) -> SlowFrame | None:
    m = SLOW_RE.match(msg)
    if not m:
        return None
    parts: dict[str, float] = {}
    star = ""
    for chunk in m.group("parts").split(" / "):
        sm = SEG_RE.match(chunk.strip())
        if not sm:
            continue
        parts[sm.group("name")] = float(sm.group("ms"))
        if sm.group("star"):
            star = sm.group("name")
    page_m = PAGE_RE.search(m.group("rest"))
    drop_m = DROPPED_RE.search(m.group("rest"))
    return SlowFrame(
        t=t,
        total=float(m.group("total")),
        parts=parts,
        star=star or max(parts, key=parts.get) if parts else "",
        page=page_m.group("page") if page_m else None,
        dropped=int(drop_m.group("n")) if drop_m else 0,
    )


def parse_log(text: str, since: str | None = None, until: str | None = None) -> Parsed:
    out = Parsed()
    for raw in text.splitlines():
        if not raw.strip() or raw.startswith("#"):
            continue
        m = LINE_RE.match(raw)
        if not m:
            continue
        t, cat, tag, msg = m.group("t"), m.group("cat"), m.group("tag"), m.group("msg")
        # 时间窗（`HH:MM:SS.mmm` 的字典序就是时间序）：用来把「一轮场景」从整天日志里切出来。
        # 不加窗的话，同一天里几次会话（含 App 重启）会混成一份报表，改前后就没法比。
        if since and t < since:
            continue
        if until and t > until:
            continue
        out.total_lines += 1
        out.first_ts = out.first_ts or t
        out.last_ts = t

        if tag == "帧" or msg.startswith("慢帧") or msg.startswith("近 "):
            sf = parse_slow(msg, t)
            if sf:
                out.slow.append(sf)
                continue
            sm = SUMMARY_RE.match(msg)
            if sm:
                out.summaries.append(
                    Summary(
                        t=t,
                        win=int(sm.group("win")),
                        slow=int(sm.group("slow")),
                        worst=float(sm.group("worst")),
                        page=(sm.group("page") or "").strip() or None,
                        over=int(sm.group("over")),
                        frames=int(sm.group("frames")),
                    )
                )
                continue
        if cat == "UI":
            out.ui_markers.append((t, msg))
        elif cat == "网络":
            out.requests.append((t, msg))
    return out


# ────────────────────────── 聚合 ──────────────────────────


def bucket(total: float) -> str:
    if total >= 100:
        return "≥100ms（肉眼明显卡）"
    if total >= 50:
        return "50–99ms（掉 3–6 帧）"
    return "32–49ms（掉 2–3 帧）"


def aggregate(p: Parsed) -> dict:
    slow = p.slow
    star_hist: dict[str, int] = {}
    seg_stat: dict[str, dict[str, float]] = {s: {"n": 0, "sum": 0.0, "max": 0.0} for s in SEGMENTS}
    for f in slow:
        star_hist[f.star] = star_hist.get(f.star, 0) + 1
        for name, ms in f.parts.items():
            st = seg_stat.setdefault(name, {"n": 0, "sum": 0.0, "max": 0.0})
            st["n"] += 1
            st["sum"] += ms
            st["max"] = max(st["max"], ms)

    by_scenario: dict[str, list[SlowFrame]] = {}
    by_page: dict[str, list[SlowFrame]] = {}
    for f in slow:
        by_scenario.setdefault(f.scenario, []).append(f)
        by_page.setdefault(f.page or "（无注脚）", []).append(f)

    def rollup(frames: list[SlowFrame]) -> dict:
        totals = sorted((f.total for f in frames), reverse=True)
        stars: dict[str, int] = {}
        for f in frames:
            stars[f.star] = stars.get(f.star, 0) + 1
        return {
            "count": len(frames),
            "worst": totals[0] if totals else 0.0,
            "mean": round(sum(totals) / len(totals), 1) if totals else 0.0,
            "over100": sum(1 for t in totals if t >= 100),
            "top_segment": max(stars, key=stars.get) if stars else "",
            "stars": stars,
        }

    over_budget = sum(s.over for s in p.summaries)
    frames_total = sum(s.frames for s in p.summaries)

    return {
        "slow_count": len(slow),
        "worst": max((f.total for f in slow), default=0.0),
        "mean": round(sum(f.total for f in slow) / len(slow), 1) if slow else 0.0,
        "buckets": {b: sum(1 for f in slow if bucket(f.total) == b) for b in
                    ["32–49ms（掉 2–3 帧）", "50–99ms（掉 3–6 帧）", "≥100ms（肉眼明显卡）"]},
        "star_hist": star_hist,
        "segments": {
            k: {"n": v["n"], "mean": round(v["sum"] / v["n"], 1) if v["n"] else 0.0, "max": round(v["max"], 1)}
            for k, v in seg_stat.items()
        },
        "scenarios": {k: rollup(v) for k, v in sorted(by_scenario.items(), key=lambda kv: -len(kv[1]))},
        "pages": {k: rollup(v) for k, v in sorted(by_page.items(), key=lambda kv: -len(kv[1]))},
        "summaries": [
            {"t": s.t, "win_s": s.win, "slow": s.slow, "worst": s.worst, "page": s.page,
             "over16": s.over, "frames": s.frames,
             "over16_ratio": round(s.over / s.frames, 4) if s.frames else None}
            for s in p.summaries
        ],
        "over16_total": over_budget,
        "frames_total": frames_total,
        "over16_ratio": round(over_budget / frames_total, 4) if frames_total else None,
        "window": {"from": p.first_ts, "to": p.last_ts, "lines": p.total_lines},
        "network_lines": len(p.requests),
        "ui_markers": len(p.ui_markers),
    }


# ────────────────────────── 报表 ──────────────────────────


def render_md(label: str, source: str, agg: dict, p: Parsed, verbose: bool) -> str:
    w = agg["window"]
    L: list[str] = []
    L.append(f"# 帧率基线 · {label}")
    L.append("")
    L.append(f"- 来源：`{source}`")
    L.append(f"- 采集窗口：{w['from']} → {w['to']}（共 {w['lines']} 行）")
    L.append(f"- 口径：慢帧 ≥32ms 记明细；小结每 60s 一条；阈值按 60Hz（16ms 预算）")
    L.append("")
    L.append("## 一、总览")
    L.append("")
    L.append("| 指标 | 值 |")
    L.append("|---|---|")
    L.append(f"| 慢帧条数（≥32ms） | **{agg['slow_count']}** |")
    L.append(f"| 最慢一帧 | **{agg['worst']:.1f} ms** |")
    L.append(f"| 慢帧均值 | {agg['mean']} ms |")
    for b, n in agg["buckets"].items():
        L.append(f"| {b} | {n} |")
    if agg["over16_ratio"] is not None:
        L.append(f"| 超 16ms 帧（小结口径） | {agg['over16_total']}/{agg['frames_total']}"
                 f" = **{agg['over16_ratio'] * 100:.1f}%** |")
    L.append("")
    L.append("## 二、主段直方图（这一帧的时间花在哪）")
    L.append("")
    L.append("| 主段 | 出现次数 | 该段均值 | 该段最大 |")
    L.append("|---|---|---|---|")
    for name in sorted(agg["star_hist"], key=lambda n: -agg["star_hist"][n]):
        st = agg["segments"].get(name, {"mean": 0.0, "max": 0.0})
        L.append(f"| **{name}** | {agg['star_hist'][name]} | {st['mean']} ms | {st['max']} ms |")
    L.append("")
    L.append("## 三、分场景（按帧上的「页面」注脚归类）")
    L.append("")
    L.append("| 场景 | 慢帧数 | 最慢 | 均值 | ≥100ms | 主要主段 |")
    L.append("|---|---|---|---|---|---|")
    for name, r in agg["scenarios"].items():
        L.append(f"| {name} | {r['count']} | {r['worst']:.1f} ms | {r['mean']} ms | {r['over100']} | {r['top_segment']} |")
    L.append("")
    L.append("## 四、逐页（Top 12，页面注脚 = 当时最近一条 UI 日志）")
    L.append("")
    L.append("| 页面 / 动作 | 慢帧数 | 最慢 | 主要主段 |")
    L.append("|---|---|---|---|")
    for name, r in list(agg["pages"].items())[:12]:
        L.append(f"| {name} | {r['count']} | {r['worst']:.1f} ms | {r['top_segment']} |")
    L.append("")
    if agg["summaries"]:
        L.append("## 五、每分钟小结（FrameWatch 自己的窗口）")
        L.append("")
        L.append("| 时间 | 窗口 | 慢帧 | 最慢 | 页面 | 超 16ms |")
        L.append("|---|---|---|---|---|---|")
        for s in agg["summaries"]:
            ratio = f"{s['over16_ratio'] * 100:.1f}%" if s["over16_ratio"] is not None else "-"
            L.append(f"| {s['t']} | {s['win_s']}s | {s['slow']} | {s['worst']:.1f} ms | {s['page'] or '-'} |"
                     f" {s['over16']}/{s['frames']} = {ratio} |")
        L.append("")
    if verbose:
        L.append("## 附：逐条明细")
        L.append("")
        L.append("| 时间 | 总计 | 主段 | 分段（ms） | 页面 | 漏采 |")
        L.append("|---|---|---|---|---|---|")
        for f in p.slow:
            segs = " / ".join(f"{k} {v:.1f}" for k, v in f.parts.items())
            L.append(f"| {f.t} | {f.total:.1f} | {f.star} | {segs} | {f.page or '-'} | {f.dropped or '-'} |")
        L.append("")
    L.append("> 归因读法：`等待` 最大 = 主线程被别的工作占住（帧根本没开始画）；"
             "`绘制`/`动画` 最大 = 这一帧真的在画。前者查「谁占了主线程」，后者查「画了多少东西」。")
    return "\n".join(L) + "\n"


# ────────────────────────── 子命令 ──────────────────────────


def cmd_capture(args: argparse.Namespace) -> tuple[Path, str]:
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    label = args.label or "capture"
    out = Path(args.out) if args.out else REPORTS_DIR / f"{label}-{stamp}.log"

    before_path = newest_log_path()
    before = read_device_log(before_path)
    before_lines = len(before.splitlines())
    print(f"[取数] {before_path} 已有 {before_lines} 行；开始采集 {args.seconds}s …")
    print("[取数] 现在开始操作手机：切 Tab / 进详情 / 返回，动作之间停半秒。")

    t0 = time.time()
    while True:
        left = args.seconds - (time.time() - t0)
        if left <= 0:
            break
        time.sleep(min(5, left))
        print(f"  … 还剩 {int(left)}s", flush=True)

    after_path = newest_log_path()
    after = read_device_log(after_path)
    lines = after.splitlines()
    if after_path != before_path:
        slice_lines = lines  # 跨天轮转：新文件整份都是这次的
    else:
        slice_lines = lines[before_lines:]

    header = [
        f"# Branchbase 帧率基线采集",
        f"# label={label}",
        f"# source={after_path}",
        f"# captured_at={time.strftime('%Y-%m-%d %H:%M:%S')}（容器 UTC）",
        f"# slice_lines={len(slice_lines)}",
    ]
    out.write_text("\n".join(header) + "\n" + "\n".join(l for l in slice_lines if l.strip()) + "\n", encoding="utf-8")
    print(f"[取数] 新增 {len(slice_lines)} 行 → {out}")
    return out, label


def cmd_watch(args: argparse.Namespace) -> tuple[Path, str]:
    """持续跟读设备日志（不设死窗口）。

    为什么要有它：现场操作是**人**在跑（读屏被拒时脚本驱动不了 UI），
    固定 `--seconds` 要么不够、要么白等。这里只做「跟读 + 落盘 + 心跳」，
    你说停就停（kill 掉进程），产物与 [cmd_capture] 完全一致，可以接着 `analyze`。
    跨天轮转（北京时间 0 点）也盯着：路径一变就整份接着读。
    """
    REPORTS_DIR.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    label = args.label or "watch"
    out = Path(args.out) if args.out else REPORTS_DIR / f"{label}-{stamp}.log"
    header = [f"# Branchbase 帧率基线采集", f"# label={label}",
              f"# captured_at={time.strftime('%Y-%m-%d %H:%M:%S')}（容器 UTC）"]
    out.write_text("\n".join(header) + "\n", encoding="utf-8")

    cur_path = newest_log_path()
    seen = len(read_device_log(cur_path).splitlines())
    kept = 0
    slow = 0
    worst = 0.0
    deadline = time.time() + args.minutes * 60
    print(f"[watch] {cur_path} 从第 {seen} 行起跟读；上限 {args.minutes} 分钟（随时可停）", flush=True)

    while time.time() < deadline:
        time.sleep(args.interval)
        try:
            path = newest_log_path()
            lines = read_device_log(path).splitlines()
            fresh = lines if path != cur_path else lines[seen:]
            cur_path, seen = path, len(lines)
            fresh = [l for l in fresh if l.strip()]
            if fresh:
                with out.open("a", encoding="utf-8") as fh:
                    fh.write("\n".join(fresh) + "\n")
                kept += len(fresh)
                for l in fresh:
                    if "慢帧" in l:
                        slow += 1
                        m = re.search(r"慢帧 ([\d.]+)ms", l)
                        if m:
                            worst = max(worst, float(m.group(1)))
            print(f"[watch] +{len(fresh):3d} 行 · 累计 {kept} 行 · 慢帧 {slow} 条 · 最慢 {worst:.1f}ms"
                  f"（剩余 {int(deadline - time.time())}s）", flush=True)
        except Exception as exc:  # 跟读失败不能把已采到的丢掉
            print(f"[watch] 本轮读取失败：{exc}", flush=True)
    print(f"[watch] 到时停止 → {out}（{kept} 行）", flush=True)
    return out, label


def cmd_analyze(args: argparse.Namespace) -> dict:
    text = Path(args.log).read_text(encoding="utf-8")
    p = parse_log(text, since=args.since, until=args.until)
    agg = aggregate(p)
    label = args.label or Path(args.log).stem
    md = render_md(label, str(args.log), agg, p, args.verbose)

    md_path = Path(args.md) if args.md else Path(args.log).with_suffix(".md")
    json_path = Path(args.json) if args.json else Path(args.log).with_suffix(".json")
    md_path.write_text(md, encoding="utf-8")
    json_path.write_text(json.dumps({"label": label, "source": str(args.log), **agg}, ensure_ascii=False, indent=2),
                         encoding="utf-8")
    print(md)
    print(f"[报表] {md_path}\n[数据] {json_path}")
    return agg


def cmd_run(args: argparse.Namespace) -> dict:
    log, label = cmd_capture(args)
    ns = argparse.Namespace(log=str(log), label=label, md=None, json=None, verbose=args.verbose)
    return cmd_analyze(ns)


def cmd_compare(args: argparse.Namespace) -> None:
    a = json.loads(Path(args.before).read_text(encoding="utf-8"))
    b = json.loads(Path(args.after).read_text(encoding="utf-8"))

    def delta(key: str, fmt: str = "{:.1f}") -> str:
        va, vb = a.get(key, 0), b.get(key, 0)
        if isinstance(va, (int, float)) and isinstance(vb, (int, float)):
            diff = vb - va
            arrow = "↓" if diff < 0 else ("↑" if diff > 0 else "=")
            return f"{fmt.format(va)} → {fmt.format(vb)}（{arrow}{abs(diff):.1f}）"
        return f"{va} → {vb}"

    print(f"# 改前 / 改后：{a.get('label')} → {b.get('label')}")
    print()
    print(f"- 慢帧条数：{delta('slow_count', '{:.0f}')}")
    print(f"- 最慢一帧：{delta('worst')} ms")
    print(f"- 慢帧均值：{delta('mean')} ms")
    if a.get("over16_ratio") is not None or b.get("over16_ratio") is not None:
        ra = (a.get("over16_ratio") or 0) * 100
        rb = (b.get("over16_ratio") or 0) * 100
        print(f"- 超 16ms 比例：{ra:.1f}% → {rb:.1f}%")
    print()
    print("## 分场景")
    print()
    print("| 场景 | 慢帧数 | 最慢 | 均值 |")
    print("|---|---|---|---|")
    for name in sorted(set(a.get("scenarios", {})) | set(b.get("scenarios", {}))):
        ra = a.get("scenarios", {}).get(name, {})
        rb = b.get("scenarios", {}).get(name, {})
        print(f"| {name} | {ra.get('count', 0)} → {rb.get('count', 0)} |"
              f" {ra.get('worst', 0):.1f} → {rb.get('worst', 0):.1f} ms |"
              f" {ra.get('mean', 0)} → {rb.get('mean', 0)} ms |")


def main() -> int:
    ap = argparse.ArgumentParser(description="Branchbase 页面重建帧率基线（FrameWatch 取数）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("capture", help="采集一段窗口内的慢帧日志")
    c.add_argument("--label", help="这轮基线的名字（进文件名）")
    c.add_argument("--seconds", type=int, default=180, help="采集时长（默认 180s）")
    c.add_argument("--out", help="输出 .log 路径（默认 tools/perf/reports/<label>-<时间>.log）")
    c.set_defaults(func=cmd_capture)

    a = sub.add_parser("analyze", help="解析一份日志切片并出报表")
    a.add_argument("--log", required=True)
    a.add_argument("--label")
    a.add_argument("--md")
    a.add_argument("--json")
    a.add_argument("--from", dest="since", help="只看 HH:MM:SS 之后（同一天内）")
    a.add_argument("--to", dest="until", help="只看 HH:MM:SS 之前")
    a.add_argument("--verbose", action="store_true", help="附逐条明细")
    a.set_defaults(func=cmd_analyze)

    r = sub.add_parser("run", help="采集 + 立刻出报表")
    r.add_argument("--label")
    r.add_argument("--seconds", type=int, default=180)
    r.add_argument("--out")
    r.add_argument("--verbose", action="store_true")
    r.set_defaults(func=cmd_run)

    m = sub.add_parser("compare", help="改前 / 改后对比两份 json")
    m.add_argument("--before", required=True)
    m.add_argument("--after", required=True)
    m.set_defaults(func=cmd_compare)

    w = sub.add_parser("watch", help="持续跟读设备日志（人操作期间用，随时可停）")
    w.add_argument("--label")
    w.add_argument("--out")
    w.add_argument("--minutes", type=int, default=15, help="最长跟读时长（默认 15 分钟）")
    w.add_argument("--interval", type=int, default=5, help="轮询间隔秒（默认 5s）")
    w.set_defaults(func=cmd_watch)

    args = ap.parse_args()
    try:
        args.func(args)
    except KeyboardInterrupt:
        print("\n[中断] 已停止", file=sys.stderr)
        return 130
    except Exception as exc:  # 取数失败要把原因说清楚，不要吞
        print(f"[失败] {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
