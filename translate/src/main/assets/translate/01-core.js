/*
 * 沉浸式翻译 · 01 核心（配置 / 状态机 / 原生桥 / 批量队列）
 * ────────────────────────────────────────────────────────────
 * 这一层不碰 DOM，也不碰界面，只回答三个问题：
 *   1. 这段文字值不值得翻（与原生侧同一套阈值，来自注入的 rules）；
 *   2. 这一批文本怎么发给原生、结果怎么收回来；
 *   3. 现在整体处于什么状态（翻译中 / 翻完 / 额度用尽 / 失败）。
 *
 * 与原生侧的分工：Kotlin 通过 `BBTranslate.request(id, to, json)` 收文本，
 * 串行翻译后回调 `window.__bbTranslated(id, to, json)`。**页面不关心用哪家翻译服务**，
 * 也不做重试与缓存 —— 那些需要跨段落视角，只有原生侧做得了。
 */
(function () {
  'use strict';

  if (window.__bbIT) return;

  var CFG = window.__bbTranslate || {};
  // 规则来自原生侧注入（唯一真源在 Kotlin 的 PageRules），这里只使用、不定义
  var RULES = CFG.rules || {
    minLen: 2, maxLen: 1200, latinRun: 3, hanRatioMax: 0.5, minHan: 4,
    immediateLimit: 5000, skipPatterns: []
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

  var state = {
    on: false,
    auto: CFG.enabled === true,   // 设置里的「自动翻译正文」
    to: CFG.to || 'zh-CN',
    dual: CFG.dual !== false,     // true = 原文+译文对照；false = 仅译文
    engine: 'ok',                 // 原生侧状态：ok | quota | paused
    busy: false,
    count: 0,                     // 已插入的译文段数
    emptyRuns: 0,
    seq: 0,
    callbacks: {},
    queue: [],
    inflight: false,
    seen: (typeof WeakSet === 'function') ? new WeakSet() : null
  };

  /* ───────────── 文本判定（与 Kotlin 的 TranslateTextPolicy 同规则） ───────────── */

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

  function needsTranslation(text) {
    var s = normalize(text);
    if (!s || isInvisible(s)) return false;
    for (var i = 0; i < SKIP.length; i++) {
      if (SKIP[i].test(s)) return false;
    }
    if (s.length < RULES.minLen || s.length > RULES.maxLen) return false;
    if (String(state.to).indexOf('zh') === 0) {
      return longestLatinRun(s) >= RULES.latinRun && hanCount(s) <= s.length * RULES.hanRatioMax;
    }
    return hanCount(s) >= RULES.minHan;
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

  // 原生侧每批结束后回推状态：额度用尽 / Key 无效 / 连续失败暂停 / 正常
  // （04-boot.js 之前（脚本还在解析中）也可能被回调，所以对 IT.ui 做一次存在性保护）
  window.__bbTranslateStatus = function (engineState) {
    var prev = state.engine;
    state.engine = engineState || 'ok';
    if (!IT.ui) return;
    // 原生侧是**确定**知道「Key 无效 / 额度用尽」的，那就立刻提示并换按钮状态，
    // 不必等页面自己攒够三次空批次才反应过来
    if (state.engine !== prev && state.engine !== 'ok') {
      IT.ui.fail(state.engine);
      return;
    }
    IT.ui.refresh();
  };

  /* ───────────── 队列与批量 ───────────── */

  function enqueue(items) {
    if (!items || !items.length) return;
    for (var i = 0; i < items.length; i++) state.queue.push(items[i]);
    pump();
  }

  function pump() {
    if (state.inflight || !state.on) return;
    if (!state.queue.length) { state.busy = false; IT.ui.refresh(); return; }

    var batch = state.queue.splice(0, BATCH);
    state.inflight = true;
    state.busy = true;
    IT.ui.refresh();

    requestTranslate(batch.map(function (x) { return x.text; }), function (results) {
      state.inflight = false;
      var inserted = 0;
      for (var i = 0; i < batch.length; i++) {
        var tr = results[i];
        if (tr) {
          IT.dom.insert(batch[i].el, tr);
          state.count++;
          inserted++;
        }
      }
      state.emptyRuns = inserted > 0 ? 0 : state.emptyRuns + 1;
      state.busy = false;
      IT.ui.refresh();

      // 整批都空：通常是断网 / 额度用尽 / Key 无效 / 服务异常，继续发只是浪费
      if (state.emptyRuns >= MAX_EMPTY_RUNS) {
        var blocked = (state.engine === 'auth' || state.engine === 'quota' || state.engine === 'paused')
          ? state.engine
          : 'failed';
        IT.ui.fail(blocked);
        return;
      }
      setTimeout(pump, BATCH_GAP_MS);
    });
  }

  /** 用户点「重试」：清空熔断与队列计数，重新扫描当前视口。 */
  function retry() {
    state.emptyRuns = 0;
    state.engine = 'ok';
    try { if (window.BBTranslate && window.BBTranslate.retry) window.BBTranslate.retry(); } catch (e) {}
    IT.ui.refresh();
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
    retry: retry
  };
})();
