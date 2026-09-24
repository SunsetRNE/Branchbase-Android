#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 Kotlin 源码里把中文字面量抽成 Android 字符串资源。

设计前提（决定了它敢改哪些、不敢改哪些）
----------------------------------------
1. **只动 [@Composable] 函数体内的字面量**：`stringResource(...)` 是 @Composable 调用，
   放在普通函数/非组合 lambda 里**编译不过**。其余位置（仓储层、协程、remember 计算）
   需要 Context 或改结构，属于另一阶段，本工具一律跳过并报告。
2. **只动翻译表里列出的字面量**：工具不做语义判断，`strings.tsv` 是唯一事实来源。
   没列进去的一律不碰 —— 这保证「工具跑一遍」不会顺手改掉它看不懂的东西。
3. **同一个字面量在全局共用一个资源**：翻译表按**字面量文本**匹配，
   所以 `"取消"` 在 38 处引用会共享一个 `action_cancel`，不会产生 38 条重复资源。
4. **模板插值转位置参数**：`"提交 ${'$'}{n} 个文件到 ${'$'}branch"` →
   `"提交 %1${'$'}s 个文件到 %2${'$'}s"` + `stringResource(R.string.x, n, branch)`。
   位置参数是硬要求（见 `tools/i18n/check-i18n.py` 的裸占位符检查）：语序不同的语言里译者要能调换。

用法
----
    # 先空跑，看会改什么
    python3 tools/i18n/extract.py --file app/src/main/java/.../Foo.kt

    # 真改（同时写 strings.xml / strings-en.xml）
    python3 tools/i18n/extract.py --file ... --apply

翻译表格式（TSV，`#` 开头是注释）：

    literal <TAB> english <TAB> resource_name
"""

import argparse
import os
import re
import sys
import xml.sax.saxutils as sax

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
SCANNER = os.path.join(ROOT, 'i18n-audit', 'scan_hardcoded_cjk.py')

import importlib.util
_spec = importlib.util.spec_from_file_location('sc', SCANNER)
sc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sc)

VALUES = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'values', 'strings.xml')
VALUES_EN = os.path.join(ROOT, 'app', 'src', 'main', 'res', 'values-en', 'strings.xml')

# Kotlin 模板里的两段：${expr} 与 $ident（后者只吃标识符）
TEMPLATE_RE = re.compile(r'\$\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}|\$([A-Za-z_]\w*)')


def load_table(path):
    """读翻译表 → {literal: (english, name)}，并检查名字唯一。

    ⚠️ 注释与数据的区分**不能只看行首是不是 `#`**：
    有些字面量本身就是 Markdown 标题（`## 变更内容…` 是 PR 描述模板的默认值），
    按 `#` 判注释会把它们**静默丢掉** —— 表现是「表里明明写了、工具却说没匹配上」。
    所以规则改成：**恰好三列就是数据**，否则才按 `#` 当注释。
    """
    table = {}
    by_name = {}
    with open(path, encoding='utf-8') as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip('\n')
            if not line.strip():
                continue
            parts = line.split('\t')
            if len(parts) != 3:
                if line.lstrip().startswith('#'):
                    continue
                raise SystemExit('%s:%d 需要三列（literal/english/name），实际 %d 列'
                                 % (path, lineno, len(parts)))
            literal, english, name = parts
            if literal in table:
                raise SystemExit('%s:%d 字面量重复：%r' % (path, lineno, literal))
            if name in by_name:
                raise SystemExit('%s:%d 资源名重复：%s（已用于 %r）' % (path, lineno, name, by_name[name]))
            if not re.fullmatch(r'[a-z][a-z0-9_]*', name):
                raise SystemExit('%s:%d 资源名必须是小写蛇形：%s' % (path, lineno, name))
            table[literal] = (english, name)
            by_name[name] = literal
    return table


def composable_spans(src):
    """所有 @Composable 函数**函数体**的区间（用词法剥掉字符串/注释，避免括号被内容带偏）。"""
    starts = sc.build_line_index(src)
    toks = sc.lex_file(src, 'kt', 'c', starts)
    masked = bytearray(len(src))
    for t in toks:
        if t.kind != 'code':
            for i in range(t.start, min(t.end, len(src))):
                masked[i] = 1
    spans = []
    for m in re.finditer(r'@Composable\b', src):
        if masked[m.start()]:
            continue
        fm = re.compile(r'\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(').search(src, m.end())
        if not fm:
            continue
        i, depth = fm.end(), 1
        while i < len(src) and depth:
            if not masked[i]:
                if src[i] == '(':
                    depth += 1
                elif src[i] == ')':
                    depth -= 1
            i += 1
        while i < len(src) and src[i] in ' \t\r\n':
            i += 1
        if i < len(src) and src[i] == '{':
            body_start, d, i = i, 1, i + 1
            while i < len(src) and d:
                if not masked[i]:
                    if src[i] == '{':
                        d += 1
                    elif src[i] == '}':
                        d -= 1
                i += 1
            spans.append((body_start, i, fm.group(1)))
    return spans


def to_format(literal):
    """把 Kotlin 模板转成 (格式串, 参数列表)。没有插值时参数为空。

    ⚠️ **先把字面 `%` 转义成 `%%`，再做模板替换** —— 顺序不能反：
    - 源码里的 `%` 是普通字符（Kotlin 不认它是格式符），到了 Android 资源里必须写 `%%`，
      否则 `String.format` 会当成格式说明符，轻则丢掉内容重则抛
      `UnknownFormatConversionException`（例如 `"${'$'}{n} 字符 · ${'$'}{pct}%"`）；
    - 若先替换再转义，会把自己刚生成的 `%1$s` 也转义成 `%%1$s`，把占位符彻底写坏。
    """
    args = []

    def sub(m):
        expr = m.group(1) if m.group(1) is not None else m.group(2)
        args.append(expr.strip())
        return '%%%d$s' % len(args)

    return TEMPLATE_RE.sub(sub, literal.replace('%', '%%')), args


def xml_escape(s):
    """XML 文本转义 + Android 的撇号/引号规则。

    Android 的资源里裸 `'` 会被当成引号定界符，必须写成 `\\'`；
    否则 aapt2 直接报错（`Apostrophe not preceded by \\`）。
    """
    s = sax.escape(s, {'"': '\\"'})
    return s.replace("'", "\\'")


def group_strings(src, toks):
    """把「用 `+` 连起来的相邻字符串」合成一组。

    为什么必须合：`"第一句，" + "第二句。"` 在源码里是两个字面量，
    但对译者来说是**一句完整的话**。逐段抽会把句子切碎，到了语序不同的语言里
    拼不回通顺的句子（这正是 `docs` 里说的「变量 + 中文后缀」那类反面教材）。

    返回 [(start, end, 合并后的文本)]，start/end 覆盖整组（含中间的 `+` 与换行）。
    """
    groups = []
    i = 0
    while i < len(toks):
        t = toks[i]
        if t.kind not in ('string', 'rawstring', 'template'):
            i += 1
            continue
        start, end = t.start, t.end
        parts = [sc.inner_of(t.kind, t.text)]
        j = i + 1
        while j < len(toks):
            nxt = toks[j]
            if nxt.kind in ('comment',):
                break
            if nxt.kind in ('string', 'rawstring', 'template'):
                between = src[end:nxt.start]
                # 中间只允许空白与一个 `+`
                if between.strip() == '+':
                    parts.append(sc.inner_of(nxt.kind, nxt.text))
                    end = nxt.end
                    j += 1
                    continue
                break
            # 非字符串 token 出现在中间 → 不是拼接
            break
        groups.append((start, end, ''.join(parts)))
        i = j
    return groups


def is_log_site(src, pos):
    """这个字面量是不是日志调用的参数？

    日志**按约定不翻译**（`tools/perf/frame-baseline.py` 用中文正则解析日志做性能归因，
    `StartupMarkerTest` 也断言 `STARTUP_TAG = "启动"`）。把日志翻成英文会同时打断
    性能报表与测试，所以工具在这里主动跳过，而不是等 CI 报红。

    判据取「同一行有日志调用」或「包住它的调用是日志函数」——与审计脚本的
    `LOG_CALL_RE` / `enclosing_call` 保持一致，避免两处口径分家。
    """
    starts = sc.build_line_index(src)
    line = sc.line_of(starts, pos)
    lines = src.split('\n')
    line_text = lines[line - 1] if line - 1 < len(lines) else ''
    if sc.LOG_CALL_RE.search(line_text):
        return True
    enc = sc.enclosing_call(src, pos)
    return bool(enc) and (enc.startswith(('Logger.', 'Log.', 'console.', 'tracing')))


FUN_HEAD_RE = re.compile(
    r'\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(')


def _masked(src):
    """剥掉字符串/注释后的掩码（非 0 表示该下标不参与括号配平）。"""
    starts = sc.build_line_index(src)
    toks = sc.lex_file(src, 'kt', 'c', starts)
    masked = bytearray(len(src))
    for t in toks:
        if t.kind != 'code':
            for i in range(t.start, min(t.end, len(src))):
                masked[i] = 1
    return masked


def _body_span(src, masked, lparen_end):
    """从参数表结束处找到函数体 `{`…`}`，返回 (body_open, body_end)；表达式体返回 None。"""
    i, depth = lparen_end, 1
    while i < len(src) and depth:
        if not masked[i]:
            if src[i] == '(':
                depth += 1
            elif src[i] == ')':
                depth -= 1
        i += 1
    while i < len(src) and src[i] in ' \t\r\n':
        i += 1
    if i >= len(src) or src[i] != '{':
        return None
    body_open, d, i = i, 1, i + 1
    while i < len(src) and d:
        if not masked[i]:
            if src[i] == '{':
                d += 1
            elif src[i] == '}':
                d -= 1
        i += 1
    return body_open, i


def enclosing_function(src, masked, pos):
    """包含 pos 的**最内层函数** → (decl_start, body_open, body_end)，找不到返回 None。"""
    best = None
    for m in FUN_HEAD_RE.finditer(src):
        if masked[m.start()]:
            continue
        span = _body_span(src, masked, m.end())
        if not span:
            continue
        body_open, body_end = span
        if body_open <= pos < body_end and (best is None or body_open > best[1]):
            best = (m.start(), body_open, body_end)
    return best


def has_context_in_scope(src, masked, pos, composable_spans):
    """这个位置能不能拿到一个 `Context`？

    三种情况之一即可：
    1. 所在函数的**参数表里有 `context`**（`fun f(context: Context, …)`）；
    2. 所在函数体在当前位置**之前声明过 `val context`**；
    3. 位置落在某个 `@Composable` 函数体内 —— 那种情况 `fix_composable.py` 会给外层组合函数
       补上 `val context = LocalContext.current`，局部函数/非组合 lambda 通过闭包拿到它。

    三条都不满足的（典型是纯模型文件：一行 `@Composable` 都没有、也没有 Context 参数）
    **必须跳过** —— 在那里插 `context.getString` 只会编译不过，而「能拿到 Context」这件事
    是接口设计问题，不该由抽取工具硬塞。
    """
    fn = enclosing_function(src, masked, pos)
    if fn:
        decl_start, body_open, _ = fn
        decl = src[decl_start:body_open]
        if re.search(r'\bcontext\s*:', decl):
            return True
        if re.search(r'\bval\s+context\b', src[body_open:pos]):
            return True
    return any(a <= pos < b for a, b, _ in composable_spans)


def plan_file(path, table, include_plain=True):
    """返回 (src, 替换列表, 跳过列表)。替换按位置倒序应用，避免偏移漂移。

    - 落在 `@Composable` 函数体内 → `stringResource(...)`
    - 落在普通代码里但**能拿到 Context** → `context.getString(...)`
    - 两者都不是（纯逻辑文件）→ 跳过并报告，等接口改造
    """
    src = open(path, encoding='utf-8').read()
    masked = _masked(src)
    starts = sc.build_line_index(src)
    toks = sc.lex_file(src, 'kt', 'c', starts)
    spans = composable_spans(src)

    edits, skipped = [], []
    for start, end, inner in group_strings(src, toks):
        if inner not in table:
            continue
        line = sc.line_of(starts, start)
        in_composable = any(a <= start < b for a, b, _ in spans)
        if not in_composable and not (include_plain and has_context_in_scope(src, masked, start, spans)):
            reason = ('不在 @Composable 函数体内' if include_plain
                      else '不在 @Composable 函数体内（本次只抽组合作用域）')
            skipped.append((line, inner, reason))
            continue
        if is_log_site(src, start):
            skipped.append((line, inner, '日志文案（按约定不翻译）'))
            continue
        if inner not in table:
            # 翻译表是**分批**补的（并行给多个文件写表），所以缺条目不能直接崩：
            # 缺的这条留在源码里不动，等表补齐后再跑一次。
            skipped.append((line, inner, '翻译表里还没有这一条'))
            continue
        fmt, args = to_format(inner)
        name = table[inner][1]
        call = 'stringResource' if in_composable else 'context.getString'
        expr = '%s(R.string.%s)' % (call, name)
        if args:
            expr = '%s(R.string.%s, %s)' % (call, name, ', '.join(args))
        edits.append((start, end, expr, line, inner))

    edits.sort(key=lambda e: -e[0])
    return src, edits, skipped


def list_candidates(path):
    """列出文件里所有含中文的字符串组（含拼接组），供人工编写翻译表。"""
    src = open(path, encoding='utf-8').read()
    starts = sc.build_line_index(src)
    toks = sc.lex_file(src, 'kt', 'c', starts)
    spans = composable_spans(src)
    out = []
    for start, end, inner in group_strings(src, toks):
        if not sc.has_cjk_any(inner):
            continue
        line = sc.line_of(starts, start)
        in_comp = any(a <= start < b for a, b, _ in spans)
        out.append((line, inner, in_comp))
    return out


def apply_edits(src, edits):
    for start, end, expr, _, _ in edits:
        src = src[:start] + expr + src[end:]
    return src


def ensure_imports(src):
    """补 stringResource 与 R 的 import（已存在则不动）。"""
    if 'import androidx.compose.ui.res.stringResource' not in src:
        anchor = 'import androidx.compose.runtime.Composable'
        if anchor in src:
            src = src.replace(anchor, anchor + '\nimport androidx.compose.ui.res.stringResource', 1)
        else:
            src = re.sub(r'^(import .*\n)', r'\1import androidx.compose.ui.res.stringResource\n',
                         src, count=1, flags=re.M)
    if re.search(r'^import com\.branchbase\.R$', src, re.M) is None:
        m = re.search(r'^import com\.branchbase\.', src, re.M)
        if m:
            src = src[:m.start()] + 'import com.branchbase.R\n' + src[m.start():]
        else:
            src = re.sub(r'^(import .*\n)', r'\1import com.branchbase.R\n', src, count=1, flags=re.M)
    return src


def write_resources(table, used):
    """把本次用到的条目写进两个 strings.xml（追加在 </resources> 前）。

    ⚠️ 两边的处理**必须不同**：
    - 默认语言那份来自 **Kotlin 源码**，里面是 `$var` / `${expr}` 模板 → 要过 [to_format]；
    - 译文那份来自**翻译表**，本来就已经是 Android 的 `%1$s` 位置参数 → **不能再转**。
      对它再跑一遍 to_format 会把 `%1$s` 里的 `$s` 当模板吃掉，写成 `%1%1$s`
      （运行时会少一个参数、`String.format` 直接抛异常）。
    """
    for path, idx in ((VALUES, 0), (VALUES_EN, 1)):
        src = open(path, encoding='utf-8').read()
        add = []
        for literal in used:
            english, name = table[literal]
            if '<string name="%s">' % name in src:
                continue
            text = to_format(literal)[0] if idx == 0 else english
            add.append('    <string name="%s">%s</string>' % (name, xml_escape(text)))
        if add:
            src = src.replace('</resources>', '\n'.join(add) + '\n</resources>')
            open(path, 'w', encoding='utf-8').write(src)


def resync_resources(table):
    """按翻译表**重写**两个 strings.xml 里由表管理的条目（幂等，用于修表后回灌）。

    只碰表里有的资源名，手写的条目（如 settings_language）不受影响。
    """
    for path, idx in ((VALUES, 0), (VALUES_EN, 1)):
        src = open(path, encoding='utf-8').read()
        for literal, (english, name) in table.items():
            text = to_format(literal)[0] if idx == 0 else english
            line = '    <string name="%s">%s</string>' % (name, xml_escape(text))
            pat = re.compile(r'^[ \t]*<string name="%s">.*</string>$' % re.escape(name), re.M)
            if pat.search(src):
                src = pat.sub(lambda _: line, src)
        open(path, 'w', encoding='utf-8').write(src)


def main():
    ap = argparse.ArgumentParser(description='把 Kotlin 里的中文字面量抽成字符串资源')
    ap.add_argument('--file', required=True, help='目标 Kotlin 文件（相对仓库根）')
    ap.add_argument('--table', default=os.path.join(HERE, 'strings.tsv'))
    ap.add_argument('--apply', action='store_true', help='真正写盘（默认只空跑）')
    ap.add_argument('--list', action='store_true', help='列出候选字面量（编写翻译表用）')
    args = ap.parse_args()

    path = args.file if os.path.isabs(args.file) else os.path.join(ROOT, args.file)
    if not os.path.isfile(path):
        raise SystemExit('找不到文件：%s' % path)

    if args.list:
        for line, inner, in_comp in list_candidates(path):
            mark = 'C' if in_comp else '-'
            print('%s\tL%-5d\t%s' % (mark, line, inner.replace('\n', '\\n')))
        return 0

    if not os.path.isfile(args.table):
        raise SystemExit('找不到翻译表：%s' % args.table)

    table = load_table(args.table)
    src, edits, skipped = plan_file(path, table)

    print('%s' % os.path.relpath(path, ROOT))
    print('  可替换 %d 处，涉及 %d 个不同资源' % (len(edits), len({e[2] for e in edits})))
    for _, _, expr, line, inner in sorted(edits, key=lambda e: e[3])[:5]:
        print('    L%-5d %-30s → %s' % (line, inner[:28], expr[:60]))
    if len(edits) > 5:
        print('    …（其余 %d 处）' % (len(edits) - 5))
    if skipped:
        print('  跳过 %d 处（不在 @Composable 函数体内，留给后续阶段）：' % len(skipped))
        for line, inner, why in sorted(skipped)[:5]:
            print('    L%-5d %-30s %s' % (line, inner[:28], why))

    if not args.apply:
        print('\n（空跑，未写盘；加 --apply 才生效）')
        return 0

    out = ensure_imports(apply_edits(src, edits))
    open(path, 'w', encoding='utf-8').write(out)
    write_resources(table, {e[4] for e in edits})
    print('\n已写入 %s，并更新 values/strings.xml 与 values-en/strings.xml' % os.path.relpath(path, ROOT))
    return 0


if __name__ == '__main__':
    sys.exit(main())
