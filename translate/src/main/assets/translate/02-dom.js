/*
 * 沉浸式翻译 · 02 DOM（段落收集 / 跳过规则 / 译文插入 / 视口观察）
 * ────────────────────────────────────────────────────────────
 * 本文件只做四件事：
 *   1. 找出「块级正文段落」，并跳过代码、表格代码、已有译文、被显式排除的节点；
 *   2. 把译文插进去 —— 普通块插成**兄弟节点**（原文一个字都不改）；列表项与表格
 *      单元格插成**子节点**（那两种位置放兄弟节点是非法 HTML，会破坏排版，见 INSERT_INSIDE）。
 *      容器里的内容是两种形态之一：整段译文，或**匹配性译文**（只翻了段内几个片段，
 *      按「片段 → 译文」配对列出，见 fill()）；
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

  // 只取「块级正文」：标题 / 段落 / 列表项 / 引用 / 表格（含表头）/ 定义列表
  var SELECTOR = 'p, li, h1, h2, h3, h4, h5, h6, blockquote, td, th, dd';

  // 译文**必须插进元素内部**的标签。
  //
  // 前三个（列表项 / 表格单元格）是**结构合法性**问题：`<ul>/<ol>` 里只允许 `<li>`、
  // `<tr>` 里只允许 `<td>/<th>`，把译文当兄弟节点插进去就是非法 HTML，浏览器按匿名内容
  // 处理，表现为「列表的缩进/编号乱掉」「表格被撑开、列错位」。
  //
  // 标题（h1–h6）是**排版归属**问题：markdown 的 h1/h2 带 `border-bottom`
  // （见 app 的 `assets/github-markdown-*.css`），译文插成兄弟节点会落到那条横线**下面**，
  // 看起来像「引用下一段」的卡片，跟它本该跟随的标题脱开了。插进标题内部则与原文同属一个
  // 标题块，横线（标题块末尾）留在译文之后。
  var INSERT_INSIDE = { LI: 1, TD: 1, TH: 1, H1: 1, H2: 1, H3: 1, H4: 1, H5: 1, H6: 1 };

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

  /** 直接子节点里找某个 class（`:scope` 在老 WebView 上不一定可用，手写更稳）。 */
  function directChild(el, cls) {
    if (!el.children) return null;
    for (var i = 0; i < el.children.length; i++) {
      var c = el.children[i];
      if (c.classList && c.classList.contains(cls)) return c;
    }
    return null;
  }

  /**
   * 取「原文文本」。
   *
   * 译文插在元素内部时（列表项 / 表格单元格），`innerText` 会把译文也算进去 ——
   * 那会让候选统计虚高，判定也可能把同一段再翻一次。这里把内部的译文排除掉。
   */
  function sourceText(el) {
    if (!directChild(el, 'bb-tr')) return el.innerText || el.textContent || '';
    var buf = '';
    for (var i = 0; i < el.childNodes.length; i++) {
      var n = el.childNodes[i];
      if (n.nodeType === 1 && n.classList && n.classList.contains('bb-tr')) continue;
      buf += (n.textContent || '') + ' ';
    }
    return buf;
  }

  // 纯查询：这段文本值得翻吗（**不产生副作用**，供「先估算整页体量」用）
  function candidateText(el) {
    if (!el || isSkipped(el) || hasBlockChild(el)) return null;
    var text = IT.util.normalize(sourceText(el));
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

  /**
   * 把一批产物填进译文容器。
   *
   * 两种形态（原生侧 `TranslatePagePayload.encode` 产出）：
   * - **字符串**：整段译文，直接当文本；
   * - **对象**：匹配性译文 —— 只翻了段内的几个片段，按「片段 → 译文」配对列出
   *   （`npm run dev → npm 运行开发`）。配对里保留原文片段，是因为读者要的正是
   *   「这个词在这句里是什么意思」；「仅译文」模式由 CSS 把原文片段藏掉。
   *
   * 重复翻译同一段（换目标语言 / 重扫）时这里是**覆盖**而不是追加，
   * 否则页面上会叠出两份译文 —— 与「译文容器存在 = 已翻译」那条幂等约定同源。
   */
  function fill(el, result) {
    var parts = (result && result.parts) || null;
    if (!parts || !parts.length) {
      el.removeAttribute('data-bb-mode');
      el.textContent = typeof result === 'string' ? result : '';
      return;
    }
    el.setAttribute('data-bb-mode', 'match');
    el.textContent = '';
    for (var i = 0; i < parts.length; i++) {
      var pair = document.createElement('span');
      pair.className = 'bb-tr-pair';
      var src = document.createElement('span');
      src.className = 'bb-tr-pair-src';
      src.textContent = parts[i].s || '';
      var dst = document.createElement('span');
      dst.className = 'bb-tr-pair-dst';
      dst.textContent = parts[i].t || '';
      pair.appendChild(src);
      pair.appendChild(dst);
      el.appendChild(pair);
    }
  }

  /**
   * 译文容器。
   *
   * @param inline true = 用 `<span>`：**标题只接受短语内容**，往里塞 `<div>` 和往 `<ul>` 里
   *   塞 `<div>` 是同一类错误（浏览器按匿名内容处理）。`.bb-tr` 自带 `display: block`，
   *   所以 span 的排版与 div 完全一致，不需要额外的定位样式。
   */
  function makeTranslation(result, inline) {
    var el = document.createElement(inline ? 'span' : 'div');
    el.className = 'bb-tr';
    el.setAttribute('lang', state.to);
    fill(el, result);
    return el;
  }

  /**
   * 译文容器。
   *
   * 普通块（段落 / 标题 / 引用 / 定义）→ 插成**兄弟节点**：原文一个字不动，
   * 「切回原文」只要删掉容器，天然幂等。
   * 受限容器（列表项 / 表格单元格，见 [INSERT_INSIDE]）→ 插成**子节点**：
   * 那两种位置放兄弟节点是非法 HTML，会破坏列表与表格的排版。
   */
  function insert(el, result) {
    if (INSERT_INSIDE[el.tagName]) {
      insertInside(el, result);
      return;
    }
    var next = el.nextElementSibling;
    if (next && next.classList && next.classList.contains('bb-tr')) {
      fill(next, result);   // 同一段重复翻译（换目标语言）时直接覆盖
      return;
    }
    // 标记源元素：仅译文模式下由 CSS 隐藏它（切回对照只需去掉 body 上的类）
    if (el.classList) el.classList.add('bb-tr-src');
    if (el.parentNode) el.parentNode.insertBefore(makeTranslation(result, false), el.nextSibling);
  }

  /**
   * 插到元素内部（列表项 / 表格单元格）。
   *
   * 原文会被包进一层 `<span class="bb-tr-src" data-bb-wrap="1">`：这样「仅译文」
   * 模式仍然只藏原文（藏整个 `<td>` 会让表格塌一列），清空时再把包裹层拆掉还原。
   */
  function insertInside(el, result) {
    var existing = directChild(el, 'bb-tr');
    if (existing) { fill(existing, result); return; }
    var src = directChild(el, 'bb-tr-src');
    if (!src) {
      src = document.createElement('span');
      src.className = 'bb-tr-src';
      src.setAttribute('data-bb-wrap', '1');
      while (el.firstChild) src.appendChild(el.firstChild);
      el.appendChild(src);
    }
    // 标题只能用短语内容（span），其余内部插入（li / td / th）用 div
    el.appendChild(makeTranslation(result, /^H[1-6]$/.test(el.tagName)));
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

  /**
   * 整页候选统计（段数 + 字符数）。
   *
   * 两处要用它：启动时判断「一次翻完还是视口优先」（字符数 vs `immediateLimit`），
   * 以及悬浮面板的进度（已译 / 候选段数）。同一件事（扫描 + 判定）只写一遍，
   * 两边不会算出不同的数。
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
    // 内部插入时的原文包裹层：把子节点搬回原位再拆掉（不能只删 class，会留一个空 span）
    var wraps = document.querySelectorAll('[data-bb-wrap]');
    for (var k = 0; k < wraps.length; k++) {
      var w = wraps[k];
      var p = w.parentNode;
      if (!p) continue;
      while (w.firstChild) p.insertBefore(w.firstChild, w);
      p.removeChild(w);
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
    candidates: candidates,
    clear: clear,
    selector: SELECTOR,
    skipSelector: SKIP_SELECTOR
  };
})();
