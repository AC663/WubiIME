package com.wubi.ime.ime;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.*;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import com.wubi.ime.engine.DictEngine;
import java.util.List;

public class WubiWebViewIME extends InputMethodService {

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DictEngine dictEngine;

    // ===== 私有剪贴板隔离 =====
    //   · 用本输入法期间复制的内容(输入法内 App / 候选词 / 宝主 App 里选中)统一进私有剪贴板，不进系统剪贴板。
    //   · 输入法存活期间(=本输入法被选用期间)全局监听系统剪贴板：一出现新内容就搬进私有剪贴板、随即清空系统剪贴板。
    //   · 粘贴键只从私有剪贴板取最新一条，直接 commitText 敲入，不读系统剪贴板。
    private com.wubi.ime.clipboard.ClipboardManager privClip;
    private android.content.ClipboardManager sysClip;
    private boolean selfClip = false;        // 防回环：忽略“我们自己清空系统剪贴板”触发的回调
    private String lastCaptured = null;       // 去重：避免同一内容反复搬运

    private final android.content.ClipboardManager.OnPrimaryClipChangedListener clipListener =
        new android.content.ClipboardManager.OnPrimaryClipChangedListener() {
            @Override public void onPrimaryClipChanged() { captureAndClearSystemClip(); }
        };

    // 把系统剪贴板新内容搬进私有剪贴板，随即清空系统剪贴板(全局隔离)。
    // 抗“抢时序”：三重防护—— selfClip 标记跳过自己的清空、空内容直接跳、lastCaptured 去重，彻底避免回环。
    private void captureAndClearSystemClip() {
        try {
            if (selfClip) { selfClip = false; return; }   // 自己清空导致的回调 → 跳过
            // 隐私铁律：仅在本输入法正在屏幕上使用(键盘可见)时才收录复制内容；
            //   后台/未打字期间对全设备任意 App 的复制一律不碰、不记录、不清空。
            if (!isInputViewShown()) return;
            // #9 自家宿主隔离：在自家应用窗口(笔记本/密码器)里打字期间的复制，只归应用
            //   自己的"贴"，绝不进输入法私有剪贴板(两套剪贴各自独立，互不串门)。
            try {
                android.view.inputmethod.EditorInfo ei = getCurrentInputEditorInfo();
                if (ei != null && getPackageName().equals(ei.packageName)) return;
            } catch (Throwable t) {}
            if (sysClip == null || privClip == null) return;
            android.content.ClipData clip = sysClip.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence cs = clip.getItemAt(0).coerceToText(this);
            if (cs == null) return;
            String text = cs.toString();
            if (text.length() == 0) return;               // 空(含我们清空后的结果) → 跳过
            if (text.equals(lastCaptured)) return;         // 已搬过的同一内容 → 跳过
            lastCaptured = text;
            privClip.add(text);                            // 收进私有剪贴板(无论是否隔离，历史都要)
            // 剪贴隔离开关(默认开)：开=随即清空系统剪贴板(隐私优先，其它App读不到)；
            //   关=保留系统剪贴板(跨App粘贴不受影响)。修复“隐患：清剪贴板破坏跨App粘贴”。
            boolean isolate = getSharedPreferences("wubi_ime", MODE_PRIVATE)
                                .getBoolean("clipIsolation", true);
            if (!isolate) return;
            selfClip = true;                               // 标记：下面这次清空是我们自己干的
            if (android.os.Build.VERSION.SDK_INT >= 28) sysClip.clearPrimaryClip();
            else sysClip.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
        } catch (Throwable t) { selfClip = false; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        dictEngine = new DictEngine();
        new Thread(() -> dictEngine.load(this)).start();
        // 私有剪贴板 + 系统剪贴板监听(全局：服务存活=本输入法被选用期间)
        privClip = new com.wubi.ime.clipboard.ClipboardManager(this);
        sysClip = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (sysClip != null) {
            try { sysClip.addPrimaryClipChangedListener(clipListener); } catch (Throwable t) {}
        }
    }

    @Override
    public View onCreateInputView() {
        android.view.Window win = getWindow().getWindow();
        if (win != null) {
            win.setBackgroundDrawableResource(android.R.color.transparent);
            applySecureFlag(win);
        }

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);   // iframe 从 file:// 父页加载 file:// 子页(内嵌应用)需要
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.addJavascriptInterface(new JsBridge(), "Android");
        webView.addJavascriptInterface(new PwBridge(), "AndroidPW");
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        webView.setHapticFeedbackEnabled(false);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // 渲染进程被系统杀死/崩溃 → 立即整体重建键盘，绝不停留在白屏
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                handler.post(() -> rebuildWebView());
                return true;   // 已接管，不让系统连带杀掉输入法进程
            }
            @Override
            public void onPageFinished(WebView view, String url) { pageReady = true; }
        });

        pageReady = false;
        webView.loadUrl("file:///android_asset/keyboard.html");
        return webView;
    }

    // 白屏核弹级恢复：销毁旧 WebView，重建输入视图。
    //   主题/输入模式/开关等状态由 localStorage(+持久层镜像)回灌，以最后一次的样子呼出。
    //   ⚠️ destroy() 严禁对仍挂在视图树上的 WebView 调用(官方铁律，带挂销毁=部分机型直接崩进程，
    //   即"白屏→看门狗重建→崩溃"连锁的根源)：先换新视图 → 旧视图脱树 → post 后销毁。
    private void rebuildWebView() {
        final WebView old = webView;
        webView = null;
        wdSeq++;   // 作废所有在途看门狗回调，防止旧序号误判新视图
        try { setInputView(onCreateInputView()); } catch (Throwable t) {}
        if (old != null) {
            try {
                android.view.ViewParent p = old.getParent();
                if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(old);
            } catch (Throwable t) {}
            try { old.loadUrl("about:blank"); } catch (Throwable t) {}
            handler.post(() -> { try { old.destroy(); } catch (Throwable t) {} });
        }
    }
    // 看门狗序号：每次弹出 ping 一次 JS，超时无回声=页面死 → rebuildWebView
    private volatile int wdSeq = 0;
    private volatile boolean wdOk = false;       // JS 存活回声
    private volatile boolean wdBeatOk = false;   // rAF 渲染心跳回声(JS活但画面冻结时不触发)
    private volatile boolean pageReady = false;  // keyboard.html onPageFinished 后才允许看门狗判死
    private long lastRepairAt = 0;               // onStartInputView 与 onWindowShown 双触发去重
    private long lastWindowHiddenAt = 0;         // #2 键盘窗最近一次隐藏时刻：判定 surface 是否可能过期

    // 根据用户设置应用/取消防截屏（默认开启；可在输入法内临时关闭以便自己截图调试）
    private void applySecureFlag(android.view.Window win) {
        // 默认关闭防截屏：避免影响用户正常截图/录屏；需要隐私保护时在剪贴板面板顶部手动开启
        boolean secure = getSharedPreferences("wubi_ime", MODE_PRIVATE)
                            .getBoolean("flagSecure", false);
        if (secure) {
            win.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                         android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    // 最近一次宿主回传的光标/字符坐标信息（用于算视觉行边界）
    private volatile android.view.inputmethod.CursorAnchorInfo lastAnchorInfo;
    // 选区会话：所有 Alt 选区操作(上下左右)统一用 setSelection 驱动，活动端连续。
    //   selAnchor = 锚点(选区固定的一端)，selActive = 活动端(随方向键移动的一端)。
    //   -1 表示当前无会话。任何非选中移动/输入都会重置(置 -1)，下次选区重新开会话。
    private int selAnchor = -1;
    private int selActive = -1;
    private int selAnchorFixed = -1;   // 旧字段保留(已弃用)
    private volatile boolean hostIsSelf = false;   // 宿主=自家应用(记事本等 WebView 富文本)：选中走 Shift+DPAD 通道
    private final java.util.concurrent.atomic.AtomicBoolean mcBusy =
        new java.util.concurrent.atomic.AtomicBoolean(false);   // moveCursor 忙标志：长按连发忙则丢帧，绝不堆积主线程队列

    @Override
    public void onUpdateCursorAnchorInfo(android.view.inputmethod.CursorAnchorInfo cursorAnchorInfo) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo);
        lastAnchorInfo = cursorAnchorInfo;
    }

    // 主动请求一次光标坐标更新（用于选区操作前刷新，提升微信等连续操作的坐标新鲜度）
    private void reqCursorOnce() {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.requestCursorUpdates(
                    android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR
                    | android.view.inputmethod.InputConnection.CURSOR_UPDATE_IMMEDIATE);
            }
        } catch (Throwable t) {}
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // 缺陷#6/#7：向 JS 注入输入框语义提示——URI 类输入框(浏览器地址栏等)→符号面板智能展开"网址"类
        try {
            boolean isUri = false;
            if (info != null) {
                int cls = info.inputType & android.text.InputType.TYPE_MASK_CLASS;
                int var = info.inputType & android.text.InputType.TYPE_MASK_VARIATION;
                isUri = (cls == android.text.InputType.TYPE_CLASS_TEXT
                      && var == android.text.InputType.TYPE_TEXT_VARIATION_URI);
            }
            final String hint = isUri ? "uri" : "text";
            if (webView != null) webView.evaluateJavascript(
                "window.__setEdHint&&window.__setEdHint('" + hint + "')", null);
        } catch (Throwable t) {}
        // 宿主识别：自家记事本/密码器(WebView contenteditable)——getExtractedText/setSelection 在
        //   Chromium 富文本上是跨进程慢调用+行为不稳，选中类操作改走 Shift+DPAD(原生按视觉行选中，精准零卡死)
        try { hostIsSelf = info != null && getPackageName().equals(info.packageName); } catch (Throwable t) { hostIsSelf = false; }
        // 隐私铁律：不再补抓键盘隐藏期间他 App 复制的内容——只收录键盘可见时的当场复制。
        // 独立应用窗口(AppActivity)里复制过的内容 → 重新加载私有剪贴板(同一 prefs，跨窗口同步)
        try { privClip = new com.wubi.ime.clipboard.ClipboardManager(this); } catch (Throwable t) {}
        // Issue1: 从别的 App 切回带输入框的微信等时，复用的 WebView 因切后台被挂起/硬件层 GPU 上下文丢失
        //   → 白屏不显示键盘。切回(onStartInputView)时主动恢复 WebView 渲染并强制重绘一帧。
        // #2 自家应用块间跳焦(笔记本 Tab 下一段等)：键盘窗从未隐藏 → surface 不可能过期，
        //   跳过整套修复仪式(INVISIBLE↔VISIBLE + 渲染层软硬切换)——这套仪式正是"键盘闪一下+卡一下"的肉眼来源。
        boolean __selfHop = hostIsSelf && lastRepairAt > 0 && lastWindowHiddenAt < lastRepairAt;
        if (!__selfHop) {
            lastRepairAt = android.os.SystemClock.uptimeMillis();
            repairSurface();
        }
        startWatchdog();
        // 请求宿主持续回传字符坐标 → 让我们能算出"视觉行"（软换行处）
        InputConnection ic0 = getCurrentInputConnection();
        if (ic0 != null) {
            ic0.requestCursorUpdates(
                android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR
                | android.view.inputmethod.InputConnection.CURSOR_UPDATE_IMMEDIATE);
        }
        if (webView != null) {
            webView.evaluateJavascript("if(typeof onIMEFocus==='function')onIMEFocus();", null);
        }
    }

    // ===== Surface 软复位：切回白屏/停在上一帧的第一道修复 =====
    //   ⚠️ 旧写法"同步 GONE→VISIBLE"在同一帧内被视图系统合并优化、不产生实际 detach/attach，
    //   等于没修。改为 INVISIBLE 立即生效 + post VISIBLE，强制两次独立的布局/绘制提交，
    //   真正让硬件层重建绘制缓冲。
    private void repairSurface() {
        if (webView == null) return;
        try {
            webView.onResume();                 // 解除后台挂起，恢复 JS 计时器与渲染
            webView.resumeTimers();
            webView.setVisibility(View.INVISIBLE);
            webView.requestLayout();
        } catch (Throwable t) {}
        handler.post(() -> {
            if (webView == null) return;
            try { webView.setVisibility(View.VISIBLE); webView.requestLayout(); webView.invalidate(); } catch (Throwable t) {}
        });
        // 强化根治：延迟一帧把渲染层 HARDWARE→SOFTWARE→HARDWARE 切一遍，强制 WebView 丢弃并
        //   重建 GPU 绘制层；再补 JS 侧强制重绘(imeRepaint)。两段 postDelayed 覆盖个别机型首帧不提交。
        handler.postDelayed(() -> {
            if (webView == null) return;
            try {
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                webView.invalidate();
                webView.evaluateJavascript("if(typeof imeRepaint==='function')imeRepaint();", null);
            } catch (Throwable t) {}
        }, 32);
        handler.postDelayed(() -> {
            if (webView == null) return;
            try { webView.invalidate(); webView.evaluateJavascript("if(typeof imeRepaint==='function')imeRepaint();", null); } catch (Throwable t) {}
        }, 140);
    }

    // ===== 两段式看门狗：JS 存活 + rAF 渲染心跳 =====
    //   老看门狗只 ping JS("1"回声)——"有声无画"(触摸有音效但画面冻结)时 JS 活着，永远检不出。
    //   新增 rAF 心跳：画面在合成器正常出帧时 requestAnimationFrame 必然回调；JS 活但心跳死
    //   = Surface 冻结 → 先软复位再验，仍死 → 核弹重建。彻底杜绝"退出宿主再进来"手动救活。
    private void startWatchdog() {
        if (webView == null) return;
        final int seq = ++wdSeq;
        wdOk = false; wdBeatOk = false;
        try { webView.evaluateJavascript("1", v -> { if (seq == wdSeq) wdOk = true; }); } catch (Throwable t) {}
        try { webView.evaluateJavascript("requestAnimationFrame(function(){try{Android.wdBeat(" + seq + ")}catch(e){}});", null); } catch (Throwable t) {}
        handler.postDelayed(() -> {
            if (seq != wdSeq) return;
            if (!wdOk) { if (isInputViewShown()) rebuildWebView(); return; }   // JS 死 → 核弹重建
            if (!wdBeatOk && pageReady && isInputViewShown()) {                // JS 活但渲染冻结 → 软复位
                repairSurface();
                try { webView.evaluateJavascript("requestAnimationFrame(function(){try{Android.wdBeat(" + seq + ")}catch(e){}});", null); } catch (Throwable t) {}
                handler.postDelayed(() -> {
                    if (seq != wdSeq) return;
                    if (!wdBeatOk && pageReady && isInputViewShown()) rebuildWebView();   // 软复位无效 → 核弹重建
                }, 700);
            }
        }, 900);
    }

    // ===== 从独立应用窗口(记事本/密码器 AppActivity)返回：IME 窗口重新显示但可能不触发
    //   onStartInputView(同一输入连接仅 hide→show)——修复逻辑从未运行，这正是"从笔记本返回
    //   键盘有声效但卡死、必须退出宿主再进才激活"的直接原因。onWindowShown 补上这条通路。=====
    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        lastWindowHiddenAt = android.os.SystemClock.uptimeMillis();   // #2 隐藏过=下次必须修复
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastRepairAt < 250) return;   // onStartInputView 刚跑过同一套，去重
        lastRepairAt = now;
        repairSurface();
        startWatchdog();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        if (webView != null) {
            webView.evaluateJavascript("if(typeof onIMEBlur==='function')onIMEBlur();", null);
        }
    }

    /** 把候选列表序列化为 JSON 字符串，不依赖 org.json */
    private String candsToJson(List<DictEngine.Candidate> cands) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cands.size(); i++) {
            DictEngine.Candidate c = cands.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"w\":\"");
            // 转义双引号和反斜杠
            for (char ch : c.word.toCharArray()) {
                if (ch == '"') sb.append("\\\"");
                else if (ch == '\\') sb.append("\\\\");
                else sb.append(ch);
            }
            sb.append("\",\"f\":").append(c.freq);
            // 拼音编码(去掉简拼前缀~)，供九宫格编码栏显示拼音字母
            sb.append(",\"c\":\"");
            String code = c.code == null ? "" : c.code;
            if (code.startsWith("~")) code = code.substring(1);
            for (char ch : code.toCharArray()) {
                if (ch == '"') sb.append("\\\"");
                else if (ch == '\\') sb.append("\\\\");
                else sb.append(ch);
            }
            sb.append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    class JsBridge {

        // 渲染心跳回执：rAF 回调触达=合成器在正常出帧(binder线程回调，只置 volatile 标志，零风险)
        @JavascriptInterface
        public void wdBeat(final int seq) { if (seq == wdSeq) wdBeatOk = true; }

        // Ctrl+Shift：切换至设备已安装的下一个输入法。API 28+ 走 InputMethodService 官方通道；
        //   低版本回退 InputMethodManager(token)。均需主线程调用。
        @JavascriptInterface
        public void switchNextIME() {
            handler.post(() -> {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        if (switchToNextInputMethod(false)) return;
                    }
                } catch (Throwable t) {}
                try {
                    android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                    android.os.IBinder tok = getWindow().getWindow().getAttributes().token;
                    if (imm != null && tok != null) imm.switchToNextInputMethod(tok, false);
                } catch (Throwable t) {}
            });
        }

        @JavascriptInterface
        public String queryWubi(final String code) {
            if (dictEngine == null || code == null || code.isEmpty()) return "[]";
            List<DictEngine.Candidate> cands = dictEngine.query(code, true, 600);
            return candsToJson(cands);
        }

        @JavascriptInterface
        public String queryPinyin(final String code) {
            if (dictEngine == null || code == null || code.isEmpty()) return "[]";
            List<DictEngine.Candidate> cands = dictEngine.query(code, false, 600);   // 放开截断：低频单字(拾/etc)在展开池可翻到，主行由JS侧slice
            return candsToJson(cands);
        }

        // 连打整句：返回整串拼音切分拼出的整句（失败返回空串）
        @JavascriptInterface
        public String querySentence(final String code) {
            if (dictEngine == null || code == null || code.isEmpty()) return "";
            return dictEngine.querySentence(code);
        }

        // 整词简拼：声母缩写查词（wm→我们）
        @JavascriptInterface
        public String queryBrief(final String code) {
            if (dictEngine == null || code == null || code.isEmpty()) return "[]";
            List<DictEngine.Candidate> cands = dictEngine.queryShengmu(code, 60);
            return candsToJson(cands);
        }

        // T9 九宫格：数字串查真实词库（含连打整句），与全拼同等质量
        @JavascriptInterface
        public String queryT9(final String digits) {
            if (dictEngine == null || digits == null || digits.isEmpty()) return "[]";
            List<DictEngine.Candidate> cands = dictEngine.queryT9(digits, 600);
            return candsToJson(cands);
        }

        // 独立应用「真·独立窗口」：另起全屏 AppActivity(独立于输入法窗口之外，像普通App一样弹出)。
        //   成功返回 true；失败(极端机型限制后台起 Activity)返回 false，JS 回退键盘内 overlay。
        @JavascriptInterface
        public boolean openAppWindow(final String name) {
            try {
                String safe = name == null ? "" : name.replaceAll("[^a-zA-Z0-9_]", "");
                if (safe.isEmpty()) return false;
                android.content.Intent it = new android.content.Intent(WubiWebViewIME.this, AppActivity.class);
                it.putExtra("app", safe);
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                          | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(it);
                // 收起键盘；笔记本里聚焦输入框时系统会重新唤起本输入法
                handler.post(() -> { try { requestHideSelf(0); } catch (Throwable t) {} });
                return true;
            } catch (Throwable t) {
                android.util.Log.e("WubiIME", "openAppWindow failed", t);
                return false;
            }
        }

        @JavascriptInterface
        public void commitText(final String text) {
            handler.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(text, 1);
            });
        }

        @JavascriptInterface
        public void commitEnter() {
            handler.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    long now = android.os.SystemClock.uptimeMillis();
                    ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0));
                    ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0));
                }
            });
        }

        @JavascriptInterface
        public void deleteBefore() {
            handler.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                // 自家 WebView 宿主(记事本/密码器)：线程铁律——绝不做读取型 IC 调用。
                //   getExtractedText/getTextBeforeCursor 对 Chromium 富文本是跨进程同步等待，
                //   主线程调=卡 UI(空块连按删除→连发+白屏的根因)。改单向投递 DEL 键：
                //   Chromium 原生按字素/选区删除，空块的 Backspace keydown 由 JS 侧接管并块。
                if (hostIsSelf) {
                    sendKey(ic, android.view.KeyEvent.KEYCODE_DEL, 0);
                    return;
                }
                // 若存在选区，删除整个选区（而非光标前1字符）
                android.view.inputmethod.ExtractedTextRequest req =
                    new android.view.inputmethod.ExtractedTextRequest();
                android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);
                if (et != null) {
                    int s = et.selectionStart, e = et.selectionEnd;
                    if (s >= 0 && e >= 0 && s != e) {
                        ic.commitText("", 1);   // 用空串替换选区 = 删除选中内容
                        return;
                    }
                }
                // 智能删除：若光标前是 emoji/旗帜字簇，一次删掉整簇（旗帜=2个区域指示符=4个char，
                // 否则系统退格每次只删1个char，旗帜要按4次才能删掉）
                CharSequence before = ic.getTextBeforeCursor(8, 0);
                int n = graphemeDeleteCount(before);
                ic.deleteSurroundingText(n, 0);
            });
        }

        // 计算光标前一个"字簇"应删除的 char 数：
        // 旗帜(两个 U+1F1E6..U+1F1FF 区域指示符)=4；带变体/ZWJ 的 emoji 整体回删；普通字符=1。
        private int graphemeDeleteCount(CharSequence before) {
            if (before == null || before.length() == 0) return 1;
            int len = before.length();
            // 末尾两个码点是否都是区域指示符 → 旗帜，删4个char
            if (len >= 4 && isRegionalPair(before, len)) return 4;
            // 末尾是否为一个代理对(emoji，2个char)
            char last = before.charAt(len - 1);
            if (len >= 2 && Character.isLowSurrogate(last)
                    && Character.isHighSurrogate(before.charAt(len - 2))) {
                int count = 2;
                // 处理 变体选择符 FE0F / ZWJ 序列：继续向前并合并
                int i = len - 2;
                while (i - 1 >= 0) {
                    char c = before.charAt(i - 1);
                    if (c == '\u200D') {                       // ZWJ：再吞一个 emoji
                        i -= 1; count += 1;
                        if (i - 2 >= 0 && Character.isLowSurrogate(before.charAt(i - 1))
                                && Character.isHighSurrogate(before.charAt(i - 2))) {
                            i -= 2; count += 2;
                        }
                    } else if (c == '\uFE0F') {                 // 变体选择符
                        i -= 1; count += 1;
                    } else break;
                }
                return count;
            }
            return 1;   // 普通字符
        }
        private boolean isRegionalPair(CharSequence s, int end) {
            // 检查 [end-4, end) 是否为两个区域指示符代理对
            if (end < 4) return false;
            return isRegionalAt(s, end - 4) && isRegionalAt(s, end - 2);
        }
        private boolean isRegionalAt(CharSequence s, int idx) {
            if (idx + 1 >= s.length()) return false;
            char hi = s.charAt(idx), lo = s.charAt(idx + 1);
            if (!Character.isHighSurrogate(hi) || !Character.isLowSurrogate(lo)) return false;
            int cp = Character.toCodePoint(hi, lo);
            return cp >= 0x1F1E6 && cp <= 0x1F1FF;
        }

        @JavascriptInterface
        public void moveCursor(final int direction, final boolean select) {
            // ===== 自家宿主(记事本/密码器 WebView)：全方向统一走【组合键单向投递】=====
            //   铁律：对 Chromium WebView 绝不做任何读取型 IC 调用(getText*/getExtractedText/getSelectedText)
            //   ——那些是跨进程同步等待，binder 线程调必死锁、主线程调必卡 UI(真机：←→卡死/Alt层全卡死元凶)。
            //   sendKeyEvent 是单向投递零等待；Chromium contenteditable 对方向/Home/End/Shift扩选/Ctrl+Home/End
            //   全部原生支持且按视觉行精准；记事本多块结构下 TAB 恰好=跳下一段(块)。
            if (hostIsSelf) {
                handler.post(() -> { try {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic == null) return;
                    final int SH = android.view.KeyEvent.META_SHIFT_ON | android.view.KeyEvent.META_SHIFT_LEFT_ON;
                    final int CT = android.view.KeyEvent.META_CTRL_ON  | android.view.KeyEvent.META_CTRL_LEFT_ON;
                    int kc = -1, meta = 0;
                    if (select) {
                        meta = SH;
                        switch (direction) {
                            case 0:  kc = android.view.KeyEvent.KEYCODE_DPAD_LEFT;  break;
                            case 1:  kc = android.view.KeyEvent.KEYCODE_DPAD_RIGHT; break;
                            case 2: case 10: kc = android.view.KeyEvent.KEYCODE_DPAD_UP;   break;
                            case 3: case 11: kc = android.view.KeyEvent.KEYCODE_DPAD_DOWN; break;
                            case 8:  kc = android.view.KeyEvent.KEYCODE_MOVE_HOME;  break;
                            case 9:  kc = android.view.KeyEvent.KEYCODE_MOVE_END;   break;
                        }
                    } else {
                        selAnchor = -1; selActive = -1; selAnchorFixed = -1;
                        switch (direction) {
                            case 0:  kc = android.view.KeyEvent.KEYCODE_DPAD_LEFT;  break;
                            case 1:  kc = android.view.KeyEvent.KEYCODE_DPAD_RIGHT; break;
                            case 2:  kc = android.view.KeyEvent.KEYCODE_DPAD_UP;    break;
                            case 3:  kc = android.view.KeyEvent.KEYCODE_DPAD_DOWN;  break;
                            case 4:  kc = android.view.KeyEvent.KEYCODE_MOVE_HOME;  break;               // 行首
                            case 5:  kc = android.view.KeyEvent.KEYCODE_MOVE_END;   break;               // 行尾
                            case 6:  kc = android.view.KeyEvent.KEYCODE_MOVE_HOME;  meta = CT; break;    // 文首=Ctrl+Home
                            case 7:  kc = android.view.KeyEvent.KEYCODE_MOVE_END;   meta = CT; break;    // 文末=Ctrl+End
                            case 12: case 13: kc = android.view.KeyEvent.KEYCODE_TAB; break;             // 跳段/跳项=TAB(多块结构下即下一块)
                        }
                    }
                    if (kc >= 0) sendKey(ic, kc, meta);
                } catch (Throwable t) {} });
                return;
            }
            // ===== 外部宿主：纯左右移(触控条主通道) post 化 + 边界内查(EditText 进程内毫秒级，安全) =====
            if (!select && (direction == 0 || direction == 1)) {
                handler.post(() -> { try {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic == null) return;
                    // 越界最后一道闸(与JS边界锁双保险)：已到文首/末绝不再发 DPAD——
                    //   文末继续发 DPAD 会把焦点推到宿主下一个控件，光标"冲出消失"的元凶。
                    try {
                        if (direction == 0) {
                            CharSequence b = ic.getTextBeforeCursor(1, 0);
                            if (b != null && b.length() == 0) return;
                        } else {
                            CharSequence a = ic.getTextAfterCursor(1, 0);
                            if (a != null && a.length() == 0) return;
                        }
                    } catch (Throwable ig) {}
                    selAnchor = -1; selActive = -1; selAnchorFixed = -1;
                    sendKey(ic, direction == 0 ? android.view.KeyEvent.KEYCODE_DPAD_LEFT
                                               : android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0);
                } catch (Throwable t) {} });
                return;
            }
            // 忙则丢帧：上一次 moveCursor 还没跑完(慢宿主 getExtractedText 中)就再来一拍，直接丢——
            //   连发场景丢帧无感，但主线程队列永不堆积，根治"长按方向键把界面灌死"。
            if (!mcBusy.compareAndSet(false, true)) return;
            handler.post(() -> {
              try {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                // 非选中类移动(纯移光标) → 结束选区会话，下次选区重新开会话
                if (!select) { selAnchor = -1; selActive = -1; selAnchorFixed = -1; }

                // ===== 纯移动光标(非选中)：一律发键事件交宿主，绝不用 setSelection 自算 =====
                //   根因——ExtractedText 在长文本时会被宿主截断(et.text 只是光标附近片段)，
                //   而 selectionStart/End 是绝对偏移，两者基准不一致：用 Math.min(片段长, ...) 会把
                //   右移卡在片段末(无法跨行)、文末跳到片段末(光标切丢)。宿主自己持有全文，发键最可靠。
                if (!select && (direction == 6 || direction == 7)) {
                    // 文首(6)/文末(7)：Ctrl+MOVE_HOME/END 在多数 App 跳到全文首尾；
                    //   小米记事本对 Ctrl 修饰可能只到行首/尾，故配合 META_CTRL_ON 提示宿主走文档级。
                    int CTRL = android.view.KeyEvent.META_CTRL_ON;
                    if (direction == 6) { sendKey(ic, android.view.KeyEvent.KEYCODE_MOVE_HOME, CTRL); return; }
                    if (direction == 7) { sendKey(ic, android.view.KeyEvent.KEYCODE_MOVE_END, CTRL); return; }
                }

                // ===== Alt 选区操作(8/9/10/11 上下行与行首尾，0/1 左右)：统一会话 + setSelection =====
                // 全程纯 setSelection 驱动，不发 Shift+DPAD：
                //   · 干净——不触发宿主触摸手势的放大镜/选择菜单，不撞架(微信/记事本一致)
                //   · 活动端连续——上选后活动端在上方，左右微调基于上方活动端，而非起始行
                //   · 微信也能选中——setSelection 微信支持(左右已验证流畅)
                if (select && (direction == 0 || direction == 1
                        || direction == 8 || direction == 9
                        || direction == 10 || direction == 11)) {
                    android.view.inputmethod.ExtractedTextRequest rq =
                        new android.view.inputmethod.ExtractedTextRequest();
                    android.view.inputmethod.ExtractedText et = ic.getExtractedText(rq, 0);
                    if (et == null || et.text == null) {
                        int SH = android.view.KeyEvent.META_SHIFT_ON;
                        int kc = (direction==0)?android.view.KeyEvent.KEYCODE_DPAD_LEFT
                               :(direction==1)?android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                               :(direction==10)?android.view.KeyEvent.KEYCODE_DPAD_UP
                               :(direction==11)?android.view.KeyEvent.KEYCODE_DPAD_DOWN
                               :(direction==8)?android.view.KeyEvent.KEYCODE_MOVE_HOME
                               :android.view.KeyEvent.KEYCODE_MOVE_END;
                        sendKey(ic, kc, SH);
                        return;
                    }
                    CharSequence full = et.text;
                    int len = full.length();
                    int base = (et.startOffset > 0) ? et.startOffset : 0;
                    int absStart = et.selectionStart;   // 宿主报告的真实选区(绝对偏移)
                    int absEnd   = et.selectionEnd;
                    if (absStart < 0) absStart = 0;
                    if (absEnd < 0) absEnd = absStart;
                    // 锚点：会话有效则沿用记录的；否则以宿主当前选区的固定端为锚点。
                    //   活动端：始终取宿主真实选区中"非锚点"的那一端 → 跨片段也正确(不依赖上次存的绝对值)。
                    int anchorAbs;
                    int activeAbs;
                    if (selAnchor >= 0) {
                        anchorAbs = selAnchor;
                        // 活动端 = 当前选区里离锚点远的一端
                        activeAbs = (Math.abs(absEnd - anchorAbs) >= Math.abs(absStart - anchorAbs)) ? absEnd : absStart;
                    } else {
                        // 新会话：无选区时锚点=光标；有选区时锚点取 start，活动端取 end
                        anchorAbs = (absStart == absEnd) ? absEnd : absStart;
                        activeAbs = absEnd;
                    }
                    int acRel = activeAbs - base;
                    if (acRel < 0) acRel = 0; if (acRel > len) acRel = len;
                    int newActiveRel;
                    switch (direction) {
                        case 0: newActiveRel = Math.max(0, acRel - 1); break;
                        case 1: newActiveRel = Math.min(len, acRel + 1); break;
                        case 8: newActiveRel = lineStart(full, acRel); break;
                        case 9: newActiveRel = lineEnd(full, acRel); break;
                        case 10: newActiveRel = lineUp(full, acRel); break;
                        case 11: newActiveRel = lineDown(full, acRel); break;
                        default: newActiveRel = acRel;
                    }
                    selAnchor = anchorAbs;
                    selActive = base + newActiveRel;
                    ic.setSelection(selAnchor, selActive);
                    return;
                }

                // ===== 行级移动(非选中)：发键事件，由宿主按真实视觉宽度定行 =====
                if (direction == 4 || direction == 5) {
                    int HOME = android.view.KeyEvent.KEYCODE_MOVE_HOME;
                    int END  = android.view.KeyEvent.KEYCODE_MOVE_END;
                    if (tryVisualLineMove(ic, direction)) return;
                    if (direction == 4) { sendKey(ic, HOME, 0); }
                    else if (direction == 5) { sendKey(ic, END, 0); fixLineEnd(ic); }
                    return;
                }
                // 12=跳段末：光标移到当前段末；已在段末则跳到下一段末（PC Ctrl+↓ 行为）
                if (direction == 12) {
                    jumpParaEnd(ic);
                    return;
                }
                // 13=跨文本框跳转(Tab)：发 TAB 键，宿主表单跳到下一个输入框；
                //    到最后一项再按会循环回第一个由宿主决定(多数表单支持环绕)
                if (direction == 13) {
                    sendKey(ic, android.view.KeyEvent.KEYCODE_TAB, 0);
                    return;
                }

                // ===== 其余(逐字/上下行/文首文末)：用 setSelection 精确控制 =====
                android.view.inputmethod.ExtractedTextRequest req =
                    new android.view.inputmethod.ExtractedTextRequest();
                android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);

                if (et == null || et.text == null) {
                    // 取不到全文：回退到方向键事件
                    int kc;
                    switch (direction) {
                        case 0: kc = android.view.KeyEvent.KEYCODE_DPAD_LEFT;  break;
                        case 1: kc = android.view.KeyEvent.KEYCODE_DPAD_RIGHT; break;
                        case 2: kc = android.view.KeyEvent.KEYCODE_DPAD_UP;    break;
                        case 3: kc = android.view.KeyEvent.KEYCODE_DPAD_DOWN;  break;
                        case 6: kc = android.view.KeyEvent.KEYCODE_MOVE_HOME;  break; // 文首回退
                        case 7: kc = android.view.KeyEvent.KEYCODE_MOVE_END;   break; // 文末回退
                        default: return;
                    }
                    int meta = select ? android.view.KeyEvent.META_SHIFT_ON : 0;
                    long now = android.os.SystemClock.uptimeMillis();
                    ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_DOWN, kc, 0, meta));
                    ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_UP, kc, 0, meta));
                    return;
                }

                CharSequence full = et.text;
                int len = full.length();
                int selStart = et.selectionStart;
                int selEnd   = et.selectionEnd;
                if (selStart < 0) selStart = 0;
                if (selEnd   < 0) selEnd   = selStart;

                // 活动端 = selEnd（移动/扩选的一端），锚点 = selStart
                int active = selEnd;
                switch (direction) {
                    case 0: active = Math.max(0, active - 1);   break; // 左：减1字符
                    case 1: active = Math.min(len, active + 1); break; // 右：加1字符
                    case 2: active = lineUp(full, active);      break; // 上：活动端上移一整行(保列)
                    case 3: active = lineDown(full, active);    break; // 下：活动端下移一整行(保列)
                    case 6: active = 0;                         break; // 全文开头
                    case 7: active = len;                       break; // 全文末尾
                    default: return;
                }
                if (select) {
                    ic.setSelection(selStart, active);
                } else {
                    ic.setSelection(active, active);
                }
              } catch (Throwable t) {
                // 任何异常都不致命：吞掉，保证 handler 线程存活、键盘后续按键仍响应（防卡死）
              } finally {
                mcBusy.set(false);   // 无论走哪条 return/异常路径都释放忙标志
              }
            });
        }

        // 用 CursorAnchorInfo 的逐字符坐标算"视觉行"边界，精确移动/选择；
        // 成功返回 true（已处理），无坐标信息或算不出返回 false（让调用方退回键事件）。
        private boolean tryVisualLineMove(InputConnection ic, int direction) {
            android.view.inputmethod.CursorAnchorInfo info = lastAnchorInfo;
            if (info == null) return false;
            android.view.inputmethod.ExtractedTextRequest req =
                new android.view.inputmethod.ExtractedTextRequest();
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);
            if (et == null || et.text == null) return false;
            int len = et.text.length();
            int sel = et.selectionEnd; if (sel < 0) sel = 0;
            int anchor = et.selectionStart; if (anchor < 0) anchor = sel;

            Float curY = charMidY(info, sel);
            if (curY == null) curY = charMidY(info, Math.max(0, sel - 1));
            if (curY == null) return false;
            final float TOL = 6f;   // 同一视觉行的 Y 容差(px)

            int target;
            if (direction == 4 || direction == 8) {
                int i = sel;
                while (i - 1 >= 0) { Float y = charMidY(info, i - 1); if (y == null || Math.abs(y - curY) > TOL) break; i--; }
                target = i;
            } else if (direction == 5 || direction == 9) {
                int i = sel;
                while (i < len) { Float y = charMidY(info, i); if (y == null || Math.abs(y - curY) > TOL) break; i++; }
                target = i;
            } else if (direction == 10) {
                // 选至上一视觉行的同列(同 X)位置
                Float curX = charMidX(info, sel);
                if (curX == null) curX = charMidX(info, Math.max(0, sel - 1));
                int ls = sel; while (ls - 1 >= 0) { Float y = charMidY(info, ls - 1); if (y == null || Math.abs(y - curY) > TOL) break; ls--; }
                if (ls == 0) { target = 0; }
                else {
                    Float py = charMidY(info, ls - 1); if (py == null) return false;
                    int pe = ls - 1;                                   // 上一视觉行末尾 offset
                    int ps = pe; while (ps - 1 >= 0) { Float y = charMidY(info, ps - 1); if (y == null || Math.abs(y - py) > TOL) break; ps--; }
                    target = nearestXOffset(info, ps, pe, curX);       // 上一行同列
                }
            } else if (direction == 11) {
                // 选至下一视觉行的同列(同 X)位置
                Float curX = charMidX(info, sel);
                if (curX == null) curX = charMidX(info, Math.max(0, sel - 1));
                int le = sel; while (le < len) { Float y = charMidY(info, le); if (y == null || Math.abs(y - curY) > TOL) break; le++; }
                if (le >= len) { target = len; }
                else {
                    Float ny = charMidY(info, le); if (ny == null) return false;
                    int ns = le;                                       // 下一视觉行行首 offset
                    int ne = ns; while (ne < len) { Float y = charMidY(info, ne); if (y == null || Math.abs(y - ny) > TOL) break; ne++; }
                    target = nearestXOffset(info, ns, ne, curX);       // 下一行同列
                }
            } else return false;

            boolean select = (direction == 8 || direction == 9 || direction == 10 || direction == 11);
            if (select) ic.setSelection(anchor, target);
            else ic.setSelection(target, target);
            return true;
        }

        // 字符中点 Y：用该字符 bounding box 的垂直中心作为"行号"判据
        private Float charMidY(android.view.inputmethod.CursorAnchorInfo info, int offset) {
            try {
                android.graphics.RectF r = info.getCharacterBounds(offset);
                if (r == null) return null;
                return (r.top + r.bottom) / 2f;
            } catch (Throwable t) { return null; }
        }

        // 字符中点 X：用于按列(同 X)对齐上下视觉行的光标等价位置
        private Float charMidX(android.view.inputmethod.CursorAnchorInfo info, int offset) {
            try {
                android.graphics.RectF r = info.getCharacterBounds(offset);
                if (r == null) return null;
                return (r.left + r.right) / 2f;
            } catch (Throwable t) { return null; }
        }

        // 在 [start,end] 区间内找 X 中点最接近 targetX 的字符边界 offset（含 end，允许停在行尾）。
        // targetX 为 null 时退回 end，保证不崩。
        private int nearestXOffset(android.view.inputmethod.CursorAnchorInfo info, int start, int end, Float targetX) {
            if (targetX == null) return end;
            int best = start; float bestD = Float.MAX_VALUE;
            for (int i = start; i <= end; i++) {
                Float x = charMidX(info, i);
                if (x == null) {                          // 行尾 caret 无字符 box，用前一字右边缘近似
                    if (i > start) { try { android.graphics.RectF r = info.getCharacterBounds(i - 1);
                        if (r != null) x = r.right; } catch (Throwable t) {} }
                }
                if (x == null) continue;
                float d = Math.abs(x - targetX);
                if (d < bestD) { bestD = d; best = i; }
            }
            return best;
        }

        // 校正"行末差一字"：仅在 MOVE_END 完全没生效(光标没动)时补救，
        // 不在软换行处误判(软换行处 MOVE_END 已正确停在视觉行尾，光标会移动)。
        private void fixLineEnd(InputConnection ic) {
            // 读光标前后，判断是否处于一个"被截断"的中文行末场景：
            // 经验上微信差一字时，光标后仍剩 1 个非换行字符且这是该段最后一字。
            CharSequence after = ic.getTextAfterCursor(2, 0);
            if (after != null && after.length() == 1 && after.charAt(0) != '\n') {
                // 光标后恰好只剩 1 个字符且非换行 = 段末差一字 → 补一步到真正末尾
                sendKey(ic, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 0);
            }
        }

        // 发送一个带 meta 修饰的按键(down+up)，由宿主处理
        private void sendKey(InputConnection ic, int keyCode, int meta) {
            long now = android.os.SystemClock.uptimeMillis();
            ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta));
            ic.sendKeyEvent(new android.view.KeyEvent(now, now,
                android.view.KeyEvent.ACTION_UP, keyCode, 0, meta));
        }

        // 跳到段末/下一段末：光标置于当前段(以\n分隔)末尾；若已在段末，跳到下一段末；
        // 已是最后一段则回到全文开头(循环，对应 PC 连续 Ctrl+↓ 的体验)。
        private void jumpParaEnd(InputConnection ic) {
            android.view.inputmethod.ExtractedTextRequest req =
                new android.view.inputmethod.ExtractedTextRequest();
            android.view.inputmethod.ExtractedText et = ic.getExtractedText(req, 0);
            if (et == null || et.text == null) {
                sendKey(ic, android.view.KeyEvent.KEYCODE_DPAD_DOWN, 0);
                sendKey(ic, android.view.KeyEvent.KEYCODE_MOVE_END, 0);
                return;
            }
            CharSequence full = et.text;
            int len = full.length();
            int base = (et.startOffset > 0) ? et.startOffset : 0;
            int pos = et.selectionEnd - base;
            if (pos < 0) pos = 0; if (pos > len) pos = len;
            // 找当前位置之后的下一个段末(\n 前一位)；段以 \n 分隔。
            int p = pos;
            // 跳过紧邻的 \n(若正好停在段末)
            while (p < len && full.charAt(p) == '\n') p++;
            int nextNl = p;
            while (nextNl < len && full.charAt(nextNl) != '\n') nextNl++;
            int target;
            if (nextNl > pos && nextNl <= len && !(nextNl == len && pos == len)) {
                target = nextNl;                 // 下一段末
            } else {
                // 已是最后一段/文末 → 循环回文首第一段末
                int firstNl = 0;
                while (firstNl < len && full.charAt(firstNl) != '\n') firstNl++;
                target = firstNl;                // 第一段末(无\n则=len)
            }
            ic.setSelection(base + target, base + target);
        }

        private int lineStart(CharSequence s, int pos) {
            for (int i = pos - 1; i >= 0; i--) {
                if (s.charAt(i) == '\n') return i + 1;
            }
            return 0;
        }
        private int lineEnd(CharSequence s, int pos) {
            for (int i = pos; i < s.length(); i++) {
                if (s.charAt(i) == '\n') return i;
            }
            return s.length();
        }
        // 活动端上移一整行，尽量保持列位置；已在首行则回到全文开头
        private int lineUp(CharSequence s, int pos) {
            int ls = lineStart(s, pos);
            int col = pos - ls;                       // 当前列
            if (ls == 0) return 0;                    // 已在首行 → 文首
            int prevEnd = ls - 1;                     // 上一行末尾(那个 \n 的位置)
            int prevStart = lineStart(s, prevEnd);    // 上一行行首
            int prevLen = prevEnd - prevStart;        // 上一行长度
            return prevStart + Math.min(col, prevLen);
        }
        // 活动端下移一整行，尽量保持列位置；已在末行则到全文末尾
        private int lineDown(CharSequence s, int pos) {
            int ls = lineStart(s, pos);
            int col = pos - ls;
            int le = lineEnd(s, pos);
            if (le >= s.length()) return s.length();  // 已在末行 → 文末
            int nextStart = le + 1;                   // 下一行行首
            int nextEnd = lineEnd(s, nextStart);      // 下一行行尾
            int nextLen = nextEnd - nextStart;
            return nextStart + Math.min(col, nextLen);
        }
        // 选区目标：上一行的最左端（行首）。已在首行则返回文首。
        private int prevLineStart(CharSequence s, int pos) {
            int ls = lineStart(s, pos);
            if (ls == 0) return 0;                    // 已在首行 → 文首
            int prevEnd = ls - 1;                     // 上一行的 \n
            return lineStart(s, prevEnd);             // 上一行行首
        }
        // 选区目标：下一行的最右端（行尾）。已在末行则返回文末。
        private int nextLineEnd(CharSequence s, int pos) {
            int le = lineEnd(s, pos);
            if (le >= s.length()) return s.length();  // 已在末行 → 文末
            int nextStart = le + 1;                   // 下一行行首
            return lineEnd(s, nextStart);             // 下一行行尾
        }

        @JavascriptInterface
        public void selectAll() {
            handler.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                if (hostIsSelf) {   // 自家WebView：Ctrl+A单向投递，零读取
                    final int CT = android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_CTRL_LEFT_ON;
                    sendKey(ic, android.view.KeyEvent.KEYCODE_A, CT);
                    return;
                }
                // 不用 performContextMenuAction(selectAll)——它在微信等会弹出系统文本选择菜单
                // (复制/粘贴气泡)并抢占焦点，导致之后 IME 所有按键被宿主选择模式吞掉、键盘全卡死。
                // 改用 setSelection(0, len) 直接设选区：纯选中、不弹菜单、不夺焦点。
                android.view.inputmethod.ExtractedTextRequest rq =
                    new android.view.inputmethod.ExtractedTextRequest();
                android.view.inputmethod.ExtractedText et = ic.getExtractedText(rq, 0);
                if (et != null && et.text != null) {
                    ic.setSelection(0, et.text.length());
                } else {
                    // 拿不到全文长度时回退到系统全选
                    ic.performContextMenuAction(android.R.id.selectAll);
                }
            });
        }

        @JavascriptInterface
        public void copyText() {
            handler.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                if (hostIsSelf) {   // 自家WebView：读选区=跨进程同步等待(复制即卡死元凶)。Ctrl+C单向投递，
                    final int CT = android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_CTRL_LEFT_ON;   // Chromium原生复制→系统剪贴板→监听器捕获进私有并按隔离策略清空。
                    sendKey(ic, android.view.KeyEvent.KEYCODE_C, CT);
                    return;
                }
                CharSequence sel = ic.getSelectedText(0);
                if (sel != null && sel.length() > 0) {
                    if (privClip != null) { privClip.add(sel.toString()); lastCaptured = sel.toString(); }  // 直接进私有，不碰系统
                } else {
                    ic.performContextMenuAction(android.R.id.copy);   // 取不到选区→走系统，监听器随即搬入私有并清空
                }
            });
        }

        // 返回当前选区文本（供输入法私有剪贴历史捕获，不经系统剪贴板）
        @JavascriptInterface
        public String getSelectedText() {
            if (hostIsSelf) return "";   // 自家WebView：binder线程同步读选区必卡死——短路；私有捕获走系统剪贴监听通道
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return "";
            CharSequence sel = ic.getSelectedText(0);
            return sel == null ? "" : sel.toString();
        }

        // 触控条边界探测：dir<0 查光标是否已在文首；dir>0 查是否已在文末。
        //   返回 true=已到边界(JS 据此硬停、不再继续累积移动credit，光标绝对停在文首/文末)。
        //   实现：用 ExtractedText 的绝对偏移判定——selectionStart<=0 即文首；selectionEnd>=全文长度即文末。
        //   长文本被宿主截断时 et.text 只是片段，但 selectionStart/End 与 startOffset 仍是绝对量，
        //   故用 (startOffset + 片段长) 估全文末界；多数 App 在光标接近末尾时片段即覆盖到真正末尾，足够可靠。
        @JavascriptInterface
        public boolean cursorAtBoundary(final int dir) {
            if (hostIsSelf) return false;   // 自家WebView：读取型探测必卡；Chromium 光标到 editable 边界自停，无需锁
            try {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return false;
                // 先用 ExtractedText 的选区绝对偏移判定（WebView/记事本富文本里 getText* 常返回 null/空串，
                //   旧实现把 null 当"已到边界"→触控条一进记事本就被边界锁锁死、光标操控不了）。
                try {
                    android.view.inputmethod.ExtractedTextRequest rq =
                        new android.view.inputmethod.ExtractedTextRequest();
                    android.view.inputmethod.ExtractedText et = ic.getExtractedText(rq, 0);
                    if (et != null && et.text != null) {
                        int base = (et.startOffset > 0) ? et.startOffset : 0;
                        if (dir < 0) {
                            return base <= 0 && et.selectionStart <= 0;
                        } else {
                            // 片段未覆盖到文首(base>0)说明全文更长——只有片段内选区未到片段尾才能断言"未到文末"；
                            // 到达片段尾且片段从 0 开始 → 真文末。
                            int relEnd = et.selectionEnd - base;
                            if (relEnd >= 0 && relEnd < et.text.length()) return false;
                            if (base <= 0 && relEnd >= et.text.length()) return true;
                        }
                    }
                } catch (Throwable ig) {}
                if (dir < 0) {
                    CharSequence before = ic.getTextBeforeCursor(1, 0);
                    // null=宿主不支持/未知 → 不锁（宁可多走一步，绝不锁死光标）
                    return before != null && before.length() == 0;
                } else {
                    CharSequence after = ic.getTextAfterCursor(1, 0);
                    return after != null && after.length() == 0;
                }
            } catch (Throwable t) { return false; }
        }

        // 防截屏开关：true=开启防截屏(默认)，false=临时允许截屏(便于自己调试截图)
        @JavascriptInterface
        public void setSecure(final boolean on) {
            getSharedPreferences("wubi_ime", MODE_PRIVATE)
                .edit().putBoolean("flagSecure", on).apply();
            handler.post(() -> {
                android.view.Window win = getWindow().getWindow();
                if (win != null) applySecureFlag(win);
            });
        }

        // 防截屏当前状态(供 JS 开机同步 UI：胶囊状态点 + 触控条光环)
        @JavascriptInterface
        public boolean isSecure() {
            return getSharedPreferences("wubi_ime", MODE_PRIVATE).getBoolean("flagSecure", false);
        }

        // 剪贴隔离开关：开(默认)=复制只进私有剪贴板、随即清空系统剪贴板；关=两边都留，跨App粘贴不受影响
        @JavascriptInterface
        public void setClipIsolation(final boolean on) {
            getSharedPreferences("wubi_ime", MODE_PRIVATE)
                .edit().putBoolean("clipIsolation", on).apply();
        }

        @JavascriptInterface
        public boolean isClipIsolation() {
            return getSharedPreferences("wubi_ime", MODE_PRIVATE).getBoolean("clipIsolation", true);
        }

        // 跳转系统"无障碍/辅助功能"设置，方便用户自行关闭流氓 App 的后门权限
        @JavascriptInterface
        public void openAccessibilitySettings() {
            handler.post(() -> {
                try {
                    android.content.Intent it = new android.content.Intent(
                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Exception e) {}
            });
        }

        @JavascriptInterface
        public void cutText() {
            handler.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                if (hostIsSelf) {
                    final int CT = android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_CTRL_LEFT_ON;
                    sendKey(ic, android.view.KeyEvent.KEYCODE_X, CT);
                    return;
                }
                CharSequence sel = ic.getSelectedText(0);
                if (sel != null && sel.length() > 0) {
                    if (privClip != null) { privClip.add(sel.toString()); lastCaptured = sel.toString(); }
                    ic.commitText("", 1);                             // 删除选区=剪切
                } else {
                    ic.performContextMenuAction(android.R.id.cut);
                }
            });
        }

        @JavascriptInterface
        public void pasteText() {
            handler.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                // 1) 系统剪贴板有内容 = “没用本输入法时复制的”(我们没清它) → 按系统的正常粘
                try {
                    if (sysClip != null) {
                        android.content.ClipData clip = sysClip.getPrimaryClip();
                        if (clip != null && clip.getItemCount() > 0) {
                            CharSequence cs = clip.getItemAt(0).coerceToText(WubiWebViewIME.this);
                            if (cs != null && cs.length() > 0) { ic.commitText(cs.toString(), 1); return; }
                        }
                    }
                } catch (Throwable t) {}
                // 2) 系统剪贴板空(本输入法复制的已被收进私有并清空) → 粘私有最新一条
                java.util.List<String> items = (privClip != null) ? privClip.getAll() : null;
                if (items != null && !items.isEmpty()) ic.commitText(items.get(0), 1);
            });
        }

        @JavascriptInterface
        public void undoText() {
            handler.post(() -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return;
                if (hostIsSelf) {
                    final int CT = android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_CTRL_LEFT_ON;
                    sendKey(ic, android.view.KeyEvent.KEYCODE_Z, CT);
                    return;
                }
                ic.performContextMenuAction(android.R.id.undo);
            });
        }

        @JavascriptInterface
        public void playClick() {
            handler.post(() -> {
                android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.playSoundEffect(
                    android.media.AudioManager.FX_KEYPRESS_STANDARD, -1f);
            });
        }

        // 键盘内 overlay 加载加密应用：返回解密后的 HTML（白名单校验，非法名返回空）
        @JavascriptInterface
        public String loadAppHtml(final String name) {
            if (name == null) return "";
            if (!"note".equals(name) && !"pwgen".equals(name) && !"browser".equals(name) && !"audio".equals(name)) return "";
            String h = com.wubi.ime.ime.AppActivity.decryptAsset(WubiWebViewIME.this, name);
            return h == null ? "" : h;
        }

        @JavascriptInterface
        public void hideKeyboard() {
            handler.post(() -> requestHideSelf(0));
        }

        @JavascriptInterface
        public void log(final String msg) {
            android.util.Log.d("WubiIME_JS", msg);
        }
    }

    // ====== 内嵌应用（密码生成器等）专用桥：数据加密存应用私有目录，拒绝外部窥探 ======
    //   安全模型：
    //     · 存储位置 getFilesDir()/{app}.enc —— 应用私有目录，其他 App 无读权限(Android 沙箱)。
    //     · 内容用 Android Keystore 生成的 AES-256-GCM 密钥加密 —— 密钥不可导出，即便文件被 root 提取也是密文。
    //     · 明文(密码历史)只在内存/加解密瞬间存在，落盘必加密 —— 杜绝病毒/取证工具扫到明文。
    class PwBridge {
        private static final String KS_ALIAS = "ankey_appdata_key";
        private static final String KS_NAME  = "AndroidKeyStore";

        // 取/建 Keystore 中的 AES 密钥（不可导出）
        private javax.crypto.SecretKey getKey() throws Exception {
            java.security.KeyStore ks = java.security.KeyStore.getInstance(KS_NAME);
            ks.load(null);
            if (ks.containsAlias(KS_ALIAS)) {
                return ((java.security.KeyStore.SecretKeyEntry)
                        ks.getEntry(KS_ALIAS, null)).getSecretKey();
            }
            javax.crypto.KeyGenerator kg = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, KS_NAME);
            kg.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                    KS_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                  | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                  .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                  .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                  .setKeySize(256)
                  .build());
            return kg.generateKey();
        }

        private java.io.File dataFile(String app) {
            String safe = app.replaceAll("[^a-zA-Z0-9_]", "");
            return new java.io.File(getFilesDir(), safe + ".enc");
        }

        // 保存：明文 JSON → AES-GCM 加密 → [12B IV | 密文]。
        // 断电安全：先写 .tmp 并 fsync 落盘，再原子 rename 覆盖正本；替换前先留一份 .bak。
        // → 任何时刻断电，正本要么是旧的完整版、要么是新的完整版，绝不会半截损坏；最坏退到 .bak。

        // ===== #15 大容量分块数据桥：流式 AES-GCM，跨桥每次≤256K字符 =====
        //   文件格式与 saveAppData 完全一致(IV+GCM流，tag附尾)——新旧互通零迁移。
        //   Java 端内存 O(块)；JS 端按块传拼，根治"超大字符串单次跨桥失败"。
        private java.io.OutputStream _dsOut; private java.io.File _dsTmp, _dsTarget;
        private java.io.Reader _dlIn;

        @JavascriptInterface
        public boolean dataSaveBegin(final String app) {
            try {
                dataSaveAbort();
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, getKey());
                byte[] iv = c.getIV();
                _dsTarget = dataFile(app);
                _dsTmp = new java.io.File(_dsTarget.getParentFile(), _dsTarget.getName() + ".tmp");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(_dsTmp);
                fos.write(iv);
                _dsOut = new javax.crypto.CipherOutputStream(fos, c);
                return true;
            } catch (Throwable t) { dataSaveAbort(); return false; }
        }
        @JavascriptInterface
        public boolean dataSaveChunk(final String s) {
            try { if (_dsOut == null) return false; _dsOut.write(s.getBytes("UTF-8")); return true; }
            catch (Throwable t) { dataSaveAbort(); return false; }
        }
        @JavascriptInterface
        public String dataSaveEnd() {
            try {
                if (_dsOut == null) return "ERR:no-session";
                _dsOut.close(); _dsOut = null;   // close 冲刷 GCM tag
                java.io.File bak = new java.io.File(_dsTarget.getParentFile(), _dsTarget.getName() + ".bak");
                if (_dsTarget.exists()) { try { copyFile(_dsTarget, bak); } catch (Throwable ig) {} }
                if (!_dsTmp.renameTo(_dsTarget)) { copyFile(_dsTmp, _dsTarget); _dsTmp.delete(); }
                _dsTmp = null; _dsTarget = null;
                return "OK";
            } catch (Throwable t) { dataSaveAbort(); return "ERR:" + t.getMessage(); }
        }
        @JavascriptInterface
        public void dataSaveAbort() {
            try { if (_dsOut != null) _dsOut.close(); } catch (Throwable t) {}
            _dsOut = null;
            try { if (_dsTmp != null && _dsTmp.exists()) _dsTmp.delete(); } catch (Throwable t) {}
            _dsTmp = null; _dsTarget = null;
        }
        @JavascriptInterface
        public String dataLoadBegin(final String app, final boolean useBak) {
            try {
                dataLoadAbort();
                java.io.File f = dataFile(app);
                if (useBak) f = new java.io.File(f.getParentFile(), f.getName() + ".bak");
                if (!f.exists() || f.length() <= 12) return "MISS";
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                byte[] iv = new byte[12]; int off = 0, r;
                while (off < 12 && (r = fis.read(iv, off, 12 - off)) > 0) off += r;
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                c.init(javax.crypto.Cipher.DECRYPT_MODE, getKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
                _dlIn = new java.io.InputStreamReader(new javax.crypto.CipherInputStream(fis, c), "UTF-8");
                return "OK";
            } catch (Throwable t) { dataLoadAbort(); return "ERR:" + t.getMessage(); }
        }
        @JavascriptInterface
        public String dataLoadNext() {
            try {
                if (_dlIn == null) return "ERR:no-session";
                char[] buf = new char[262144]; int n = 0, r;
                while (n < buf.length && (r = _dlIn.read(buf, n, buf.length - n)) > 0) n += r;
                if (n <= 0) { dataLoadAbort(); return ""; }   // 正常结束(GCM tag 已校验通过)
                return "D" + new String(buf, 0, n);           // 前缀 D 区分数据与控制码
            } catch (Throwable t) { dataLoadAbort(); return "ERR:" + t.getMessage(); }   // tag 校验失败在此暴露
        }
        @JavascriptInterface
        public void dataLoadAbort() {
            try { if (_dlIn != null) _dlIn.close(); } catch (Throwable t) {}
            _dlIn = null;
        }

        @JavascriptInterface
        public void saveAppData(final String app, final String json) {
            try {
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, getKey());
                byte[] iv = c.getIV();
                byte[] ct = c.doFinal(json.getBytes("UTF-8"));
                java.io.File target = dataFile(app);
                java.io.File tmp = new java.io.File(target.getParentFile(), target.getName() + ".tmp");
                java.io.File bak = new java.io.File(target.getParentFile(), target.getName() + ".bak");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
                fos.write(iv); fos.write(ct);
                fos.flush(); fos.getFD().sync();          // 强制落到物理介质
                fos.close();
                // 先把上一份完好正本复制成 .bak(正本此刻仍完整，复制安全)
                if (target.exists()) { try { copyFile(target, bak); } catch (Throwable ig) {} }
                // 原子替换：rename 是原子操作，正本不会出现半截状态
                if (!tmp.renameTo(target)) {
                    copyFile(tmp, target);                // 极少数 rename 失败 → 用已 fsync 的 tmp 覆盖
                    tmp.delete();
                }
            } catch (Throwable t) {
                android.util.Log.e("WubiIME_PW", "save failed", t);
            }
        }

        private void copyFile(java.io.File src, java.io.File dst) throws Exception {
            java.io.FileInputStream in = new java.io.FileInputStream(src);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush(); out.getFD().sync(); out.close(); in.close();
        }

        // 读取：先读正本；若正本缺失/损坏(断电写坏)，自动回退上一份 .bak。
        @JavascriptInterface
        public String loadAppData(final String app) {
            java.io.File main = dataFile(app);
            String r = tryLoad(main);
            if (r != null) return r;
            java.io.File bak = new java.io.File(main.getParentFile(), main.getName() + ".bak");
            String rb = tryLoad(bak);
            return rb != null ? rb : "";
        }

        // 解密单个文件；任何失败(不存在/截断/GCM 校验不过)都返回 null，交由调用方回退。
        private String tryLoad(java.io.File f) {
            try {
                if (f == null || !f.exists()) return null;
                byte[] all = new byte[(int) f.length()];
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                int off = 0, r;
                while (off < all.length && (r = fis.read(all, off, all.length - off)) > 0) off += r;
                fis.close();
                if (all.length <= 12) return null;
                byte[] iv = java.util.Arrays.copyOfRange(all, 0, 12);
                byte[] ct = java.util.Arrays.copyOfRange(all, 12, all.length);
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                c.init(javax.crypto.Cipher.DECRYPT_MODE, getKey(),
                        new javax.crypto.spec.GCMParameterSpec(128, iv));
                return new String(c.doFinal(ct), "UTF-8");
            } catch (Throwable t) { return null; }
        }

        // 复制(密码/笔记内容)：只进输入法私有剪贴板，绝不写系统剪贴板。
        // 用我的键盘在目标输入框按“粘贴”即可贴入；其它输入法/App 无法获取。
        @JavascriptInterface
        public void copyToClipboard(final String text) {
            handler.post(() -> {
                if (privClip != null && text != null && text.length() > 0) {
                    privClip.add(text);
                    lastCaptured = text;   // 防止全局监听器误判为外部新内容再处理一遍
                }
            });
        }

        // 读取私有剪贴板最新一条（供笔记本等内嵌应用粘贴）
        @JavascriptInterface
        public String getClipboardLatest() {
            try { if (privClip != null) { java.util.List<String> a = privClip.getAll(); if (a != null && !a.isEmpty()) return a.get(0); } } catch (Throwable t) {}
            return "";
        }

        // #1 读取打包资产(Base64)：钢琴真采样专用，白名单严格限定 piano/ 下的音符文件
        @JavascriptInterface
        public String readAssetB64(final String name) {
            if (name == null || !name.matches("piano/[A-G]b?[0-8]\\.mp3")) return "";
            try (java.io.InputStream is = getAssets().open(name)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
                return android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
            } catch (Throwable t) { return ""; }
        }

        // #5 删除私有剪贴板单条(剪贴条左滑删除)
        @JavascriptInterface
        public void removeClipboardText(final String text) {
            handler.post(() -> {
                try {
                    if (privClip == null || text == null) return;
                    java.util.List<String> a = privClip.getAll();
                    if (a == null) return;
                    int idx = a.indexOf(text);
                    if (idx >= 0) privClip.delete(idx);
                } catch (Throwable t) {}
            });
        }

        // #4 清空私有剪贴板（含安全抹除）：先写3轮等长随机噪声占据持久槽位，再彻底清空——
        //   SharedPreferences 旧值被多次覆盖后不可从存储介质回收复原。JS 侧撤回窗到期时调用。
        @JavascriptInterface
        public void clearClipboardAll() {
            handler.post(() -> {
                try {
                    if (privClip == null) return;
                    java.util.List<String> cur = privClip.getAll();
                    int n = (cur == null) ? 0 : cur.size();
                    java.util.Random rnd = new java.util.Random();
                    for (int round = 0; round < 3; round++) {
                        privClip.clearAll();
                        for (int i = 0; i < Math.max(1, n); i++) {
                            StringBuilder sb = new StringBuilder();
                            for (int k = 0; k < 64; k++) sb.append((char)('a' + rnd.nextInt(26)));
                            privClip.add(sb.toString());
                        }
                    }
                    privClip.clearAll();
                } catch (Throwable t) {}
            });
        }

        // 读取私有剪贴板全部条目，JSON 数组字符串（最新在前）
        @JavascriptInterface
        public String getClipboardAll() {
            try {
                if (privClip == null) return "[]";
                java.util.List<String> a = privClip.getAll();
                org.json.JSONArray arr = new org.json.JSONArray();
                if (a != null) for (String s : a) arr.put(s);
                return arr.toString();
            } catch (Throwable t) { return "[]"; }
        }

        // ===== 导出(键盘内嵌 overlay 模式)：分块传输 + MediaStore 写系统下载目录 =====
        //   与 AppActivity 同一套 JS 协议(saveBegin/saveChunk/saveEnd)。输入法服务无 Activity
        //   上下文、不能弹 SAF 对话框，saveEnd 直接落盘并同步返回真实结果字符串：
        //   "OK:Download/文件名" / "ERR:原因"。分块≤512KB 杜绝超大字符串单次跨桥失败。
        private java.io.ByteArrayOutputStream _saveBuf;
        private String _saveName, _saveMime;

        @JavascriptInterface
        public void saveBegin(final String name, final String mime) {
            _saveBuf = new java.io.ByteArrayOutputStream();
            _saveName = (name == null ? "export.bin" : name.replaceAll("[\\\\/:*?\"<>|]", "_"));
            _saveMime = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        }

        @JavascriptInterface
        public boolean saveChunk(final String b64) {
            try {
                if (_saveBuf == null || b64 == null) return false;
                _saveBuf.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT));
                return true;
            } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public String saveEnd() {
            try {
                if (_saveBuf == null || _saveName == null) return "ERR:no-data";
                byte[] data = _saveBuf.toByteArray(); _saveBuf = null;
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, _saveName);
                    cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, _saveMime);
                    cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
                    android.net.Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "ERR:系统拒绝创建文件";
                    java.io.OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(data); os.flush(); os.close();
                    cv.clear();
                    cv.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, cv, null, null);
                    return "OK:Download/" + _saveName;
                } else {
                    java.io.File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    if (!dir.exists()) dir.mkdirs();
                    java.io.File f = new java.io.File(dir, _saveName);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    fos.write(data); fos.flush(); fos.getFD().sync(); fos.close();
                    return "OK:" + f.getAbsolutePath();
                }
            } catch (Throwable t) {
                android.util.Log.e("WubiIME_PW", "saveEnd failed", t);
                String m = t.getClass().getSimpleName();
                return "ERR:" + m;
            }
        }

        // 关闭应用、返回键盘界面（由 iframe 内退出按钮调用 → 通知 JS 收起 overlay）
        @JavascriptInterface
        public void closeApp() {
            if (webView != null) {
                handler.post(() -> webView.evaluateJavascript(
                    "if(typeof closeApp==='function')closeApp();", null));
            }
        }

        // 独立应用「真·全屏」：打开记事本/密码生成器时把输入法视图高度撑到(整屏-状态栏)，
        //   让应用充分铺满系统屏幕，而不再局限在键盘那一小块面板里(原先像并列小窗)。
        //   关闭时恢复 WRAP_CONTENT，键盘回到正常高度。纯高度变更，不另起 Activity，
        //   故应用仍在输入法内、私有剪贴板「复制→回原输入框粘贴」链路不受影响。
        @JavascriptInterface
        public void setAppFullscreen(final boolean on) {
            handler.post(() -> {
                if (webView == null) return;
                try {
                    android.view.ViewGroup.LayoutParams lp = webView.getLayoutParams();
                    if (lp == null) return;
                    if (on) {
                        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE))
                            .getDefaultDisplay().getRealMetrics(dm);
                        int sb = 0;
                        int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
                        if (rid > 0) sb = getResources().getDimensionPixelSize(rid);
                        int h = dm.heightPixels - sb;
                        if (h < 400) h = dm.heightPixels;           // 异常兜底
                        lp.height = h;
                    } else {
                        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                    }
                    webView.setLayoutParams(lp);
                    webView.requestLayout();
                    webView.invalidate();
                } catch (Throwable t) {}
            });
        }

        // 供 JS 兜底读取可用屏高(CSS px 由 JS 用 devicePixelRatio 换算)：整屏物理像素-状态栏
        @JavascriptInterface
        public int getUsableHeightPx() {
            try {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                ((android.view.WindowManager) getSystemService(WINDOW_SERVICE))
                    .getDefaultDisplay().getRealMetrics(dm);
                int sb = 0;
                int rid = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (rid > 0) sb = getResources().getDimensionPixelSize(rid);
                return dm.heightPixels - sb;
            } catch (Throwable t) { return 0; }
        }
    }

    @Override
    public void onDestroy() {
        if (sysClip != null) {
            try { sysClip.removePrimaryClipChangedListener(clipListener); } catch (Throwable t) {}
        }
        if (webView != null) {
            try {
                android.view.ViewParent p = webView.getParent();
                if (p instanceof android.view.ViewGroup) ((android.view.ViewGroup) p).removeView(webView);
            } catch (Throwable t) {}
            try { webView.destroy(); } catch (Throwable t) {}
            webView = null;
        }
        super.onDestroy();
    }
}
