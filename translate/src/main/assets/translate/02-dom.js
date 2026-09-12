/*
 * 沉浸式翻译 · 02 DOM（段落收集 / 跳过规则 / 译文插入 / 视口观察）
 * ────────────────────────────────────────────────────────────
 * 本文件只做四件事：
 *   1. 找出「块级正文段落」，并跳过代码、表格代码、已有译文、被显式排除的节点；
 *   2. 把译文插入为**原文的兄弟节点**（原文一个字都不改）；
 *   3. 用 IntersectionObserver 做视口优先，滚动到哪翻到哪；
 *   4. 给悬浮面板提供统计（候选段数 / 字符数）与「清空本页译文」。
 *
 * 为什么译文用独立兄弟节点，而不是把译文写回原文的文本节点：
 * 把译文写回原节点之后，「切回原文」「切换对照/仅译文」都必须依赖一份额外保存的原文副本，
 * 状态一多就会互相打架（网页版旧实现正是如此，后来改成了独立容器）。
 * 独立容器则天然幂等：译文容器存在 = 已翻译；删掉容器 = 完全还原。
 */
(function () {
  'use strict';

  var IT = window.__bbIT;
  if (!IT) return;

  var state = IT.state;

  // 只取「块级正文」：标题 / 段落 / 列表项 / 引用 / 表格单元格 / 定义列表
  var SELECTOR = 'p, li, h1, h2, h3, h4, h5, h6, blockquote, td, dd';

  // 命中即不翻：代码块、行内代码、语法高亮、已插入的译文、标题锚点、显式排除
  // （.blob-code / table.diff 是 GitHub 文件页与 diff 的代码容器，属于「误翻重灾区」）
  var SKIP_SELECTOR = [
    'pre', 'code', 'kbd', 'samp', 'var', 'tt', 'svg', 'math',
    '.highlight', '.blob-code', '.diff-table', 'table.diff',
    '.bb-tr', '.bb-tr-src', '.anchor', 'script', 'style', 'noscript',
    '[data-bb-skip]', '[contenteditable="true"]'
  ].join(',');

  function isSkipped(el) {
    return !!(el.closest && el.closest(SKIP_SELECTOR));
  }

  // 外层块里还套着内层块（GitHub 松列表 <li><p>…</p></li>）：只取内层，
  // 否则 li 与 p 会各翻一次，同一段话出现两条译文
  function hasBlockChild(el) {
    return !!(el.querySelector && el.querySelector(SELECTOR));
  }

  // 纯查询：这段文本值得翻吗（**不产生副作用**，供「先估算整页体量」用）
  function candidateText(el) {
    if (!el || isSkipped(el) || hasBlockChild(el)) return null;
    var text = IT.util.normalize(el.innerText || el.textContent || '');
    return IT.util.needsTranslation(text) ? text : null;
  }

  function collect(el) {
    if (state.seen && state.seen.has(el)) return null;
    var text = candidateText(el);
    if (text == null) return null;
    if (state.seen) state.seen.add(el);
    return { el: el, text: text };
  }

  /* ───────────── 译文插入 ───────────── */

  function insert(el, translated) {
    var next = el.nextElementSibling;
    if (next && next.classList && next.classList.contains('bb-tr')) {
      next.textContent = translated;   // 同一段重复翻译（换目标语言）时直接覆盖
      return;
    }
    var div = document.createElement('div');
    div.className = 'bb-tr';
    div.setAttribute('lang', state.to);
    div.textContent = translated;
    // 标记源元素：仅译文模式下由 CSS 隐藏它（切回对照只需去掉 body 上的类）
    if (el.classList) el.classList.add('bb-tr-src');
    // 列表项里的译文缩进到同一层级，避免看起来像另一个列表项
    if (el.tagName === 'LI') div.style.marginLeft = '1.2em';
    if (el.parentNode) el.parentNode.insertBefore(div, el.nextSibling);
  }

  /* ───────────── 视口优先 ───────────── */

  var observer = null;

  function inViewport(el) {
    var r = el.getBoundingClientRect();
    var h = window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 0;
    return r.bottom >= -200 && r.top <= h + 200;   // 与 observer 的 rootMargin 对齐
  }

  /**
   * 扫描段落。
   *
   * @param onlyViewport true = 只取当前视口内的（手动点「译」时先把看得见的翻出来，
   *   用户立刻能看到结果；剩下的交给 IntersectionObserver 滚动触发）
   */
  function scan(onlyViewport) {
    var nodes = document.querySelectorAll(SELECTOR);
    var hits = [];
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (isSkipped(el)) continue;
      if (onlyViewport && !inViewport(el)) continue;
      var item = collect(el);
      if (item) hits.push(item);
    }
    return hits;
  }

  /** 观察剩余段落：滚动到视口内才收集入队（长页面不一次烧光额度）。 */
  function observeAll() {
    if (!window.IntersectionObserver) return;   // 没有则退化为「启动时扫一次视口 + 滚动兜底」
    var nodes = document.querySelectorAll(SELECTOR);
    if (!observer) {
      observer = new IntersectionObserver(function (entries) {
        var hits = [];
        for (var i = 0; i < entries.length; i++) {
          var e = entries[i];
          if (!e.isIntersecting) continue;
          observer.unobserve(e.target);       // 一次性：进了视口就摘掉观察，避免重复入队
          var item = collect(e.target);
          if (item) hits.push(item);
        }
        if (hits.length) IT.enqueue(hits);
      }, { rootMargin: '200px 0px' });
    }
    for (var j = 0; j < nodes.length; j++) {
      if (isSkipped(nodes[j])) continue;
      if (state.seen && state.seen.has(nodes[j])) continue;
      observer.observe(nodes[j]);
    }
  }

  /** 整页候选文本的总字符数（决定「一次翻完」还是「视口优先」，见 05-boot.js）。 */
  function totalCandidateLength() {
    return candidates().chars;
  }

  /**
   * 整页候选统计（段数 + 字符数）。
   *
   * 悬浮面板要用它算进度（已译 / 候选），启动时也用它判断「这页有没有东西可翻」
   * —— 没有就不显示悬浮球。刻意与 [totalCandidateLength] 共用一个实现：
   * 同一件事（扫描 + 判定）只写一遍，两边不会算出不同的数。
   */
  function candidates() {
    var nodes = document.querySelectorAll(SELECTOR);
    var count = 0, chars = 0;
    for (var i = 0; i < nodes.length; i++) {
      if (isSkipped(nodes[i])) continue;
      var text = candidateText(nodes[i]);
      if (text) { count++; chars += text.length; }
    }
    return { count: count, chars: chars };
  }

  /**
   * 清空本页译文（面板上的「清空本页译文」）。
   *
   * 与「关闭翻译」不同：关只是用 CSS 藏起来（元素还在，重开秒出）；
   * 这里是**真的删掉**，并把去重集合与计数一并复位，于是重扫等于重翻。
   */
  function clear() {
    var nodes = document.querySelectorAll('.bb-tr');
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i].parentNode) nodes[i].parentNode.removeChild(nodes[i]);
    }
    var srcs = document.querySelectorAll('.bb-tr-src');
    for (var j = 0; j < srcs.length; j++) srcs[j].classList.remove('bb-tr-src');
    state.count = 0;
    state.emptyRuns = 0;
    state.seen = (typeof WeakSet === 'function') ? new WeakSet() : null;
    disconnect();
  }

  function disconnect() {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
  }

  IT.dom = {
    scan: scan,
    observeAll: observeAll,
    disconnect: disconnect,
    insert: insert,
    totalCandidateLength: totalCandidateLength,
    candidates: candidates,
    clear: clear,
    selector: SELECTOR,
    skipSelector: SKIP_SELECTOR
  };
})();
