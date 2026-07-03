#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
安键输入法 · 词库准备脚本（随仓库维护，CI 构建时调用）

用法:
  python3 prepare_dicts.py wubi_supplement <wubi86.yaml> <modern_words.txt> [THUOCL词表.txt ...]
      从 wubi86.yaml 抽取单字全码 → 按五笔86规则给词自动成码 → 追加进 yaml 数据段。
      · modern_words.txt: 现代词清单(高频置顶, freq=500000)
      · THUOCL 词表(可选): 清华开放词库 "词<tab/空格>频率" 格式，每表取前 N 条(freq 归档 120000)
      已存在的词跳过；含生僻字(单字码缺失)的词跳过——绝不会产出错码。

  python3 prepare_dicts.py pinyin_extra <pinyin.dict> <pinyin_extra.txt>
      口语补充词表(词<TAB>带空格拼音<TAB>频率)追加进最终拼音词典，自动含简拼，按(码,词)去重。

  python3 prepare_dicts.py pinyin_dedup <merged.yaml>
      合并多个拼音源(base+ext)后按 (词,音) 去重，保留先出现者(base 优先)，防止候选重复。
"""
import sys, io

def read_text(path):
    for enc in ('utf-8', 'gb18030'):
        try:
            with io.open(path, encoding=enc) as f:
                return f.read()
        except (UnicodeDecodeError, LookupError):
            continue
        except FileNotFoundError:
            return ''
    return ''

def is_han(w):
    return all('\u4e00' <= ch <= '\u9fff' for ch in w)

def load_yaml_entries(path):
    """返回 (已有词集合, 单字→最长全码映射)。yaml 数据段自 '...' 行开始，word\\tcode\\t[freq]"""
    have, char = set(), {}
    data = False
    with io.open(path, encoding='utf-8') as f:
        for ln in f:
            ln = ln.rstrip('\n')
            if ln == '...':
                data = True
                continue
            if not data or not ln or ln.startswith('#'):
                continue
            p = ln.split('\t')
            if len(p) < 2:
                continue
            w, c = p[0].strip(), p[1].strip()
            have.add(w)
            if len(w) == 1 and (w not in char or len(c) > len(char[w])):
                char[w] = c
    return have, char

def wubi_code(word, char):
    """五笔86组词规则：2字=各取前2；3字=1+1+2；≥4字=前三字首码+末字首码"""
    cs = [char.get(ch, '') for ch in word]
    if not all(cs):
        return None
    n = len(cs)
    if n == 1: return cs[0][:4]
    if n == 2: return cs[0][:2] + cs[1][:2]
    if n == 3: return cs[0][:1] + cs[1][:1] + cs[2][:2]
    return cs[0][:1] + cs[1][:1] + cs[2][:1] + cs[-1][:1]

def cmd_wubi_supplement(argv):
    yaml_path, modern_path = argv[0], argv[1]
    thuocl_paths = argv[2:]
    have, char = load_yaml_entries(yaml_path)
    out, added = [], set()

    def push(w, freq):
        if not w or w in have or w in added or not is_han(w) or len(w) > 8:
            return
        c = wubi_code(w, char)
        if c and len(c) >= 2:
            out.append('%s\t%s\t%d' % (w, c, freq))
            added.add(w)

    # ① 现代词清单：高频置顶
    for w in read_text(modern_path).split():
        if w.startswith('#'):
            continue
        push(w, 500000)

    # ② THUOCL 词表：每表限量取词频最高的前 8000 条，freq 归档中高位(不压过现代词/官方高频)
    PER_FILE = 8000
    for path in thuocl_paths:
        rows = []
        for ln in read_text(path).splitlines():
            p = ln.split()
            if len(p) < 2:
                continue
            try:
                rows.append((p[0].strip(), int(p[1])))
            except ValueError:
                continue
        rows.sort(key=lambda x: -x[1])
        for w, _ in rows[:PER_FILE]:
            push(w, 120000)

    with io.open(yaml_path, 'a', encoding='utf-8') as f:
        f.write('\n' + '\n'.join(out) + '\n')
    print('wubi supplement: +%d words (modern+THUOCL)' % len(out))

def cmd_pinyin_extra(argv):
    """pinyin_extra <pinyin.dict> <pinyin_extra.txt>
    口语补充词表追加进最终 pinyin.dict：自动生成全拼码 + 简拼(~声母)条目，
    与已有条目按 (码,词) 去重且保留更高频率。TreeMap 加载与顺序无关，直接重写整文件。"""
    dict_path, extra_path = argv[0], argv[1]
    ent = {}          # (code,word) -> freq
    order = []        # 保持首次出现顺序（稳定输出，方便 diff）
    for ln in read_text(dict_path).splitlines():
        p = ln.split('\t')
        if len(p) < 2:
            continue
        try:
            fr = int(p[2]) if len(p) > 2 else 100
        except ValueError:
            fr = 100
        k = (p[0].strip(), p[1].strip())
        if k not in ent:
            order.append(k)
        ent[k] = max(ent.get(k, 0), fr)
    added = 0
    for ln in read_text(extra_path).splitlines():
        ln = ln.strip()
        if not ln or ln.startswith('#'):
            continue
        p = ln.split('\t')
        if len(p) < 2:
            continue
        word, spaced = p[0].strip(), p[1].strip()
        try:
            fr = int(p[2]) if len(p) > 2 else 90000
        except ValueError:
            fr = 90000
        sylls = [x for x in spaced.split() if x]
        if not word or not sylls:
            continue
        full = ''.join(sylls)
        keys = [(full, word)]
        if len(sylls) >= 2:
            brief = ''.join(x[0] for x in sylls)
            if len(brief) >= 2 and brief != full:
                keys.append(('~' + brief, word))
        for k in keys:
            if k not in ent:
                order.append(k)
                added += 1
            ent[k] = max(ent.get(k, 0), fr)
    with io.open(dict_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join('%s\t%s\t%d' % (k[0], k[1], ent[k]) for k in order))
    print('pinyin extra: +%d new entries, total %d' % (added, len(order)))

def cmd_pinyin_dedup(argv):
    path = argv[0]
    seen, out = set(), []
    data = False
    dropped = 0
    with io.open(path, encoding='utf-8') as f:
        for ln in f:
            ln = ln.rstrip('\n')
            if ln == '...':
                data = True
                out.append(ln)
                continue
            if not data:
                out.append(ln)
                continue
            p = ln.split('\t')
            if len(p) >= 2:
                key = (p[0].strip(), p[1].strip())
                if key in seen:
                    dropped += 1
                    continue
                seen.add(key)
            out.append(ln)
    with io.open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(out) + '\n')
    print('pinyin dedup: kept %d lines, dropped %d duplicates' % (len(out), dropped))

if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == 'wubi_supplement':
        cmd_wubi_supplement(sys.argv[2:])
    elif cmd == 'pinyin_dedup':
        cmd_pinyin_dedup(sys.argv[2:])
    elif cmd == 'pinyin_extra':
        cmd_pinyin_extra(sys.argv[2:])
    else:
        sys.exit('unknown command: ' + cmd)
