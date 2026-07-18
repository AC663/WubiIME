package com.wubi.ime.engine;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DictEngine {

    public static class Candidate {
        public final String word;
        public final int    freq;
        public final String code;
        public Candidate(String word, int freq, String code) {
            this.word=word; this.freq=freq; this.code=code;
        }
    }

    private final java.util.TreeMap<String, List<int[]>> wubiIndex   = new java.util.TreeMap<>();
    private final java.util.TreeMap<String, List<int[]>> pinyinIndex = new java.util.TreeMap<>();
    private final java.util.TreeMap<String, List<int[]>> briefIndex  = new java.util.TreeMap<>();

    private final List<String> wubiWords   = new ArrayList<>();
    private final List<String> pinyinWords = new ArrayList<>();

    private boolean loaded = false;

    public void load(Context ctx) {
        if (loaded) return;
        loadDict(ctx, "dict/wubi86.dict",  wubiIndex,   wubiWords,   false);
        loadDict(ctx, "dict/pinyin.dict",  pinyinIndex, pinyinWords, true);
        loaded = true;
    }

    private void loadDict(Context ctx, String path,
                          Map<String,List<int[]>> index,
                          List<String> words, boolean hasBrief) {
        try (InputStream is = ctx.getAssets().open(path);
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split("\t", 3);
                if (p.length < 2) continue;
                String code = p[0].trim();
                String word = p[1].trim();
                if (code.isEmpty() || word.isEmpty()) continue;
                int freq = 0;
                if (p.length >= 3) {
                    try { freq = Integer.parseInt(p[2].trim()); }
                    catch (NumberFormatException ignored) {}
                }

                if (hasBrief && code.startsWith("~")) {
                    String brief = code.substring(1);
                    // 找已有词索引
                    int found = -1;
                    for (int i = words.size()-1; i >= Math.max(0, words.size()-10); i--) {
                        if (words.get(i).equals(word)) { found = i; break; }
                    }
                    if (found < 0) { found = words.size(); words.add(word); }
                    briefIndex.computeIfAbsent(brief, k -> new ArrayList<>())
                              .add(new int[]{found, freq});
                } else {
                    int wi = words.size();
                    words.add(word);
                    // 词频重排：convert_rime已按(code, -freq)排序
                    // 直接append，每个code下自然是词频降序
                    index.computeIfAbsent(code, k -> new ArrayList<>())
                         .add(new int[]{wi, freq});
                }
            }
        } catch (IOException ignored) {}
    }

    /** 五笔/全拼查询 */
    public List<Candidate> query(String input, boolean isWubi, int maxN) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        String prefix = input.toLowerCase(Locale.ROOT);
        java.util.NavigableMap<String,List<int[]>> index = isWubi ? wubiIndex : pinyinIndex;
        List<String> words = isWubi ? wubiWords : pinyinWords;
        return doQuery(index, words, prefix, maxN, isWubi);
    }

    /**
     * 连打/整句：把整串拼音切成"已知词/音节"链，拼出整句候选。
     * 动态规划：最大化 Σ log(freq+1) − PEN×词数。
     * PEN 较大 → 偏好更少、更长的真实词（我知道 而非 我之到），同时避免过度碎成单字。
     * 例：woshuoshenme → 我说什么；wozhidao → 我知道；womenzhidao → 我们知道。
     */
    private static final double SENT_PEN = 12.0;
    // 多字词奖励：每多1个汉字 +BONUS，把"总是/这样"整词链推到单字碎链之上——
    //   千变万化短句(你总是这样/我明天再说…)按词成句而非逐字硬拼。
    private static final double WORD_BONUS = 3.0;
    // 容错跳字：某处音节打错/缺失时，允许以重罚跳过1个字母(原样保留输出)，
    //   一处笔误不再让整句连打全盘崩溃返回空。正常可切分路径分数永远占优。
    private static final double SKIP_PEN = SENT_PEN * 3.0;
    // 整句DP单段最大长度：原 6 只容得下≤6字母的词(zhidao)，"晚点wandian"(7码)、三字词等
    //   长词永远无法作为整段命中 → 连打只能碎拼出废词。提到 14(覆盖四字词常见全拼)，
    //   查询是 HashMap 命中，代价可忽略。T9 数字串同理。
    private static final int SENT_MAXSEG = 14;
    public String querySentence(String input) {
        if (input == null || input.isEmpty()) return "";
        String s = input.toLowerCase(Locale.ROOT);
        int n = s.length();
        final double NEG = -1e18;
        double[] dp = new double[n + 1];
        int[] backPos = new int[n + 1];
        String[] backWord = new String[n + 1];
        java.util.Arrays.fill(dp, NEG);
        dp[0] = 0;
        for (int i = 0; i < n; i++) {
            if (dp[i] == NEG) continue;
            int maxSyl = Math.min(n - i, SENT_MAXSEG);
            for (int L = 1; L <= maxSyl; L++) {
                String code = s.substring(i, i + L);
                List<int[]> hit = pinyinIndex.get(code);
                if (hit == null || hit.isEmpty()) continue;
                int wi = hit.get(0)[0];
                int fr = hit.get(0)[1];
                if (wi >= pinyinWords.size()) continue;
                String w = pinyinWords.get(wi);
                double sc = dp[i] + Math.log(fr + 1) + WORD_BONUS * (w.length() - 1) - SENT_PEN;
                if (sc > dp[i + L]) {
                    dp[i + L] = sc;
                    backPos[i + L] = i;
                    backWord[i + L] = w;
                }
            }
            // 容错跳字(重罚)：该位无任何词可起段时也能续命，笔误字母原样带出
            double sk = dp[i] - SKIP_PEN;
            if (sk > dp[i + 1]) {
                dp[i + 1] = sk;
                backPos[i + 1] = i;
                backWord[i + 1] = s.substring(i, i + 1);
            }
        }
        if (dp[n] == NEG) return "";          // 切不动 → 整句失败
        StringBuilder sb = new StringBuilder();
        int pos = n, skipped = 0;
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        int guard = 0;
        while (pos > 0 && guard++ < 128) {
            String w = backWord[pos];
            if (w.length() == 1 && w.charAt(0) < 0x80) skipped++;   // 跳字段=单个ASCII字母
            stack.push(w);
            pos = backPos[pos];
        }
        if (skipped * 3 > n) return "";       // 跳字过多=基本全是笔误，不冒充整句
        while (!stack.isEmpty()) sb.append(stack.pop());
        return sb.toString();
    }
    // 位置 p 起是否存在任一长度(1..SENT_MAXSEG)的已知 code（用于切分可行性剪枝，上限须与DP一致）
    private boolean canStart(String s, int p) {
        int n = s.length();
        int maxSyl = Math.min(n - p, SENT_MAXSEG);
        for (int L = 1; L <= maxSyl; L++) {
            if (pinyinIndex.containsKey(s.substring(p, p + L))) return true;
        }
        return false;
    }

    // 标准拼音音节表（409个，用于正确切分音节后取声母）
    private static final java.util.Set<String> SYLLABLES = new java.util.HashSet<>(java.util.Arrays.asList(
        "a","ai","an","ang","ao","ba","bai","ban","bang","bao","bei","ben","beng","bi","bian","biao","bie","bin","bing","bo","bu",
        "ca","cai","can","cang","cao","ce","cen","ceng","cha","chai","chan","chang","chao","che","chen","cheng","chi","chong","chou","chu","chua","chuai","chuan","chuang","chui","chun","chuo","ci","cong","cou","cu","cuan","cui","cun","cuo",
        "da","dai","dan","dang","dao","de","dei","den","deng","di","dian","diao","die","ding","diu","dong","dou","du","duan","dui","dun","duo",
        "e","ei","en","eng","er","fa","fan","fang","fei","fen","feng","fo","fou","fu",
        "ga","gai","gan","gang","gao","ge","gei","gen","geng","gong","gou","gu","gua","guai","guan","guang","gui","gun","guo",
        "ha","hai","han","hang","hao","he","hei","hen","heng","hong","hou","hu","hua","huai","huan","huang","hui","hun","huo",
        "ji","jia","jian","jiang","jiao","jie","jin","jing","jiong","jiu","ju","juan","jue","jun",
        "ka","kai","kan","kang","kao","ke","kei","ken","keng","kong","kou","ku","kua","kuai","kuan","kuang","kui","kun","kuo",
        "la","lai","lan","lang","lao","le","lei","leng","li","lia","lian","liang","liao","lie","lin","ling","liu","long","lou","lu","luan","lun","luo","lv","lve",
        "ma","mai","man","mang","mao","me","mei","men","meng","mi","mian","miao","mie","min","ming","miu","mo","mou","mu",
        "na","nai","nan","nang","nao","ne","nei","nen","neng","ni","nian","niang","niao","nie","nin","ning","niu","nong","nou","nu","nuan","nun","nuo","nv","nve",
        "o","ou","pa","pai","pan","pang","pao","pei","pen","peng","pi","pian","piao","pie","pin","ping","po","pou","pu",
        "qi","qia","qian","qiang","qiao","qie","qin","qing","qiong","qiu","qu","quan","que","qun",
        "ran","rang","rao","re","ren","reng","ri","rong","rou","ru","rua","ruan","rui","run","ruo",
        "sa","sai","san","sang","sao","se","sen","seng","sha","shai","shan","shang","shao","she","shei","shen","sheng","shi","shou","shu","shua","shuai","shuan","shuang","shui","shun","shuo","si","song","sou","su","suan","sui","sun","suo",
        "ta","tai","tan","tang","tao","te","teng","ti","tian","tiao","tie","ting","tong","tou","tu","tuan","tui","tun","tuo",
        "wa","wai","wan","wang","wei","wen","weng","wo","wu",
        "xi","xia","xian","xiang","xiao","xie","xin","xing","xiong","xiu","xu","xuan","xue","xun",
        "ya","yan","yang","yao","ye","yi","yin","ying","yo","yong","you","yu","yuan","yue","yun",
        "za","zai","zan","zang","zao","ze","zei","zen","zeng","zha","zhai","zhan","zhang","zhao","zhe","zhei","zhen","zheng","zhi","zhong","zhou","zhu","zhua","zhuai","zhuan","zhuang","zhui","zhun","zhuo","zi","zong","zou","zu","zuan","zui","zun","zuo"
    ));
    // 音节后是否还能继续切分（剪枝）
    private static boolean sylCanStart(String s) {
        int mx = Math.min(6, s.length());
        for (int L = mx; L >= 1; L--) if (SYLLABLES.contains(s.substring(0, L))) return true;
        return false;
    }
    // 把全拼按音节表最大匹配切分，再取每个音节的声母（women→wo,men→wm）
    static String toShengmu(String code) {
        java.util.List<String> sy = new java.util.ArrayList<>();
        int i = 0, n = code.length(), guard = 0;
        while (i < n) {
            if (guard++ > 32) return "";
            String matched = null;
            int mx = Math.min(6, n - i);
            for (int L = mx; L >= 1; L--) {
                String c = code.substring(i, i + L);
                if (SYLLABLES.contains(c)) {
                    String rem = code.substring(i + L);
                    if (rem.isEmpty() || sylCanStart(rem)) { matched = c; break; }
                }
            }
            if (matched == null) return "";       // 切不动 → 放弃简拼
            sy.add(matched); i += matched.length();
        }
        String[] INITIALS = {"zh","ch","sh","b","p","m","f","d","t","n","l","g","k","h","j","q","x","r","z","c","s","y","w"};
        StringBuilder sb = new StringBuilder();
        for (String s : sy) {
            String ini = null;
            for (String x : INITIALS) if (s.startsWith(x)) { ini = x; break; }
            sb.append(ini != null ? ini.charAt(0) : s.charAt(0));
        }
        return sb.toString();
    }

    private final java.util.TreeMap<String, List<int[]>> shengmuIndex = new java.util.TreeMap<>();
    private boolean shengmuBuilt = false;
    private void buildShengmu() {
        if (shengmuBuilt) return;
        for (Map.Entry<String,List<int[]>> e : pinyinIndex.entrySet()) {
            String code = e.getKey();
            String word = null;
            int wi0 = e.getValue().get(0)[0];
            if (wi0 < pinyinWords.size()) word = pinyinWords.get(wi0);
            if (word == null || word.length() < 2) continue;   // 只给多字词建简拼
            String sm = toShengmu(code);
            if (sm.length() < 2 || sm.equals(code)) continue;
            for (int[] wf : e.getValue()) {
                shengmuIndex.computeIfAbsent(sm, k -> new ArrayList<>()).add(wf);
            }
        }
        shengmuBuilt = true;
    }

    /** 整词声母简拼查询：wm→我们, sm→什么, zhd→知道 */
    public List<Candidate> queryShengmu(String input, int maxN) {
        if (input == null || input.length() < 2) return Collections.emptyList();
        buildShengmu();
        String sm = input.toLowerCase(Locale.ROOT);
        List<int[]> hit = shengmuIndex.get(sm);
        if (hit == null) return Collections.emptyList();
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // 频率降序
        List<int[]> sorted = new ArrayList<>(hit);
        sorted.sort((a,b)->b[1]-a[1]);
        for (int[] wf : sorted) {
            if (wf[0] < pinyinWords.size()) {
                String w = pinyinWords.get(wf[0]);
                if (seen.add(w)) out.add(new Candidate(w, wf[1], sm));
            }
            if (out.size() >= maxN) break;
        }
        return out;
    }

    /** 简拼查询 */
    public List<Candidate> queryBrief(String input, int maxN) {
        if (input == null || input.length() < 2) return Collections.emptyList();
        return doQuery(briefIndex, pinyinWords,
                input.toLowerCase(Locale.ROOT), maxN, false);
    }

    // ===== T9 九宫格：把拼音码映射为数字串，复用真实词库做候选+连打 =====
    // 字母→数字：abc=2 def=3 ghi=4 jkl=5 mno=6 pqrs=7 tuv=8 wxyz=9
    private static final int[] L2D = new int[26];
    static {
        String[] g = {"abc","def","ghi","jkl","mno","pqrs","tuv","wxyz"};
        for (int d = 0; d < g.length; d++)
            for (char c : g[d].toCharArray()) L2D[c-'a'] = d + 2;
    }
    private static String codeToDigits(String code) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < 'a' || c > 'z') return null;
            sb.append((char)('0' + L2D[c-'a']));
        }
        return sb.toString();
    }
    private final java.util.TreeMap<String, List<int[]>> t9Index = new java.util.TreeMap<>();
    // 与 t9Index 各 bucket 一一对应的拼音真码（供九宫编码栏显示拼音字母，非数字串）
    private final java.util.HashMap<String, List<String>> t9Codes = new java.util.HashMap<>();
    private boolean t9Built = false;
    private void buildT9() {
        if (t9Built) return;
        for (Map.Entry<String,List<int[]>> e : pinyinIndex.entrySet()) {
            String py  = e.getKey();
            String dig = codeToDigits(py);
            if (dig == null) continue;
            List<int[]>   bucket = t9Index.computeIfAbsent(dig, k -> new ArrayList<>());
            List<String>  pys    = t9Codes.computeIfAbsent(dig, k -> new ArrayList<>());
            for (int[] wf : e.getValue()) { bucket.add(wf); pys.add(py); }
        }
        // 每个数字串下按词频降序，bucket 与拼音码同序排（保持一一对应）
        for (Map.Entry<String,List<int[]>> e : t9Index.entrySet()) {
            String dig = e.getKey();
            List<int[]>  b  = e.getValue();
            List<String> pc = t9Codes.get(dig);
            Integer[] idx = new Integer[b.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (x,y)->b.get(y)[1]-b.get(x)[1]);
            List<int[]>  nb = new ArrayList<>(b.size());
            List<String> np = new ArrayList<>(b.size());
            for (Integer i : idx) { nb.add(b.get(i)); np.add(pc.get(i)); }
            b.clear();  b.addAll(nb);
            pc.clear(); pc.addAll(np);
        }
        t9Built = true;
    }
    // 取某数字串 bucket 内第 i 项的拼音真码
    private String t9CodeAt(String dig, int i) {
        List<String> pc = t9Codes.get(dig);
        return (pc != null && i < pc.size()) ? pc.get(i) : dig;
    }

    /**
     * T9 查询：和全拼一样支持单词候选 + 连打整句。
     * digits：用户按下的数字序列(2-9)。
     * 返回候选词列表（整句连打结果置于首位，其后是单/多字词候选）。
     */
    public List<Candidate> queryT9(String digits, int maxN) {
        if (digits == null || digits.isEmpty()) return Collections.emptyList();
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '2' || c > '9') return Collections.emptyList();
        }
        buildT9();
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1) 连打整句：DP 在数字串上切分，每段取该数字串下最高频词
        String[] sr = t9Sentence(digits);
        if (sr != null && sr[0] != null && !sr[0].isEmpty() && sr[0].length() > 1 && seen.add(sr[0])) {
            out.add(new Candidate(sr[0], Integer.MAX_VALUE, sr[1]));
        }

        // 2) 精确数字串候选（该串恰好对应的词，词频降序）
        List<int[]> exact = t9Index.get(digits);
        if (exact != null) {
            for (int ei = 0; ei < exact.size(); ei++) {
                int[] wf = exact.get(ei);
                if (wf[0] < pinyinWords.size()) {
                    String w = pinyinWords.get(wf[0]);
                    if (seen.add(w)) out.add(new Candidate(w, wf[1], t9CodeAt(digits, ei)));
                }
                if (out.size() >= maxN) return out;
            }
        }

        // 3) 前缀候选：数字串前缀匹配，短码优先、词频降序
        List<Candidate> pre = new ArrayList<>();
        int maxLen = digits.length() + 8;
        for (Map.Entry<String,List<int[]>> e : t9Index.tailMap(digits, false).entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(digits)) break;
            if (k.length() > maxLen) continue;
            List<int[]> bk = e.getValue();
            for (int bi = 0; bi < bk.size(); bi++) {
                int[] wf = bk.get(bi);
                if (wf[0] < pinyinWords.size()) {
                    String w = pinyinWords.get(wf[0]);
                    if (seen.add(w)) pre.add(new Candidate(w, wf[1], t9CodeAt(k, bi)));
                }
                if (pre.size() > maxN * 6) break;
            }
            if (pre.size() > maxN * 6) break;
        }
        pre.sort((a,b)->{ int d=a.word.length()-b.word.length(); return d!=0?d:b.freq-a.freq; });
        for (Candidate c : pre) { if (out.size() >= maxN) break; out.add(c); }
        return out;
    }

    // T9 连打：在数字串上 DP，最大化 Σlog(freq+1)−PEN×词数，逐段取该数字串最高频词
    private String[] t9Sentence(String digits) {
        int n = digits.length();
        final double NEG = -1e18;
        double[] dp = new double[n + 1];
        int[] backPos = new int[n + 1];
        String[] backWord = new String[n + 1];
        String[] backPy   = new String[n + 1];
        java.util.Arrays.fill(dp, NEG);
        dp[0] = 0;
        for (int i = 0; i < n; i++) {
            if (dp[i] == NEG) continue;
            int maxSeg = Math.min(n - i, SENT_MAXSEG);
            for (int L = 1; L <= maxSeg; L++) {
                String seg = digits.substring(i, i + L);
                List<int[]> hit = t9Index.get(seg);
                if (hit == null || hit.isEmpty()) continue;
                int wi = hit.get(0)[0];
                int fr = hit.get(0)[1];
                if (wi >= pinyinWords.size()) continue;
                String w = pinyinWords.get(wi);
                double sc = dp[i] + Math.log(fr + 1) + WORD_BONUS * (w.length() - 1) - SENT_PEN;
                if (sc > dp[i + L]) {
                    dp[i + L] = sc; backPos[i + L] = i;
                    backWord[i + L] = w;
                    backPy[i + L]   = t9CodeAt(seg, 0);
                }
            }
            double sk = dp[i] - SKIP_PEN;
            if (sk > dp[i + 1]) {
                dp[i + 1] = sk; backPos[i + 1] = i;
                backWord[i + 1] = digits.substring(i, i + 1);
                backPy[i + 1]   = digits.substring(i, i + 1);
            }
        }
        if (dp[n] == NEG) return null;
        StringBuilder sb = new StringBuilder();
        StringBuilder pb = new StringBuilder();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<String> pstack = new java.util.ArrayDeque<>();
        int pos = n, guard = 0, skipped = 0;
        while (pos > 0 && guard++ < 128) {
            String w = backWord[pos];
            if (w.length() == 1 && w.charAt(0) < 0x80) skipped++;
            stack.push(w); pstack.push(backPy[pos]); pos = backPos[pos];
        }
        if (skipped > 0) return null;   // 收紧:任何未匹配段落=不出整句(绝不把数字/ASCII原样塞进候选)
        while (!stack.isEmpty()) sb.append(stack.pop());
        while (!pstack.isEmpty()) pb.append(pstack.pop());
        return new String[]{ sb.toString(), pb.toString() };
    }


    /**
     * 反查编码：isWubi=true→查汉字的五笔编码；false→查拼音
     * 返回最多3个编码（从短到长）
     */
    public List<String> reverseLookup(String word, boolean isWubi) {
        Map<String,List<int[]>> index = isWubi ? wubiIndex   : pinyinIndex;
        List<String>             words = isWubi ? wubiWords   : pinyinWords;
        List<String[]> found = new ArrayList<>();  // [code, freq]
        for (Map.Entry<String,List<int[]>> e : index.entrySet()) {
            for (int[] wi : e.getValue()) {
                if (wi[0] < words.size() && words.get(wi[0]).equals(word)) {
                    found.add(new String[]{e.getKey(), String.valueOf(wi[1])});
                    break;
                }
            }
        }
        // 按编码长度升序、词频降序排（短码优先）
        found.sort((a, b) -> {
            int dl = a[0].length() - b[0].length();
            if (dl != 0) return dl;
            return Integer.parseInt(b[1]) - Integer.parseInt(a[1]);
        });
        List<String> results = new ArrayList<>();
        for (String[] s : found) {
            if (results.size() >= 3) break;
            if (!results.contains(s[0])) results.add(s[0]);
        }
        return results;
    }

    private List<Candidate> doQuery(java.util.NavigableMap<String,List<int[]>> index,
                                    List<String> words,
                                    String prefix, int maxN, boolean isWubi) {
        Set<String> seen = new HashSet<>();

        // 1. 精确匹配（convert_rime已保证同code下词频降序）
        List<Candidate> exactList = new ArrayList<>();
        List<int[]> exact = index.get(prefix);
        if (exact != null) {
            for (int[] e : exact) {
                if (e[0] < words.size()) {
                    String w = words.get(e[0]);
                    if (seen.add(w)) exactList.add(new Candidate(w, e[1], prefix));
                }
            }
        }

        // 2. 前缀匹配：用 TreeMap.tailMap 从 prefix 处开始，
        //    一旦 key 不再以 prefix 开头立即 break，避免遍历整表（135k 条）
        //    五笔：只收集≤prefix.length+2的前缀；拼音：≤prefix.length+8
        int maxCodeLen = isWubi ? prefix.length() + 2 : prefix.length() + 8;

        List<Candidate> prefixList = new ArrayList<>();
        for (Map.Entry<String,List<int[]>> entry : index.tailMap(prefix, false).entrySet()) {
            String k = entry.getKey();
            if (!k.startsWith(prefix)) break;          // 已越过前缀区间，停止
            if (k.length() > maxCodeLen) continue;
            for (int[] e : entry.getValue()) {
                if (e[0] < words.size()) {
                    String w = words.get(e[0]);
                    if (seen.add(w))
                        prefixList.add(new Candidate(w, e[1], k));
                }
                if (prefixList.size() > maxN * 6) break;
            }
            if (prefixList.size() > maxN * 6) break;
        }

        // 前缀候选排序：编码越短越优先，同长度下词频降序
        prefixList.sort((a, b) -> {
            int la = a.code.length(), lb = b.code.length();
            if (la != lb) return la - lb;
            return b.freq - a.freq;
        });

        // 3. 合并：精确优先 + 前缀补充
        List<Candidate> result = new ArrayList<>();
        result.addAll(exactList);
        for (Candidate c : prefixList) {
            if (result.size() >= maxN) break;
            result.add(c);
        }
        return result;
    }
}