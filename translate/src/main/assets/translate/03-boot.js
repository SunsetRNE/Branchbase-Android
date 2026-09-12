/*
 * 沉浸式翻译 · 03 启动与命令（开关 / 自动翻译 / 显示方式 / 视口策略 / 滚动兜底）
 * ──────────────────────────────────────────────────────────────────────────
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
 * **总开关只有一处**：设置里的「自动翻译正文」。页内开关（工具面板上的「本页翻译」）
 * 只对当前页面有效、**不写 localStorage** —— 一旦持久化，用户「在设置里关掉」之后
 * 再次打开正文页时，上次留下的 `bb_translate_on=1` 会把翻译重新拉起来，
 * 表现为「设置里关了，仓库页的悬浮球还在」。
 *
 * ## command()：原生工具面板的唯一入口
 *
 * 悬浮球与工具面板是**原生 Compose 控件**（app 的 ui/translate/TranslateBubble.kt），
 * 面板上的开关与操作都通过 `window.__bbIT.command(name, arg)` 打进来 ——
 * 页面只负责执行并回推状态，不参与界面。设置类的键（显示方式 / 样式 / 目标语言 /
 * 自动翻译）由原生侧落盘，这里只应用当前效果，所以页面**没有任何写设置的通道**。
 */
(function () {
  'use strict';

  var IT = window.__bbIT;
  if (!IT) return;

  var state = IT.state;
  var RULES = IT.rules;
  var SCROLL_THROTTLE_MS = 200;
  var STYLES = ['card', 'underline', 'plain'];

  function storage(fn) {
    try { return fn(); } catch (e) { return null; }
  }

  /** 重数候选段数：面板的进度（已译 / 候选）读它，打开面板与重扫后各刷一次。 */
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
    start();
    IT.report();
  }

  function off() {
    state.on = false;
    document.body.classList.remove('bb-tr-on');
    document.body.classList.remove('bb-tr-only');
    IT.dom.disconnect();
    IT.report();
  }

  function applyDisplayMode() {
    document.body.classList.add('bb-tr-on');
    // 深色主题：译文卡片换深色配色（样式在 translate.css 的 body.bb-dark 段）
    document.body.classList.toggle('bb-dark', IT.cfg.dark === true);
    // 仅译文模式：由 CSS 隐藏原文块（.bb-tr-src），译文容器保持可见
    document.body.classList.toggle('bb-tr-only', !state.dual);
    document.body.setAttribute('data-bb-style', state.style || 'card');
  }

  /* ───────────── 原生面板调用的写入口（即时生效，落盘由原生负责） ───────────── */

  function setDual(dual) {
    state.dual = !!dual;
    applyDisplayMode();
    IT.report();
  }

  function setStyle(style) {
    if (STYLES.indexOf(style) < 0) return;
    state.style = style;
    document.body.setAttribute('data-bb-style', style);
    IT.report();
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
    IT.dom.clear();
    refreshCandidates();
    if (state.on) {
      if (state.longPage) IT.dom.observeAll();
      IT.retry();
    } else {
      IT.report();
    }
  }

  /**
   * 原生工具面板下发的命令。
   *
   * 命令名与 Kotlin 的 `TranslatePageCommands` 常量一一对应；**不认识的命令直接忽略**
   * （老 App 与新脚本混装时不会做出意外动作）。
   */
  function command(name, arg) {
    switch (name) {
      case 'on': on(); break;
      case 'off': off(); break;
      case 'dual': setDual(arg === '1' || arg === 'true'); break;
      case 'style': setStyle(String(arg)); break;
      case 'target': setTarget(String(arg)); break;
      case 'retry': IT.retry(); break;
      case 'scan-visible':
        refreshCandidates();
        IT.enqueue(IT.dom.scan(true));
        break;
      case 'scan-all':
        refreshCandidates();
        IT.enqueue(IT.dom.scan(false));
        break;
      case 'clear':
        // 队列里可能还压着「清空前收集到的段落」，一起丢掉，否则译文马上又长回来
        state.queue = [];
        state.emptyRuns = 0;
        IT.dom.clear();
        refreshCandidates();
        IT.report();
        break;
      default: break;
    }
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
    document.body.setAttribute('data-bb-style', state.style || 'card');
    document.body.classList.toggle('bb-dark', IT.cfg.dark === true);
    // ⚠️ 只有总开关（设置里的「自动翻译正文」）能决定进页面时开不开。
    // 别在这里加回「上次的页内开关」：那正是「设置里关掉后悬浮球仍出现在仓库页」的原因。
    if (state.auto) on();
    else IT.report();
    // 原生侧的熔断状态（额度用尽 / 连续失败）在启动时同步一次，面板直接显示正确文案
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
  IT.command = command;
  IT.refreshCandidates = refreshCandidates;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
