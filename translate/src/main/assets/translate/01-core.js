/*
 * 沉浸式翻译 · 01 核心（配置 / 状态机 / 原生桥 / 批量队列 / 状态上报）
 * ────────────────────────────────────────────────────────────────
 * 这一层不碰界面，只回答四个问题：
 *   1. 这段文字值不值得翻（与原生侧同一套阈值，来自注入的 rules）；
 *   2. 这一批文本怎么发给原生、结果怎么收回来；
 *   3. 现在整体处于什么状态（翻译中 / 翻完 / 额度用尽 / 失败）；
 *   4. 怎么把状态**推给原生** —— 悬浮球与工具面板是原生 Compose 控件
 *      （见 app 的 ui/translate/TranslateBubble.kt），页面不再自己画按钮。
 *
 * 与原生侧的分工：Kotlin 通过 `BBTranslate.request(id, to, json)` 收文本，
 * 串行翻译后回调 `window.__bbTranslated(id, to, json)`；页面把状态用
 * `BBTranslate.report(json)` 推回去，原生用 `window.__bbIT.command(name, arg)`
 * 下发开关与操作。**页面不关心用哪家翻译服务**，也不做重试与缓存 —— 那些需要
 * 跨段落视角，只有原生侧做得了。
 */
(function () {
  'use strict';

  if (window.__bbIT) return;

  var CFG = window.__bbTranslate || {};
  // 规则来自原生侧注入（唯一真源在 Kotlin 的 PageRules），这里只使用、不定义
  var RULES = CFG.rules || {
    minLen: 2, maxLen: 1200, latinRun: 3, hanRatioMax: 0.5, minHan: 4,
    immediateLimit: 5000, skipPatterns: [],
    matchPolicy: 'match', matchMinLen: 4, maxMatchParts: 6
  };

  // 跳过规则（纯数字 / 纯 URL / 版本号 / 单个 @提及、#编号 / 标签残留）同样来自原生侧，
  // 这里只编译成正则使用 —— 两边判定同一件事，规则改动不会漏掉一边
  var SKIP = [];
  (function () {
    var pats = RULES.skipPatterns || [];
    for (var i = 0; i < pats.length; i++) {
      try { SKIP.push(new RegExp(pats[i])); } catch (e) { /* 规则坏了就少一条，不影响翻译 */ }
    }
  })();

  var BATCH = 3;            // 每批段数：后端对并发不友好，原生侧还会再串行化
  var MAX_EMPTY_RUNS = 3;   // 连续几批「一段都没翻出来」就认为服务不可用
  var BATCH_GAP_MS = 120;   // 批次间隔，给原生侧的串行闸门留出喘息
  var REPORT_GAP_MS = 150;  // 状态上报节流：翻译时每批都会变，别每段都过一次桥

  var state = {
    on: false,
    auto: CFG.enabled === true,   // 设置里的「自动翻译正文」（总开关）
    to: CFG.to || 'zh-CN',
    dual: CFG.dual !== false,     // true = 原文+译文对照；false = 仅译文
    style: CFG.style || 'card',
    engine: 'ok',                 // 原生侧状态：ok | quota | paused | auth
    failed: false,
    busy: false,
    count: 0,                     // 已插入的译文段数
    emptyRuns: 0,
    // 本页候选统计（段数 + 字符数）：决定「一次翻完还是视口优先」，也是面板进度的来源
    candidates: { count: 0, chars: 0 },
    longPage: false,
    seq: 0,
    callbacks: {},
    queue: [],
    inflight: false,
    seen: (typeof WeakSet === 'function') ? new WeakSet() : null
  };

  /* ───────────── 文本判定（与 Kotlin 的 TranslateDecisionEngine 同规则） ─────────────
     这里只做**粗筛**：把明显不用翻的段落挡在桥前面（省一次往返，也省一次额度）。
     权威判定在原生侧 —— 只有那里同时看得到设置、缓存与占位符保护。
     所以这份镜像宁可稍微宽松（多送一段，由原生侧判掉），也不要漏送原生侧想翻的段落。 */

  function normalize(s) {
    return String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
  }

  function longestLatinRun(s) {
    var best = 0, cur = 0;
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if ((c >= 65 && c <= 90) || (c >= 97 && c <= 122)) {
        cur++;
        if (cur > best) best = cur;
      } else {
        cur = 0;
      }
    }
    return best;
  }

  function hanCount(s) {
    var m = s.match(/[\u4e00-\u9fff]/g);
    return m ? m.length : 0;
  }

  // 零宽字符 / 软连字符 / BOM：GitHub 正文里不少，去掉后为空说明这段没有内容
  function isInvisible(s) {
    return s.replace(/[\s\u200b-\u200d\ufeff\u00ad]/g, '') === '';
  }

  /* 字符分类：区间与 Kotlin 的 isHanChar / isLatinLetter **逐段对齐**
     （JS 没有 Char.isLetter，Kotlin 那边也因此写死了同一批区间）。
     改这里必须同时改 TranslateTextPolicy.kt —— 两边不一致的坏法是
     「页面筛掉了原生其实想翻的段落」，页面上看不出任何报错。 */
  function isHanCode(c) {
    return c >= 0x4e00 && c <= 0x9fff;
  }

  function isLatinCode(c) {
    if (c < 128) return (c >= 65 && c <= 90) || (c >= 97 && c <= 122);
    return (c >= 0x00c0 && c <= 0x024f) || (c >= 0x0370 && c <= 0x04ff) ||
      (c >= 0x3040 && c <= 0x30ff) || (c >= 0xac00 && c <= 0xd7af);
  }

  /**
   * 一段的文字构成：目标文字 / 外语字母各多少，段内有哪些「值得单独翻的片段」。
   *
   * 片段 = 连续外语字母，**吸收夹在中间的中性字符**（空格 / 标点 / 数字）：
   * `npm run dev` 是一个片段而不是三个词 —— 逐词送翻会得到三份不知道上下文
   * 的译文，拼起来是「npm 运行 开发」这种读不通的东西（与原生侧同一套切法）。
   */
  function kindAt(s, i, toZh) {
    var c = s.charCodeAt(i);
    if (isHanCode(c)) return toZh ? 'T' : 'F';
    if (isLatinCode(c)) return toZh ? 'F' : 'T';
    return 'N';
  }

  function analyze(s) {
    var toZh = String(state.to).indexOf('zh') === 0;
    var n = s.length, runs = [], target = 0, foreign = 0, i, k, j;

    for (i = 0; i < n;) {
      k = kindAt(s, i, toZh);
      j = i + 1;
      while (j < n && kindAt(s, j, toZh) === k) j++;
      runs.push({ k: k, start: i, end: j });
      if (k === 'T') target += j - i;
      else if (k === 'F') foreign += j - i;
      i = j;
    }

    var parts = [];
    i = 0;
    while (i < runs.length) {
      if (runs[i].k !== 'F') { i++; continue; }
      var last = i, m = i + 1;
      // 吸收「内部中性」：中性段后面**紧跟**外语段时，它属于同一个片段
      while (m + 1 < runs.length && runs[m].k === 'N' && runs[m + 1].k === 'F') { last = m + 1; m += 2; }
      var part = normalize(s.substring(runs[i].start, runs[last].end));
      if (worthPart(part, toZh)) parts.push(part);
      i = last + 1;
    }
    return { target: target, foreign: foreign, parts: parts };
  }

  /** 片段值不值得单独翻（阈值同样来自注入的 rules）。 */
  function worthPart(part, toZh) {
    if (!part || part.length < (RULES.matchMinLen || 4)) return false;
    for (var i = 0; i < SKIP.length; i++) if (SKIP[i].test(part)) return false;
    return toZh ? longestLatinRun(part) >= RULES.latinRun : hanCount(part) >= RULES.minHan;
  }

  /**
   * 这一段的判定（粗筛）。
   *
   * 与原生 `TranslateDecisionEngine.decide` 同序：
   * ① 段内没有外语内容 → 一致，不翻；② 外语太零碎（`CI` / `a`）→ 不值得翻；
   * ③ 外语为主 → 整段翻；④ 目标文字为主 → 段内确有需要翻的片段才送（「不翻」规则下挡掉）。
   */
  function needsTranslation(text) {
    var s = normalize(text);
    if (!s || isInvisible(s)) return false;
    for (var i = 0; i < SKIP.length; i++) {
      if (SKIP[i].test(s)) return false;
    }
    if (s.length < RULES.minLen || s.length > RULES.maxLen) return false;

    var toZh = String(state.to).indexOf('zh') === 0;
    var a = analyze(s);

    // ① 「所需要的目标文字」与被翻译目标一致：段内没有外语内容
    if (a.foreign === 0) return false;
    // ② 外语太零碎：整段判定与片段判定共用同一个门槛
    var worth = toZh ? (longestLatinRun(s) >= RULES.latinRun) : (hanCount(s) >= RULES.minHan);
    if (!worth) return false;
    // ③ 外语为主（含整段外语）：整段翻
    if (a.target <= (a.target + a.foreign) * RULES.hanRatioMax) return true;
    // ④ 目标文字为主：只有存在值得单独翻的片段才送
    if (a.parts.length === 0) return false;
    return RULES.matchPolicy !== 'skip';
  }

  /* ───────────── 原生桥（异步：request 立即返回，结果走 __bbTranslated） ───────────── */

  function requestTranslate(texts, cb) {
    if (!window.BBTranslate || !texts.length) { cb([]); return; }
    var id = 'r' + (++state.seq);
    state.callbacks[id] = cb;
    try {
      window.BBTranslate.request(id, state.to, JSON.stringify(texts));
    } catch (e) {
      delete state.callbacks[id];
      cb([]);
    }
  }

  window.__bbTranslated = function (id, toLang, json) {
    var cb = state.callbacks[id];
    if (!cb) return;
    delete state.callbacks[id];
    var list = [];
    try { list = JSON.parse(json) || []; } catch (e) { list = []; }
    cb(list);
  };

  // 原生侧每批结束后回推状态：额度用尽 / Key 无效 / 连续失败暂停 / 正常。
  // 原生侧是**确定**知道这些的，所以立刻换状态并上报，不必等页面自己攒够三次空批次。
  window.__bbTranslateStatus = function (engineState) {
    state.engine = engineState || 'ok';
    if (state.engine !== 'ok') state.failed = false;
    report();
  };

  /* ───────────── 状态上报（页面 → 原生） ───────────── */

  function deriveStatus() {
    if (state.engine === 'auth') return 'auth';
    if (state.engine === 'quota') return 'quota';
    if (state.engine === 'paused') return 'paused';
    if (state.failed) return 'failed';
    if (state.busy || state.inflight) return 'translating';
    return state.count > 0 ? 'done' : 'idle';
  }

  /** 当前快照（字段与 Kotlin 的 TranslatePageSnapshot 一一对应）。 */
  function snapshot() {
    return {
      on: state.on === true,
      status: deriveStatus(),
      translated: state.count || 0,
      candidates: (state.candidates && state.candidates.count) || 0,
      chars: (state.candidates && state.candidates.chars) || 0
    };
  }

  var reportTimer = null;
  var lastReportAt = 0;

  function sendReport() {
    try {
      if (window.BBTranslate && window.BBTranslate.report) {
        window.BBTranslate.report(JSON.stringify(snapshot()));
      }
    } catch (e) { /* 老原生没有这个方法：界面退化成不显示，不影响翻译本身 */ }
  }

  /**
   * 把状态推给原生（节流 [REPORT_GAP_MS]，末尾补一次）。
   *
   * 翻译时每批都会调它，不节流就是每段都过一次桥；而悬浮球上的数字晚 150ms
   * 更新没人看得出来。
   */
  function report() {
    var now = Date.now();
    var wait = REPORT_GAP_MS - (now - lastReportAt);
    if (wait <= 0) {
      lastReportAt = now;
      sendReport();
      return;
    }
    if (reportTimer) return;
    reportTimer = setTimeout(function () {
      reportTimer = null;
      lastReportAt = Date.now();
      sendReport();
    }, wait);
  }

  /** 失败态（原生推来的「Key 无效 / 额度用尽」或页面自己攒够空批次）。 */
  function fail(kind) {
    state.failed = (kind === 'failed');
    if (kind === 'auth' || kind === 'quota' || kind === 'paused') state.engine = kind;
    report();
  }

  /* ───────────── 队列与批量 ───────────── */

  function enqueue(items) {
    if (!items || !items.length) return;
    for (var i = 0; i < items.length; i++) state.queue.push(items[i]);
    pump();
  }

  function pump() {
    if (state.inflight || !state.on) return;
    if (!state.queue.length) { state.busy = false; report(); return; }

    var batch = state.queue.splice(0, BATCH);
    state.inflight = true;
    state.busy = true;
    report();

    requestTranslate(batch.map(function (x) { return x.text; }), function (results) {
      state.inflight = false;
      var inserted = 0, failures = 0;
      for (var i = 0; i < batch.length; i++) {
        // 元素是**混合类型**（原生侧 TranslatePagePayload.encode 产出）：
        //   ""      判定为不需要翻（本身就是目标文字 / 译后与原文一致）→ 什么都不做
        //   "译文"   整段译文 → 插一张译文块
        //   {...}   匹配性译文 → 插一组「片段 → 译文」配对
        //   null    这一段翻译失败 → 计一次失败
        var tr = results[i];
        if (tr === null || tr === undefined) { failures++; continue; }
        if (typeof tr === 'string') {
          if (!tr) continue;
          IT.dom.insert(batch[i].el, tr);
          state.count++;
          inserted++;
          continue;
        }
        if (tr && tr.parts && tr.parts.length) {
          IT.dom.insert(batch[i].el, tr);
          state.count++;
          inserted++;
        }
      }
      // **只有真的失败了才累计失败批次**：判定跳过（空串）也会「一段都没插进去」，
      // 混在一起时，一页全是中文的正文会被误报成「翻译失败」
      state.emptyRuns = (inserted > 0 || failures === 0) ? 0 : state.emptyRuns + 1;
      state.busy = false;
      report();

      // 整批都空：通常是断网 / 额度用尽 / Key 无效 / 服务异常，继续发只是浪费
      if (state.emptyRuns >= MAX_EMPTY_RUNS) {
        var blocked = (state.engine === 'auth' || state.engine === 'quota' || state.engine === 'paused')
          ? state.engine
          : 'failed';
        fail(blocked);
        return;
      }
      setTimeout(pump, BATCH_GAP_MS);
    });
  }

  /** 面板上的「重试」：清空熔断与队列计数，重新扫描当前视口。 */
  function retry() {
    state.emptyRuns = 0;
    state.engine = 'ok';
    state.failed = false;
    try { if (window.BBTranslate && window.BBTranslate.retry) window.BBTranslate.retry(); } catch (e) {}
    report();
    enqueue(IT.dom.scan(true));
  }

  var IT = window.__bbIT = {
    cfg: CFG,
    rules: RULES,
    state: state,
    util: {
      normalize: normalize,
      needsTranslation: needsTranslation
    },
    requestTranslate: requestTranslate,
    enqueue: enqueue,
    retry: retry,
    fail: fail,
    report: report,
    snapshot: snapshot,
    deriveStatus: deriveStatus
  };
})();
