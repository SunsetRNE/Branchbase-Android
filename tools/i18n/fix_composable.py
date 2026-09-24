#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把「抽取后编译不过」的那些站点自动改成可用形态，直到编译通过或不再有进展。

## 它在修什么

`extract.py` 只按「在不在 @Composable 函数体内」判断，但函数体内还有**非组合 lambda**
（`scope.launch { }`、`onClick = { }`、`remember { }`），`stringResource` 在那里编译不过。
编译器是最可靠的裁判，所以流程是：

    抽取 → 编译 → 按报错逐个降级 → 再编译 → …

两类报错对应两种修法：

| 报错 | 含义 | 修法 |
|---|---|---|
| `@Composable invocations can only happen…` | 该行在非组合上下文 | 这一行的 `stringResource(` 换成 `context.getString(` |
| `Function invocation 'context(...)' expected` | 换完之后作用域里没有 `context` | 给**所在函数**补 `val context = LocalContext.current` |

第二类报错的文案看着奇怪（像是 Kotlin 2.2+ 的 `context(...)` 语法），
但它就是「`context` 这个名字解析不到一个值」时的说法 —— 别被它带偏。

用法：

    python3 tools/i18n/fix_composable.py [--max-rounds 6]
"""

import argparse
import importlib.util
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
SRC_ROOT = os.path.join(ROOT, 'app', 'src', 'main', 'java')

# 复用抽取器里的词法工具（composable_spans 是两处的共同判据，不能各写一份）
_spec = importlib.util.spec_from_file_location('ex', os.path.join(HERE, 'extract.py'))
ex = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ex)
sc = ex.sc

ERROR_RE = re.compile(r'^e: file://(?P<path>[^:]+):(?P<line>\d+):\d+ (?P<msg>.*)$')


def compile_once():
    """跑一次编译，返回 [(相对路径, 行号, 报错文本)]。"""
    p = subprocess.run(
        ['./gradlew', ':app:compileDebugKotlin', '--offline'],
        cwd=ROOT, capture_output=True, text=True,
    )
    out = []
    for line in (p.stdout + p.stderr).splitlines():
        m = ERROR_RE.match(line.strip())
        if m:
            out.append((os.path.relpath(m.group('path'), SRC_ROOT),
                        int(m.group('line')), m.group('msg')))
    return out, p.returncode == 0


def enclosing_body(lines, ln):
    """ln（1 基）所在的**最内层 @Composable 函数体**的起始行号（0 基），找不到返回 None。

    为什么必须是「@Composable 的函数体」而不是「最内层花括号」：
    - 报错点常在一个 `when` 分支或 lambda 里，那只是个代码块，往里插 `val context` 是语法错误；
    - 也常在一个**局部 `fun`** 里（例如 `fun doResolve()`）。`LocalContext.current` 本身是
      组合调用，插进局部函数一样编译不过 —— 正确做法是插到**外层那个 @Composable** 里，
      让局部函数通过闭包拿到它。

    所以这里复用 `extract.py` 的词法级 `composable_spans`（剥掉字符串/注释后配平花括号），
    取包含该行的最内层区间。
    """
    src = '\n'.join(lines)
    starts = sc.build_line_index(src)
    spans = ex.composable_spans(src)
    target = starts[ln - 1] if ln - 1 < len(starts) else None
    if target is None:
        return None
    best = None
    for a, b, _name in spans:
        if a <= target < b and (best is None or a > best[0]):
            best = (a, b)
    if best is None:
        return None
    return sc.line_of(starts, best[0]) - 1


def main():
    ap = argparse.ArgumentParser(description='自动修复抽取后的组合上下文报错')
    ap.add_argument('--max-rounds', type=int, default=6)
    args = ap.parse_args()

    for rnd in range(1, args.max_rounds + 1):
        errors, ok = compile_once()
        if ok:
            print('第 %d 轮：编译通过 ✅' % rnd)
            return 0
        if not errors:
            print('第 %d 轮：编译失败但解析不到错误行，请手动查看' % rnd)
            return 1

        # 按文件分组，自下而上改，避免行号漂移
        by_file = {}
        for path, ln, msg in errors:
            by_file.setdefault(path, []).append((ln, msg))

        changed = 0
        for path, items in by_file.items():
            fp = os.path.join(SRC_ROOT, path)
            lines = open(fp, encoding='utf-8').read().split('\n')
            for ln, msg in sorted(set(items), reverse=True):
                i = ln - 1
                if i >= len(lines):
                    continue
                if 'Function invocation' in msg or "Unresolved reference 'getString'" in msg:
                    # context 不在作用域 → 给所在函数补一个
                    body = enclosing_body(lines, ln)
                    if body is None:
                        print('⚠ %s:%d 找不到所在函数，跳过' % (path, ln))
                        continue
                    indent = len(lines[body]) - len(lines[body].lstrip()) + 4
                    if any('val context = LocalContext.current' in x
                           for x in lines[max(0, body - 3):body + 8]):
                        continue
                    lines.insert(body + 1, ' ' * indent + 'val context = LocalContext.current')
                    changed += 1
                elif 'stringResource(' in lines[i]:
                    lines[i] = lines[i].replace('stringResource(', 'context.getString(')
                    changed += 1
            open(fp, 'w', encoding='utf-8').write('\n'.join(lines))

        print('第 %d 轮：%d 个文件 / %d 处报错，已修 %d 处'
              % (rnd, len(by_file), len(errors), changed))
        if changed == 0:
            print('没有再能自动修的了，剩余报错：')
            for path, ln, msg in errors[:10]:
                print('   %s:%d %s' % (path, ln, msg))
            return 1
    print('达到最大轮数仍未通过')
    return 1


if __name__ == '__main__':
    sys.exit(main())
