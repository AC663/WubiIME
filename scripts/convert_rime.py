import sys, re

def pinyin_to_code(py):
    return py.replace(" ", "").replace("'", "").replace("_", "")

def pinyin_brief(py):
    parts = re.split(r"[ '_]+", py.strip())
    if len(parts) < 2: return None
    brief = "".join(p[0] for p in parts if p)
    return brief if len(brief) >= 2 else None

def read_rime(infile):
    rows = []   # (word, code_raw, freq)
    in_data = False
    with open(infile, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line == "...": in_data = True; continue
            if not in_data or not line or line.startswith("#"): continue
            parts = line.split("\t")
            if len(parts) < 2: continue
            word = parts[0].strip()
            code = parts[1].strip()
            try:
                freq = int(parts[2].strip()) if len(parts) > 2 else 100
            except ValueError:
                freq = 100
            if not word or not code: continue
            rows.append((word, code, freq))
    return rows

def norm_rank(items, scale):
    # items: list of (full, word, brief_or_None, freq) -> 归一化freq
    items2 = sorted(items, key=lambda x: -x[3])
    N = len(items2); out = []
    for r, (full, word, br, fr) in enumerate(items2):
        nf = int(round(100000 * (1 - r / N) * scale)) + 1
        out.append((full, word, br, nf))
    return out

def convert_pinyin(singles_file, phrases_file, outfile, topN):
    # 单字
    sing_raw = read_rime(singles_file)
    sing = []
    for word, code, fr in sing_raw:
        if len(word) != 1: continue
        full = pinyin_to_code(code)
        if not full or not re.fullmatch(r"[a-z]+", full): continue
        sing.append((full, word, None, fr))   # 单字无简拼
    # 词组
    ph_raw = read_rime(phrases_file)
    ph = []
    for word, code, fr in ph_raw:
        if len(word) < 2: continue
        full = pinyin_to_code(code)
        if not full or not re.fullmatch(r"[a-z]+", full): continue
        br = pinyin_brief(code)
        ph.append((full, word, br if br and br != full else None, fr))
    ph.sort(key=lambda x: -x[3]); ph = ph[:topN]
    # 归一化各自量纲：单字×0.25，词组×1.0
    sing = norm_rank(sing, 0.25)
    ph   = norm_rank(ph, 1.0)
    # 汇总 entries：全拼条目 + 简拼条目(~)
    entries = []; seen = set()
    for full, word, br, nf in sing + ph:
        k = full + "\t" + word
        if k not in seen:
            seen.add(k); entries.append((full, word, nf))
        if br:
            bk = "~" + br + "\t" + word
            if bk not in seen:
                seen.add(bk); entries.append(("~" + br, word, nf))
    entries.sort(key=lambda x: (x[0], -x[2]))
    with open(outfile, "w", encoding="utf-8") as f:
        f.write("\n".join(f"{c}\t{w}\t{fq}" for c, w, fq in entries))
    print(f"拼音转换完成: {len(entries)} 条 -> {outfile}")

def convert_wubi(infile, outfile):
    rows = read_rime(infile)
    entries = []; seen = set()
    for word, code, fr in rows:
        if " " in code: continue
        if not re.fullmatch(r"[a-z]+", code): continue
        key = code + "\t" + word
        if key not in seen:
            seen.add(key); entries.append((code, word, fr))
    entries.sort(key=lambda x: (x[0], -x[2]))
    with open(outfile, "w", encoding="utf-8") as f:
        f.write("\n".join(f"{c}\t{w}\t{fq}" for c, w, fq in entries))
    print(f"五笔转换完成: {len(entries)} 条 -> {outfile}")

if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "pinyin":
        # pinyin <singles> <phrases> <out> <topN>
        convert_pinyin(sys.argv[2], sys.argv[3], sys.argv[4], int(sys.argv[5]))
    else:
        # wubi <in> <out>
        convert_wubi(sys.argv[2], sys.argv[3])
