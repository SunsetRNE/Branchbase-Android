#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
i18n 校验：资源目录 / 占位符 / translatable / 覆盖率。

这个脚本是**改坏立刻红**的那一层。它不依赖 Gradle、不依赖 Android SDK ——
只解析 XML，所以本地随手能跑，CI 里也只是几毫秒：

    python3 tools/i18n/check-i18n.py

四类检查，前三类是**硬错误**（一定退出码非 0），第四类是**阈值**（用 --min-coverage 调）：

  ① 结构      —— 默认语言与各翻译的键集合、重复键、空值
  ② 占位符    —— 译文与默认语言的 %N$s 集合必须一致；禁止裸 %s / %d（语序不同的语言里没法调换）
  ③ translatable —— 同一键在默认语言与译文里的 translatable 必须一致；品牌名/格式串不许被翻译
  ④ 覆盖率    —— 每种语言缺多少条（--min-coverage，默认 100）

为什么这些必须是硬错误：它们全会变成**运行时事故**而不是显示瑕疵 ——
少一个占位符 → `String.format` 抛异常、界面直接崩；多一个占位符 → 参数被静默吞掉；
漏标 translatable → 译者把缓存键或品牌名翻掉，功能静默失效。

配套的扫描器（找**还没抽出来**的硬编码中文）在 `i18n-audit/scan_hardcoded_cjk.py`，
两者互补：那个管"有没有抽干净"，这个管"抽出来之后有没有写对"。
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

# %1$s / %2$d …（带位置）与 %s / %d …（裸）
POSITIONAL_RE = re.compile(r'%(\d+)\$[sdfxXoegEc]')
BARE_RE = re.compile(r'%(?![%]|\d+\$)([sdfxXoegEc])')

VALUES_DIR_RE = re.compile(r'^values-?(.*)$')


def malformed_percent(text):
    """格式串里有没有「不成对的 %」。

    去掉合法的 `%%` 与 `%N$X` 之后还剩 `%`，说明这个串写坏了 —— 典型来源是
    **把已经含 `%1$s` 的译文又当成模板转了一遍**，产出 `%1%1$s`：
    它仍能匹配出 `%1$s`（所以只比占位符集合是查不出来的），
    但运行时 `String.format` 会因为「格式说明符不完整」直接抛异常。
    """
    t = text.replace('%%', '')
    t = POSITIONAL_RE.sub('', t)
    return '%' in t


def qualifier_to_tag(qualifier: str):
    """`values-en` → en，`values-zh-rCN` → zh-CN，`values-b+zh+Hans` → zh-Hans。"""
    if not qualifier:
        return None
    if qualifier.startswith('b+'):
        return qualifier[2:].replace('+', '-')
    parts = qualifier.split('-')
    tag = []
    for i, p in enumerate(parts):
        if p.startswith('r') and len(p) > 1 and i > 0:
            tag.append(p[1:].upper())
        else:
            tag.append(p)
    return '-'.join(tag)


def is_language_qualifier(qualifier: str) -> bool:
    """区分 `values-en`（语言）与 `values-night` / `values-v35` / `values-sw600dp`（非语言）。

    只按语言过滤，否则 `values-night/` 会被当成一种"语言"，报出一堆假红 ——
    假红比没有检查更坏（这条教训在本仓库的 SettingsSpecTest 里已经写过一次）。
    """
    if not qualifier:
        return False
    first = qualifier.split('-')[0]
    if first.startswith('b+'):
        return True
    if len(first) == 2 or len(first) == 3:
        return first.isalpha() and first.islower()
    return False


class Strings:
    """一个 strings.xml 的解析结果：name -> (value, translatable)。"""

    def __init__(self, path):
        self.path = path
        self.items = {}
        # 复数条目 → [(quantity, text)]，用于「各形态占位符必须一致」的检查
        self.plural_forms = {}
        self.duplicates = []
        self.empty = []
        self._parse()

    def _parse(self):
        root = ET.parse(self.path).getroot()
        seen = Counter()
        for el in root:
            if el.tag not in ('string', 'plurals', 'string-array'):
                continue
            name = el.get('name')
            if name is None:
                continue
            seen[name] += 1
            if el.tag == 'plurals':
                forms = [(item.get('quantity') or '?', item.text or '')
                         for item in el.findall('item')]
                self.plural_forms[name] = forms
                value = ' '.join(text for _, text in forms)
            elif el.tag == 'string-array':
                value = ' '.join((item.text or '') for item in el.findall('item'))
            else:
                value = el.text or ''
            self.items[name] = (value, el.get('translatable'))
        self.duplicates = [n for n, c in seen.items() if c > 1]
        self.empty = [
            n for n, (v, _) in self.items.items()
            if not v.strip() and not n.startswith('_')
        ]


def module_res_dirs(root):
    """所有带 `res/values/strings.xml` 的模块 → [(模块名, res 目录)]，:app 排最前。"""
    mods = []
    for entry in sorted(os.listdir(root)):
        if not os.path.isdir(os.path.join(root, entry)):
            continue
        res = os.path.join(root, entry, 'src', 'main', 'res')
        if os.path.isfile(os.path.join(res, 'values', 'strings.xml')):
            mods.append((entry, res))
    mods.sort(key=lambda m: (m[0] != 'app', m[0]))
    return mods


def collect(res):
    """解析一个模块的 res 目录 → (默认 Strings, [(tag, Strings)], unqualifiedResLocale)。"""
    default_path = os.path.join(res, 'values', 'strings.xml')
    if not os.path.isfile(default_path):
        raise SystemExit('找不到默认语言资源：%s' % default_path)

    default = Strings(default_path)
    translations = []
    for entry in sorted(os.listdir(res)):
        full = os.path.join(res, entry)
        if not os.path.isdir(full):
            continue
        m = VALUES_DIR_RE.match(entry)
        if not m:
            continue
        qualifier = m.group(1)
        if not is_language_qualifier(qualifier):
            continue
        strings = os.path.join(full, 'strings.xml')
        if not os.path.isfile(strings):
            continue
        translations.append((qualifier_to_tag(qualifier), Strings(strings)))

    # 默认语言（`res/resources.properties` 的 unqualifiedResLocale）
    unqualified = None
    props = os.path.join(res, 'resources.properties')
    if os.path.isfile(props):
        with open(props, encoding='utf-8') as fh:
            for line in fh:
                line = line.strip()
                if line.startswith('#') or '=' not in line:
                    continue
                k, v = line.split('=', 1)
                if k.strip() == 'unqualifiedResLocale':
                    unqualified = v.strip()
    return default, translations, unqualified


def check(root, min_coverage):
    """逐模块校验 + 一条跨模块规则。返回 (errors, warnings)。"""
    errors = []
    warnings = []
    mods = module_res_dirs(root)
    if not mods:
        raise SystemExit('找不到任何带 res/values/strings.xml 的模块')

    app_locales = set()
    for name, res in mods:
        default, translations, unqualified = collect(res)
        label = ':%s' % name
        print('%s  默认语言 %s · 条目 %d · 翻译 %d 种'
              % (label, unqualified or '⚠ 未声明', len(default.items), len(translations)))

        if not unqualified:
            if name == 'app':
                errors.append(
                    '%s: res/resources.properties 缺少 unqualifiedResLocale —— '
                    'AGP 的 generateLocaleConfig 不知道 values/ 是什么语言，'
                    ':app:extractDebugSupportedLocales 会直接失败' % label
                )
            elif translations:
                warnings.append(
                    '%s 有翻译目录但未声明 unqualifiedResLocale。库模块不跑 generateLocaleConfig，'
                    '不影响构建；但它的 values/ 必须与 :app 的默认语言一致（同一份回退目标），'
                    '加语言时两边要一起加' % label
                )

        # ── 复数（<plurals>）的两条不变量 ──────────────────────────────
        # 1. 必须有 `other`：Android 在任何语言下都要求它，缺了会在部分语言下取不到串；
        # 2. 各 quantity 的占位符必须一致：`one` 忘了写 %1$s 是最常见的复数错误，
        #    它不会编译失败，只在数量为 1 时少一个数字。
        for label_s, store in ((label, default),) + tuple(
                (t.path, t) for _, t in translations):
            # ⚠️ 循环变量不能叫 `name` —— 外层模块循环用的就是 `name`，
            # 遮蔽之后下面所有 `name == 'app'` 判断都会失效（这是一个真实踩过的坑：
            # 加完这段之后「:app 有没有 en」的判断静默失灵，报出假的「语言不一致」）。
            for plural_name, forms in store.plural_forms.items():
                quantities = [q for q, _ in forms]
                if 'other' not in quantities:
                    errors.append('%s: 复数 `%s` 缺少 other 形态（Android 强制要求）' % (label_s, plural_name))
                sets = {tuple(sorted(set(POSITIONAL_RE.findall(txt)))) for _, txt in forms}
                if len(sets) > 1:
                    errors.append(
                        '%s: 复数 `%s` 各 quantity 的占位符不一致：%s'
                        % (label_s, plural_name,
                           {q: sorted(set(POSITIONAL_RE.findall(t))) for q, t in forms})
                    )

        if default.duplicates:
            errors.append('%s: values/strings.xml 有重复键：%s' % (label, ', '.join(sorted(default.duplicates))))
        if default.empty:
            errors.append('%s: values/strings.xml 有空值条目：%s' % (label, ', '.join(sorted(default.empty))))

        # 格式串完整性：默认语言与译文都要查（`%1%1$s` 这种只比占位符集合是查不出来的）
        for key, (val, _) in default.items.items():
            if malformed_percent(val):
                errors.append(
                    '%s: `%s` 的格式串里有不成对的 %% —— 运行时 String.format 会抛异常（默认=%r）'
                    % (label, key, val[:60])
                )

        # 覆盖率只看**可翻译**的条目：translatable="false" 的（品牌名、格式串）本来就不该有译文，
        # 把它们算进分母会让覆盖率永远到不了 100%（lint 的 MissingTranslation 同样不要求它们）
        required = {n for n, (_, t) in default.items.items() if t != 'false'}

        for tag, t in translations:
            if name == 'app':
                app_locales.add(tag)
            missing = sorted(required - set(t.items))
            extra = sorted(set(t.items) - set(default.items))

            if t.duplicates:
                errors.append('%s 有重复键：%s' % (t.path, ', '.join(sorted(t.duplicates))))
            if extra:
                errors.append(
                    '%s 有默认语言里不存在的键（删条目时忘了删译文）：%s'
                    % (t.path, ', '.join(extra))
                )

            for key in sorted(set(default.items) & set(t.items)):
                dv, dt = default.items[key]
                tv, tt = t.items[key]

                # ③ translatable 一致性
                if (dt == 'false') != (tt == 'false'):
                    errors.append(
                        '%s: `%s` 的 translatable 与默认语言不一致（默认=%s 译文=%s）—— '
                        '标了 false 的（品牌名/格式串）不许有译文'
                        % (t.path, key, dt, tt)
                    )
                    continue
                if dt == 'false':
                    warnings.append(
                        '%s: `%s` 标了 translatable="false" 却仍有译文，建议删掉这一条'
                        % (t.path, key)
                    )
                    continue

                # ② 占位符一致性
                dpos = sorted(set(POSITIONAL_RE.findall(dv)))
                tpos = sorted(set(POSITIONAL_RE.findall(tv)))
                if dpos != tpos:
                    errors.append(
                        '%s: `%s` 的占位符不一致（默认=%s 译文=%s）—— '
                        '运行时 String.format 会抛异常或静默吞参数'
                        % (t.path, key, dpos or '无', tpos or '无')
                    )
                if BARE_RE.findall(dv):
                    errors.append(
                        '%s: `%s` 用了裸占位符 %%s/%%d —— 必须写成 %%1$s/%%2$d：'
                        '语序不同的语言里译者要能调换参数位置' % (t.path, key)
                    )
                if BARE_RE.findall(tv):
                    errors.append(
                        '%s: `%s` 用了裸占位符 %%s/%%d，必须与默认语言一样用位置参数'
                        % (t.path, key)
                    )
                if malformed_percent(tv):
                    errors.append(
                        '%s: `%s` 的格式串里有不成对的 %% —— 运行时 String.format 会抛异常（译文=%r）'
                        % (t.path, key, tv[:60])
                    )

            coverage = 100.0 * (len(required) - len(missing)) / max(1, len(required))
            status = 'OK' if not missing else '缺 %d 条' % len(missing)
            print(
                '    %-8s %-10s 条目 %4d / %4d   覆盖率 %6.2f%%   %s'
                % (tag, os.path.basename(os.path.dirname(t.path)), len(t.items),
                   len(required), coverage, status)
            )

            if missing and coverage < min_coverage:
                errors.append(
                    '%s 覆盖率 %.2f%% 低于阈值 %.2f%%（缺 %d 条，例如：%s）'
                    % (t.path, coverage, min_coverage, len(missing), ', '.join(missing[:5]))
                )

        if not translations and name == 'app':
            warnings.append('只有默认语言，语言页与语言清单都不会出现第二种语言')

    # ── 跨模块：库模块的语言必须是 :app 的子集 ──────────────────────────
    # `android:localeConfig` 由 AGP 按 **:app 自己的 res 目录**生成（不含库模块）。
    # 所以只在库模块里加 values-de/ 会出现：通知是德文、界面是中文、语言列表里还没有德语 ——
    # 用户既无法主动选中它，也说不清自己看到的是什么。
    for name, res in mods:
        if name == 'app':
            continue
        _, translations, _ = collect(res)
        orphan = sorted({tag for tag, _ in translations} - app_locales)
        if orphan:
            errors.append(
                ':%s 提供了 :app 没有的语言 %s —— android:localeConfig 按 :app 的 res 生成，'
                '这些语言不会出现在语言列表里；请在 :app 下补同名 values-*/ 目录'
                % (name, ', '.join(orphan))
            )

    return errors, warnings


def main():
    ap = argparse.ArgumentParser(description='i18n 资源校验（结构 / 占位符 / translatable / 覆盖率）')
    ap.add_argument('--root', default='.', help='仓库根目录')
    ap.add_argument(
        '--min-coverage', type=float, default=100.0,
        help='每种语言的最低覆盖率（默认 100：不允许发半成品翻译）。'
             '全量翻译期间可以调低，但调低就等于允许界面中英混排。',
    )
    args = ap.parse_args()

    errors, warnings = check(os.path.abspath(args.root), args.min_coverage)

    print()
    for w in warnings:
        print('⚠️  %s' % w)
    for e in errors:
        print('❌ %s' % e)

    if errors:
        print('\n共 %d 处错误。' % len(errors))
        return 1
    print('✅ i18n 校验通过（%d 条提示）。' % len(warnings))
    return 0


if __name__ == '__main__':
    sys.exit(main())
