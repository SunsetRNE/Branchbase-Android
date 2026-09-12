/*
 * 沉浸式翻译 · 05 启动（开关 / 自动翻译 / 显示方式 / 视口策略 / 滚动兜底）
 * ──────────────────────────────────────────────────────────────────────
 * 启动时决定了两件事，都由**设置**驱动、且在首帧前就位（配置由原生侧注入，
 * 见 Kotlin `TranslateSettings.injectScript`）：
 *
 *   1. 要不要自动开始翻（设置里的「自动翻译正文」优先于上次的页内开关状态 ——
 *      设置是用户的明确意图，不该被一次临时开关覆盖）；
 *   2. 怎么翻：
 *      · 整页候选文本 ≤ immediateLimit（默认 5000 字符）→ **一次全翻**，
 *        短 README 点一下整页就好了，不需要滚动推进；
 *      · 超过 → **视口优先**：先把当前看得见的翻出来，其余等滚动到视口再翻
 *        （长页面/长 issue 一次几百个请求会把免费额度烧光，也会让首屏卡住）。
 *
 * 页内开关状态记在 localStorage，同一台设备再次打开沿用上次的选择。
 *
 * 另外这里集中了「显示方式 / 译文样式 / 目标语言」三个**即时生效并落盘**的写入口
 * （悬浮球面板上的快捷设置调它们）：面板只负责交互，状态机仍然只有这一处。
 */
(function () {
  'use strict';

  var IT = window.__bbIT;
  if (!IT) return;

  var state = IT.state;
  var RULES = IT.rules;
  var ON_KEY = 'bb_translate_on';
  var SCROLL_THROTTLE_MS = 200;
  var STYLES = ['card', 'underline', 'plain'];

  function storage(fn) {
    try { return fn(); } catch (e) { return null; }
  }

  /** 重数候选段数：悬浮面板的进度（已译 / 候选）读它，打开面板与重扫后各刷一次。 */
  function refreshCandidates() {
    try {
      state.candidates = IT.dom.candidates();
    } catch (e) { /* 统计失败不影响翻译本身 */ }
  }

  function on() {
    if (state.on) return;
    state.on = true;
    state.failed = false;
    applyDisplayMode();
    storage(function () { localStorage.setItem(ON_KEY, '1'); });
    IT.ui.refresh();
    start();
  }

  function off() {
    state.on = false;
    document.body.classList.remove('bb-tr-on');
    document.body.classList.remove('bb-tr-only');
    IT.dom.disconnect();
    storage(function () { localStorage.setItem(ON_KEY, '0'); });
    IT.ui.refresh();
  }

  function applyDisplayMode() {
    document.body.classList.add('bb-tr-on');
    // 深色主题：译文卡片与悬浮球换成深色配色（样式在 translate.css 的 body.bb-dark 段）
    document.body.classList.toggle('bb-dark', IT.cfg.dark === true);
    // 仅译文模式：由 CSS 隐藏原文块（.bb-tr-src），译文容器保持可见
    document.body.classList.toggle('bb-tr-only', !state.dual);
    document.body.setAttribute('data-bb-style', state.settings.style || 'card');
  }

  /* ───────────── 面板上的快捷设置（即时生效 + 落盘） ───────────── */

  function setDual(dual) {
    if (state.dual === !!dual) return;
    state.dual = !!dual;
    state.settings.dual = state.dual;
    IT.pref('dual', state.dual ? '1' : '0');
    applyDisplayMode();
    IT.ui.refresh();
    IT.ui.showTip(state.dual ? '已切换：原文 + 译文对照' : '已切换：仅显示译文');
  }

  function setStyle(style) {
    if (STYLES.indexOf(style) < 0 || state.settings.style === style) return;
    state.settings.style = style;
    IT.pref('style', style);
    document.body.setAttribute('data-bb-style', style);
    IT.ui.refresh();
    IT.ui.showTip('译文样式已切换（不重翻）');
  }

  /**
   * 切目标语言。
   *
   * 已插入的译文是**旧语言**的，留着只会误导，所以清掉重来；
   * 缓存按 (源语言, 目标语言, 原文) 分键，换语言不会命中旧译文。
   */
  function setTarget(code) {
    var next = code === 'en' ? 'en' : 'zh-CN';
    if (next === state.to) return;
    state.to = next;
    state.settings.target = next;
    IT.pref('target', next);
    IT.dom.clear();
    refreshCandidates();
    IT.ui.refresh();
    if (state.on) {
      if (state.longPage) IT.dom.observeAll();
      IT.retry();
    }
    IT.ui.showTip(next === 'en' ? '已切换目标语言：English' : '已切换目标语言：中文');
  }

  function start() {
    // 先估算整页体量（纯查询，不占用段落），顺带把统计留给面板的进度条
    var c = IT.dom.candidates();
    state.candidates = c;
    state.longPage = c.chars > RULES.immediateLimit;

    // 视口内的先翻：点一下立刻能看到结果
    IT.enqueue(IT.dom.scan(true));

    if (!state.longPage) {
      IT.enqueue(IT.dom.scan(false));   // 短页面：剩下的也一次全翻
    } else {
      IT.dom.observeAll();              // 长页面：滚动到视口再翻
      if (!window.IntersectionObserver) bindScrollFallback();
    }
  }

  /* WebView 极老 / IntersectionObserver 缺失时的兜底：滚动时按视口扫描（节流） */
  var scrollTimer = null;
  function bindScrollFallback() {
    if (bindScrollFallback.bound) return;
    bindScrollFallback.bound = true;
    window.addEventListener('scroll', function () {
      if (!state.on || scrollTimer) return;
      scrollTimer = setTimeout(function () {
        scrollTimer = null;
        if (state.on) IT.enqueue(IT.dom.scan(true));
      }, SCROLL_THROTTLE_MS);
    }, { passive: true });
  }

  function boot() {
    document.body.setAttribute('data-bb-style', state.settings.style || 'card');
    document.body.classList.toggle('bb-dark', IT.cfg.dark === true);
    var saved = storage(function () { return localStorage.getItem(ON_KEY); });
    if (state.auto || saved === '1') on();
    else IT.ui.refresh();
    // 原生侧的熔断状态（额度用尽 / 连续失败）在启动时同步一次，球直接显示正确文案
    storage(function () {
      if (window.BBTranslate && window.BBTranslate.state) {
        window.__bbTranslateStatus(window.BBTranslate.state());
      }
    });
  }

  IT.on = on;
  IT.off = off;
  IT.setDual = setDual;
  IT.setStyle = setStyle;
  IT.setTarget = setTarget;
  IT.refreshCandidates = refreshCandidates;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
