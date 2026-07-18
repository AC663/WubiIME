package com.wubi.ime.ime;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;

/**
 * 独立应用全屏窗口（密码生成器 / 笔记本）。
 *
 * 为什么另起 Activity 而不是键盘内 overlay：
 *   输入法窗口是系统受限窗口（高度受宿主/系统裁决，部分 ROM 禁止 IME 自行撑满全屏），
 *   overlay 方案在真机上被弹出阻止、无法全屏。AppActivity 是普通全屏窗口，
 *   像独立 App 一样流畅弹出；笔记本内的输入框聚焦时系统正常唤起本输入法打字。
 *
 * 数据完全同源：
 *   · AndroidPW 桥与输入法内 PwBridge 同名同方法——apps/*.html 无需任何改动。
 *   · 加密存储用同一 Keystore 别名(ankey_appdata_key)、同一 {app}.enc 文件格式(12B IV|密文)
 *     ——两个窗口读写的是同一份密文数据。
 *   · 私有剪贴板用同一 SharedPreferences(wubi_clipboard)——此处复制，回键盘即可粘贴
 *     (输入法侧在 onStartInputView 重新加载私有剪贴板，跨窗口刷新)。
 */
public class AppActivity extends Activity {

    private WebView webView;
    private WebView netView;      // #浏览器 真网页 WebView(全功能网络栈)
    private FrameLayout rootLayout;
    private String browserBarBg = null;   // #1 浏览器主题跟随的栏/底色
    private boolean isBrowser;
    private PwBridge bridge;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    // ===== 加密资产运行时解密（构建期 CI 用同一派生密钥加密） =====
    //   密钥=SHA256("AnKey.Asset.v1|" + 两段异或量的十六进制)——常量三段拆分+R8混淆提高静态提取门槛。
    //   坦白边界：APK 内派生逻辑可被深度逆向还原(非绝对防线)；其意义是杜绝"从目录路径直接打开 html"
    //   这类零门槛访问。用户数据的绝对防线在 Keystore(密钥不出安全硬件)。
    static byte[] assetKey() throws Exception {
        long a = 0x51E0C7A39B24D68L, b = 0x7D3A9F1C64E8B05L, c = 0x2C8B5E7F19A3D46L;
        String seed = "AnKey.Asset.v1|" + Long.toHexString(a ^ b) + Long.toHexString(b ^ c);
        return java.security.MessageDigest.getInstance("SHA-256").digest(seed.getBytes("UTF-8"));
    }
    static String decryptAsset(android.content.Context ctx, String name) {
        try {
            java.io.InputStream is = ctx.getAssets().open("enc/" + name + ".bin");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            byte[] all = bo.toByteArray();
            if (all.length < 13) return null;
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, 12);
            byte[] ct = java.util.Arrays.copyOfRange(all, 12, all.length);
            javax.crypto.Cipher cp = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cp.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(assetKey(), "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            return new String(cp.doFinal(ct), "UTF-8");
        } catch (Throwable t) { return null; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 去掉默认 ActionBar（黑色"拾砚"大标题栏太占高度）：全屏留给应用内容，
        //   返回/关闭交由页面内的"返回键盘"按钮与系统返回手势。
        try { requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); } catch (Throwable t) {}
        try { if (getActionBar() != null) getActionBar().hide(); } catch (Throwable t) {}

        // #4 防截屏策略调整：笔记本(note)页内【放行截屏/录屏】——用户明确需要页内截屏留存；
        //   密码生成器等其它应用窗口仍随输入法防截屏开关(开=禁截)。
        String __app0 = getIntent() != null ? getIntent().getStringExtra("app") : null;
        boolean secure = !"note".equals(__app0)
                && getSharedPreferences("wubi_ime", MODE_PRIVATE).getBoolean("flagSecure", false);
        if (secure) {
            getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                                 android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }

        String app = getIntent() != null ? getIntent().getStringExtra("app") : null;
        String safe = app == null ? "" : app.replaceAll("[^a-zA-Z0-9_]", "");
        boolean __isView = getIntent() != null && android.content.Intent.ACTION_VIEW.equals(getIntent().getAction()) && getIntent().getData() != null;
        if (safe.isEmpty() && !__isView) { finish(); return; }
        if (safe.isEmpty()) safe = "note";   // 查看器模式：借note壳的中性主题，实际内容由下方 ACTION_VIEW 分支直载

        // 状态栏与应用底色一致，图标深色（应用页面均为浅色底）
        try {
            boolean darkApp = "browser".equals(safe);   // #7 浏览器=暗底应用：状态栏随色、浅色图标
            String barBg = darkApp ? "#07080B" : "#F6F3EE";
            boolean lightIcons = darkApp;
            if (darkApp) {
                // #1 主题跟随：读键盘 kbprefs.kbTheme，状态栏/导航栏/UI底色与驾驶舱同色板（浅主题=浅舱+深图标）
                try {
                    String prefs = new PwBridge().loadAppData("kbprefs");
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"kbTheme\"\\s*:\\s*\"(t-[a-z]+)\"").matcher(prefs == null ? "" : prefs);
                    if (m.find()) {
                        String t = m.group(1);
                        String c = "t-white".equals(t) ? "#eae5da" : "t-orange".equals(t) ? "#ebd5c5"
                                 : "t-blue".equals(t) ? "#d8e8f5" : "t-purple".equals(t) ? "#e4d0f5"
                                 : "t-red".equals(t) ? "#f0d0d0" : "t-green".equals(t) ? "#d0ecda"
                                 : "t-pink".equals(t) ? "#f8d8ee" : "t-brown".equals(t) ? "#e8d8c4"
                                 : "t-cyan".equals(t) ? "#c8ecf5" : "t-yellow".equals(t) ? "#f8e6b0" : null;
                        if (c != null) { barBg = c; lightIcons = false; }   // t-dark 保持默认暗舱
                    }
                } catch (Throwable ig) {}
            }
            browserBarBg = barBg;
            getWindow().setStatusBarColor(Color.parseColor(barBg));
            getWindow().setNavigationBarColor(Color.parseColor(barBg));
            getWindow().getDecorView().setSystemUiVisibility(
                lightIcons ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable t) {}

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setBackgroundColor(Color.parseColor("#F6F3EE"));
        bridge = new PwBridge();
        webView.addJavascriptInterface(bridge, "AndroidPW");
        // 渲染进程崩溃：直接关窗口，绝不留白屏僵尸窗
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                try { finish(); } catch (Throwable t) {}
                return true;
            }
        });
        // 缺陷修复：apps 视图此前没有 WebChromeClient——<input type=file> 点击无响应(音频剪辑选文件失灵的根因)。
        //   复用与 netView 相同的 fileChooserCb 系统选择器流程。
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView wv, android.webkit.ValueCallback<android.net.Uri[]> cb, FileChooserParams params) {
                if (fileChooserCb != null) { try { fileChooserCb.onReceiveValue(null); } catch (Throwable t) {} }
                fileChooserCb = cb;
                try {
                    android.content.Intent it = params.createIntent();
                    startActivityForResult(it, REQ_FILE_CHOOSER);
                    return true;
                } catch (Throwable t) { fileChooserCb = null; return false; }
            }
        });
        // 缺陷#6：系统"打开方式"进入的 md/html/txt 查看器——精准原样呈现，免第三方
        android.content.Intent __vi = getIntent();
        if (__vi != null && android.content.Intent.ACTION_VIEW.equals(__vi.getAction()) && __vi.getData() != null) {
            try {
                android.net.Uri u = __vi.getData();
                java.io.InputStream in = getContentResolver().openInputStream(u);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] bf = new byte[8192]; int n2; long tot = 0;
                while ((n2 = in.read(bf)) > 0) { bo.write(bf, 0, n2); tot += n2; if (tot > 8L*1024*1024) break; }
                in.close();
                String body = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                String nm = u.getLastPathSegment(); if (nm == null) nm = "文档";
                String mime = getContentResolver().getType(u); if (mime == null) mime = "";
                boolean isHtml = mime.contains("html") || nm.toLowerCase().endsWith(".html") || nm.toLowerCase().endsWith(".htm");
                if (isHtml) {
                    // html：原模原样直载（本地文档，无远程上下文）
                    webView.loadDataWithBaseURL("https://ankey.app/viewer/", body, "text/html", "utf-8", null);
                } else {
                    // md/txt：等宽预格式化视图，精准复现内容（不做markdown渲染，保证零失真）
                    String esc = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                    String page = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                        + "<style>body{margin:0;background:#faf9f5;color:#222;font:14px/1.75 -apple-system,'Noto Sans SC',sans-serif;padding:16px calc(env(safe-area-inset-right,0px) + 16px) 40px calc(env(safe-area-inset-left,0px) + 16px)}"
                        + "pre{white-space:pre-wrap;word-break:break-word;margin:0}"
                        + "h1{font-size:15px;color:#666;font-weight:600;border-bottom:1px solid #e5e0d5;padding:calc(env(safe-area-inset-top,0px) + 10px) 0 10px;margin:0 0 12px}</style></head><body>"
                        + "<h1>" + nm.replace("<","&lt;") + "</h1><pre>" + esc + "</pre></body></html>";
                    webView.loadDataWithBaseURL("https://ankey.app/viewer/", page, "text/html", "utf-8", null);
                }
                setContentView(webView);   // #3 黑屏闪退根因：此分支早退前从未挂视图——Activity 无内容窗口即黑屏，部分 ROM 再触异常即闪退
                return;   // 查看器模式：不进入常规 app 流程
            } catch (Throwable t) {}
        }
        isBrowser = "browser".equals(safe);
        if (isBrowser) {
            // #浏览器 真 WebView 承载网页：底层 netView(全功能网络栈,正常缓存) + 顶层 webView(PURE UI,透明背景)。
            //   打开网页时 JS 通过桥令 netView 显形并加载；回到起始页/标签/设置时令其隐藏，露出 UI。
            netView = new WebView(this);
            WebSettings ns = netView.getSettings();
            ns.setJavaScriptEnabled(true);
            ns.setDomStorageEnabled(true);
            ns.setLoadWithOverviewMode(true);
            ns.setUseWideViewPort(true);
            ns.setSupportZoom(true);
            ns.setBuiltInZoomControls(true);
            ns.setDisplayZoomControls(false);
            ns.setCacheMode(WebSettings.LOAD_DEFAULT);       // 正常缓存(修复 ERR_CACHE_MISS 根因)
            ns.setMediaPlaybackRequiresUserGesture(true);
            ns.setUserAgentString(ns.getUserAgentString().replace("; wv", ""));
            try { String __ua = ns.getUserAgentString(); if (!__ua.contains("Mobile")) ns.setUserAgentString(__ua + " Mobile"); } catch (Throwable t) {}   // 去 WebView 标记,减少站点拒绝
            netView.setBackgroundColor(Color.parseColor("#FFFFFF"));
            netView.setVisibility(View.GONE);
            netView.setWebChromeClient(new WebChromeClient() {
                @Override public void onProgressChanged(WebView v, int p) {
                    if (webView != null) webView.evaluateJavascript(
                        "window.__onNetProgress&&window.__onNetProgress(" + p + ")", null);
                }
                @Override public void onReceivedTitle(WebView v, String t) {
                    if (webView != null && t != null) webView.evaluateJavascript(
                        "window.__onNetTitle&&window.__onNetTitle(" + jsStr(t) + ")", null);
                }
                @Override public boolean onShowFileChooser(WebView wv, android.webkit.ValueCallback<android.net.Uri[]> cb, FileChooserParams params) {
                    if (fileChooserCb != null) { try { fileChooserCb.onReceiveValue(null); } catch (Throwable t) {} }
                    fileChooserCb = cb;
                    try {
                        android.content.Intent it = (params != null) ? params.createIntent() : new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                        it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                        if (it.getType() == null) it.setType("*/*");
                        startActivityForResult(android.content.Intent.createChooser(it, "选择文件"), REQ_FILE_CHOOSER);
                        return true;
                    } catch (Throwable t) { fileChooserCb = null; return false; }
                }
                @Override public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                    handler.post(() -> { try { request.deny(); } catch (Throwable t) {} });   // 隐私:摄像头/麦克风一律拒绝
                }
                @Override public void onGeolocationPermissionsShowPrompt(String origin, android.webkit.GeolocationPermissions.Callback cb) {
                    try { cb.invoke(origin, false, false); } catch (Throwable t) {}   // 隐私:定位一律拒绝
                }
                @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                    if (fsCustomView != null) { try { callback.onCustomViewHidden(); } catch (Throwable t) {} return; }
                    fsCustomView = view; fsCallback = callback;
                    try {
                        if (rootLayout != null) rootLayout.addView(fsCustomView, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                    } catch (Throwable t) {}
                }
                @Override public void onHideCustomView() {
                    try { if (fsCustomView != null && rootLayout != null) rootLayout.removeView(fsCustomView); } catch (Throwable t) {}
                    fsCustomView = null;
                    if (fsCallback != null) { try { fsCallback.onCustomViewHidden(); } catch (Throwable t) {} fsCallback = null; }
                }
            });
            netView.setWebViewClient(new android.webkit.WebViewClient() {
                @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                    try { if (netView != null) { netView.setVisibility(View.GONE); } } catch (Throwable t) {}
                    if (webView != null) webView.evaluateJavascript("window.__onNetGone&&window.__onNetGone()", null);
                    return true;
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                    String u = req.getUrl() != null ? req.getUrl().toString() : "";
                    if (u.startsWith("http://") || u.startsWith("https://")) return false;   // 网页内跳转放行
                    return true;   // 非 http(intent/tel/mailto 等)拦截,交回 UI 处理
                }
                @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                    try {
                        android.net.Uri u = req != null ? req.getUrl() : null;
                        if (u != null && isAdHost(u.getHost())) {
                            final int n = adBlockedCount.incrementAndGet();
                            final String bh = u.getHost();
                            if (webView != null) handler.post(() -> {
                                try { webView.evaluateJavascript("window.__onAdBlocked&&window.__onAdBlocked(" + n + "," + jsStr(bh) + ")", null); } catch (Throwable t) {}
                            });
                            return new android.webkit.WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
                        }
                    } catch (Throwable t) {}
                    return null;   // 非黑名单→放行
                }
                @Override public void onReceivedSslError(WebView v, android.webkit.SslErrorHandler h, android.net.http.SslError err) {
                    // 缺陷修复：绝不静默 proceed 不变，但也不再立即 cancel——挂起交用户决策。
                    //   国内部分 ROM WebView 证书链补全能力弱，hao123 等主流站会被误判"证书无效"。
                    //   专业浏览器范式：警告页 + 用户明示"仍要访问"才放行(仅本次)。
                    String u = (err != null && err.getUrl() != null) ? err.getUrl() : "";
                    if (pendingSslHandler != null) { try { pendingSslHandler.cancel(); } catch (Throwable t) {} }
                    pendingSslHandler = h;
                    if (webView != null) webView.evaluateJavascript("window.__onNetSslError&&window.__onNetSslError(" + jsStr(u) + ")", null);
                }
                @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                    adBlockedCount.set(0);   // 每次导航重置真实拦截计数
                    if (webView != null) webView.evaluateJavascript(
                        "window.__onNetStart&&window.__onNetStart(" + jsStr(url) + ")," +
                        "window.__onAdBlocked&&window.__onAdBlocked(0)", null);
                }
                @Override public void onPageFinished(WebView v, String url) {
                    if (webView != null) webView.evaluateJavascript(
                        "window.__onNetFinish&&window.__onNetFinish(" + jsStr(url) + "," + jsStr(v.getTitle()==null?"":v.getTitle()) + "," + v.canGoBack() + "," + v.canGoForward() + ")", null);
                }
                @Override public void onReceivedError(WebView v, WebResourceRequest req, android.webkit.WebResourceError err) {
                    if (req != null && req.isForMainFrame() && webView != null) {
                        String u = req.getUrl()!=null?req.getUrl().toString():"";
                        webView.evaluateJavascript("window.__onNetError&&window.__onNetError(" + jsStr(u) + ")", null);
                    }
                }
            });
            netView.setDownloadListener((url, ua, cd, mime, len) -> handler.post(() -> {
                // 缺陷#6：真实下载——http(s) 走系统 DownloadManager 落公共下载目录；blob: 回传 UI 走 saveBegin 桥
                try {
                    if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                        android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                        android.app.DownloadManager.Request rq = new android.app.DownloadManager.Request(android.net.Uri.parse(url));
                        String fn = android.webkit.URLUtil.guessFileName(url, cd, mime);
                        // #1 apk变.bin根因：服务器给 octet-stream 且无 Content-Disposition 时 guessFileName 兜底 .bin——
                        //   URL 路径自带真实后缀则以路径为准；.apk 强制安装包 MIME，下载完成后可直接点按安装。
                        String dlMime = mime;
                        try {
                            String seg = android.net.Uri.parse(url).getLastPathSegment();
                            if (seg != null && seg.contains(".")) {
                                String ext = seg.substring(seg.lastIndexOf('.') + 1).toLowerCase();
                                if (ext.matches("[a-z0-9]{2,5}")) {
                                    if (fn == null || fn.toLowerCase().endsWith(".bin")) fn = seg;
                                    if ("apk".equals(ext)) dlMime = "application/vnd.android.package-archive";
                                    else {
                                        String byExt = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                                        if (byExt != null && (dlMime == null || dlMime.isEmpty() || "application/octet-stream".equals(dlMime))) dlMime = byExt;
                                    }
                                }
                            }
                        } catch (Throwable ig) {}
                        rq.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fn);
                        rq.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        rq.setMimeType(dlMime);
                        try { rq.addRequestHeader("User-Agent", ua); } catch (Throwable ig) {}
                        try { rq.addRequestHeader("Cookie", android.webkit.CookieManager.getInstance().getCookie(url)); } catch (Throwable ig) {}
                        dm.enqueue(rq);
                        android.widget.Toast.makeText(AppActivity.this, "已开始下载：" + fn, android.widget.Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    try { android.widget.Toast.makeText(AppActivity.this, "下载失败", android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ig) {}
                }
                if (webView != null) webView.evaluateJavascript("window.__onNetDownload&&window.__onNetDownload(" + jsStr(url) + ")", null);
            }));   // #浏览器 下载请求:不静默落盘,给提示+把链接回传 UI 供复制
            webView.setBackgroundColor(Color.parseColor(browserBarBg != null ? browserBarBg : "#07080B"));   // UI 层底色随主题(#1)：承载顶栏/底栏/起始页;netView 叠其上
            rootLayout = new FrameLayout(this);
            rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            rootLayout.addView(netView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));   // netView 置顶:真网页居最上层,可正常接收触摸/聚焦唤起输入法
        }

        // 资产加密加载：apps/*.html 构建期已 AES-GCM 加密为 assets/enc/*.bin，目录里无明文——
        //   外界无法从 APK/文件路径直接打开这些应用页面；仅本输入法运行时内存解密。
        String html = decryptAsset(this, safe);
        if (html != null) {
            webView.loadDataWithBaseURL("https://ankey.app/apps/", html, "text/html", "utf-8", null);
        } else {
            webView.loadUrl("file:///android_asset/apps/" + safe + ".html");   // 开发期明文回退
        }
        setContentView(isBrowser ? (View) rootLayout : (View) webView);
    }

    // JS 字符串安全转义(供 evaluateJavascript 传参)
    static String jsStr(String s) {
        if (s == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '"':  b.append("\\\""); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int)c));
                    else b.append(c);
            }
        }
        b.append("\"");
        return b.toString();
    }

    @Override
    public void onBackPressed() {
        if (isBrowser && netView != null && netView.getVisibility() == View.VISIBLE) {
            if (netView.canGoBack()) { netView.goBack(); return; }   // 网页可后退→先退网页
            netView.setVisibility(View.GONE);                        // 网页到头→回 PURE UI 起始页
            if (webView != null) webView.evaluateJavascript("window.__onNetClosed&&window.__onNetClosed()", null);
            return;
        }
        finish();   // 返回手势/返回键 = 关闭应用窗口
    }

    @Override
    protected void onDestroy() {
        try { if (webView != null) webView.destroy(); } catch (Throwable t) {}
        try { if (netView != null) netView.destroy(); } catch (Throwable t) {}
        webView = null; netView = null;
        super.onDestroy();
    }

    // ====== 与输入法内 PwBridge 完全同名同格式的加密数据桥 ======
    class PwBridge {
        private static final String KS_ALIAS = "ankey_appdata_key";
        private static final String KS_NAME  = "AndroidKeyStore";

        // ===== #浏览器 真 WebView 控制接口(仅 browser 应用生效) =====
        @JavascriptInterface
        public void browserSslProceed() { handler.post(() -> {
            // 用户在警告页明示"仍要访问(不安全)"→ 仅放行本次挂起的握手
            if (pendingSslHandler != null) { try { pendingSslHandler.proceed(); } catch (Throwable t) {} pendingSslHandler = null; }
        }); }
        @JavascriptInterface
        public void browserSslCancel() { handler.post(() -> {
            if (pendingSslHandler != null) { try { pendingSslHandler.cancel(); } catch (Throwable t) {} pendingSslHandler = null; }
        }); }
        @JavascriptInterface
        public void browserLoad(final String url) {
            if (!isBrowser || url == null) return;
            handler.post(() -> { try {
                if (netView != null) { netView.setVisibility(View.VISIBLE); netView.loadUrl(url); }
            } catch (Throwable t) {} });
        }
        @JavascriptInterface
        public void browserShow() {
            if (!isBrowser) return;
            handler.post(() -> { try { if (netView != null) netView.setVisibility(View.VISIBLE); } catch (Throwable t) {} });
        }
        @JavascriptInterface
        public void browserHide() {   // 回起始页/标签/设置：隐藏网页层,露出 PURE UI
            if (!isBrowser) return;
            handler.post(() -> { try { if (netView != null) netView.setVisibility(View.GONE); } catch (Throwable t) {} });
        }
        @JavascriptInterface
        public void browserBack() {
            if (!isBrowser) return;
            handler.post(() -> { try { if (netView != null && netView.canGoBack()) netView.goBack(); } catch (Throwable t) {} });
        }
        @JavascriptInterface
        public void browserForward() {
            if (!isBrowser) return;
            handler.post(() -> { try { if (netView != null && netView.canGoForward()) netView.goForward(); } catch (Throwable t) {} });
        }
        @JavascriptInterface
        public void browserReload() {
            if (!isBrowser) return;
            handler.post(() -> { try { if (netView != null) netView.reload(); } catch (Throwable t) {} });
        }
        @JavascriptInterface
        public void browserStop() {
            if (!isBrowser) return;
            handler.post(() -> { try { if (netView != null) netView.stopLoading(); } catch (Throwable t) {} });
        }
        @JavascriptInterface
        public boolean browserCanGoBack() {
            try { return isBrowser && netView != null && netView.canGoBack(); } catch (Throwable t) { return false; }
        }
        @JavascriptInterface
        public int browserAdCount() { try { return adBlockedCount.get(); } catch (Throwable t) { return 0; } }   // #浏览器 真实广告拦截计数(名实之辨)
        @JavascriptInterface
        public void browserClearData() {   // 无痕/清除数据：清 cookie + 缓存 + 表单
            if (!isBrowser) return;
            handler.post(() -> { try {
                android.webkit.CookieManager.getInstance().removeAllCookies(null);
                android.webkit.CookieManager.getInstance().flush();
                if (netView != null) { netView.clearCache(true); netView.clearFormData(); netView.clearHistory(); }
            } catch (Throwable t) {} });
        }
        @JavascriptInterface
        public void browserSetIncognito(final boolean on) {
            if (!isBrowser) return;
            handler.post(() -> { try {
                android.webkit.CookieManager.getInstance().setAcceptCookie(!on);
                if (netView != null) {
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(netView, !on);
                    netView.getSettings().setCacheMode(on ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);
                    netView.getSettings().setDomStorageEnabled(!on);   // 真·无痕:关 DOM 存储(localStorage/sessionStorage 不落地)
                    if (on) {   // 进入无痕:清 cookie+缓存+表单+历史+WebStorage,断绝跨会话痕迹
                        android.webkit.CookieManager.getInstance().removeAllCookies(null);
                        android.webkit.CookieManager.getInstance().flush();
                        netView.clearCache(true); netView.clearFormData(); netView.clearHistory();
                        android.webkit.WebStorage.getInstance().deleteAllData();
                    }
                }
            } catch (Throwable t) {} });
        }
        // #浏览器 让位顶栏/底栏:JS 量取内容区(#stage)边界(设备px)传入,netView 靠 margin 精确覆盖内容区,顶/底栏露出可点
        @JavascriptInterface
        public void browserSetInsets(final int topPx, final int bottomPx) {
            if (!isBrowser) return;
            handler.post(() -> { try {
                if (netView == null) return;
                android.view.ViewGroup.LayoutParams lp0 = netView.getLayoutParams();
                if (lp0 instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lp0;
                    lp.topMargin = Math.max(0, topPx);
                    lp.bottomMargin = Math.max(0, bottomPx);
                    netView.setLayoutParams(lp);
                    netView.requestLayout();
                }
            } catch (Throwable t) {} });
        }


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
                fos.flush(); fos.getFD().sync();
                fos.close();
                if (target.exists()) { try { copyFile(target, bak); } catch (Throwable ig) {} }
                if (!tmp.renameTo(target)) {
                    copyFile(tmp, target);
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

        @JavascriptInterface
        public String loadAppData(final String app) {
            java.io.File main = dataFile(app);
            String r = tryLoad(main);
            if (r != null) return r;
            java.io.File bak = new java.io.File(main.getParentFile(), main.getName() + ".bak");
            String rb = tryLoad(bak);
            return rb != null ? rb : "";
        }

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

        // 复制：只进输入法私有剪贴板(同一 SharedPreferences)，绝不写系统剪贴板
        @JavascriptInterface
        public void copyToClipboard(final String text) {
            try {
                if (text == null || text.isEmpty()) return;
                new com.wubi.ime.clipboard.ClipboardManager(AppActivity.this).add(text);
            } catch (Throwable t) {}
        }

        // #6 删除私有剪贴板单条(note"贴"左滑删除/详情内删除)
        @JavascriptInterface
        public void removeClipboardText(final String text) {
            try {
                if (text == null) return;
                com.wubi.ime.clipboard.ClipboardManager cm = new com.wubi.ime.clipboard.ClipboardManager(AppActivity.this);
                java.util.List<String> a = cm.getAll();
                if (a == null) return;
                int idx = a.indexOf(text);
                if (idx >= 0) cm.delete(idx);
            } catch (Throwable t) {}
        }

        // #4 清空私有剪贴板(安全抹除，与 IME 桥同构)
        @JavascriptInterface
        public void clearClipboardAll() {
            try {
                com.wubi.ime.clipboard.ClipboardManager cm = new com.wubi.ime.clipboard.ClipboardManager(AppActivity.this);
                java.util.List<String> cur = cm.getAll();
                int n = (cur == null) ? 0 : cur.size();
                java.util.Random rnd = new java.util.Random();
                for (int round = 0; round < 3; round++) {
                    cm.clearAll();
                    for (int i = 0; i < Math.max(1, n); i++) {
                        StringBuilder sb = new StringBuilder();
                        for (int k = 0; k < 64; k++) sb.append((char)('a' + rnd.nextInt(26)));
                        cm.add(sb.toString());
                    }
                }
                cm.clearAll();
            } catch (Throwable t) {}
        }

        @JavascriptInterface
        public String getClipboardLatest() {
            try {
                java.util.List<String> a = new com.wubi.ime.clipboard.ClipboardManager(AppActivity.this).getAll();
                if (a != null && !a.isEmpty()) return a.get(0);
            } catch (Throwable t) {}
            return "";
        }

        @JavascriptInterface
        public String getClipboardAll() {
            try {
                java.util.List<String> a = new com.wubi.ime.clipboard.ClipboardManager(AppActivity.this).getAll();
                org.json.JSONArray arr = new org.json.JSONArray();
                if (a != null) for (String s : a) arr.put(s);
                return arr.toString();
            } catch (Throwable t) { return "[]"; }
        }

        // 应用叠加：在当前独立应用之上再开一个应用窗口(如记事本里直接打开密码生成器)。
        //   launchMode=standard + 同任务 startActivity → 新窗口压栈，返回键逐层退回。
        @JavascriptInterface
        public boolean openApp(final String name) {
            try {
                String safe = name == null ? "" : name.replaceAll("[^a-zA-Z0-9_]", "");
                if (safe.isEmpty()) return false;
                if (!"note".equals(safe) && !"pwgen".equals(safe) && !"browser".equals(safe) && !"audio".equals(safe)) return false;   // 白名单同 loadAppHtml
                android.content.Intent it = new android.content.Intent(AppActivity.this, AppActivity.class);
                it.putExtra("app", safe);
                handler.post(() -> { try { startActivity(it); } catch (Throwable t) {} });
                return true;
            } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public void closeApp() {
            handler.post(() -> { try { finish(); } catch (Throwable t) {} });
        }

        // ===== 导出根治：SAF 系统「保存至」对话框 + 分块传输 =====
        //   旧 saveFile(单次大 base64 + 静默 MediaStore) 真机多轮不落盘且无任何系统反馈。
        //   现改：JS 分块传入(每块≤512KB，杜绝超大字符串单次跨桥失败) → saveEnd 弹出
        //   ACTION_CREATE_DOCUMENT 系统对话框(用户亲眼选保存位置) → 写入后回调
        //   window.onSaveResult(ok, msg)。系统 UI 兜底，用户可见、结果可信。
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
                byte[] part = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                _saveBuf.write(part);
                return true;
            } catch (Throwable t) { return false; }
        }

        @JavascriptInterface
        public String saveEnd() {
            if (_saveBuf == null || _saveName == null) return "ERR:no-data";
            handler.post(() -> {
                try {
                    android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
                    it.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                    it.setType(_saveMime);
                    it.putExtra(android.content.Intent.EXTRA_TITLE, _saveName);
                    startActivityForResult(it, REQ_SAVE_DOC);
                } catch (Throwable t) {
                    notifySaveResult(false, "无法打开系统保存对话框");
                }
            });
            return "PICKER";   // JS 侧等待 window.onSaveResult 回调
        }
    }

    static final int REQ_SAVE_DOC = 7301;
    static final int REQ_FILE_CHOOSER = 7302;
    private android.webkit.ValueCallback<android.net.Uri[]> fileChooserCb;   // #浏览器 网页文件上传回调
    private android.webkit.SslErrorHandler pendingSslHandler;   // #浏览器 挂起的证书决策(用户点"仍要访问"/"取消")
    private final java.util.concurrent.atomic.AtomicInteger adBlockedCount = new java.util.concurrent.atomic.AtomicInteger(0);   // #浏览器 真实广告拦截计数
    private View fsCustomView;                                                // #浏览器 全屏视频覆盖层
    private WebChromeClient.CustomViewCallback fsCallback;
    // #浏览器 内置广告/追踪 host 黑名单(散件内联,离线,无需联网更新)。shouldInterceptRequest 命中→空响应=真实拦截,非编造计数。
    private static final String[] AD_HOSTS = {
        "doubleclick.net","googlesyndication.com","googleadservices.com","googletagservices.com","googletagmanager.com",
        "google-analytics.com","adservice.google.com","app-measurement.com","scorecardresearch.com","pos.baidu.com",
        "hm.baidu.com","cpro.baidu.com","cbjs.baidu.com","cnzz.com","umeng.com","umeng.co","mmstat.com","tanx.com",
        "gdt.qq.com","pingjs.qq.com","simba.taobao.com","adnxs.com","criteo.com","criteo.net","facebook.net",
        "connect.facebook.net","moatads.com","doubleverify.com","taboola.com","outbrain.com","quantserve.com",
        "chartbeat.com","mixpanel.com","segment.io","segment.com","amplitude.com","hotjar.com","fullstory.com",
        "adform.net","rubiconproject.com","pubmatic.com","openx.net","casalemedia.com","smartadserver.com","teads.tv",
        "yieldmo.com","sharethrough.com","indexww.com","33across.com","bidswitch.net","growingio.com","sensorsdata.cn"
    };
    private static boolean isAdHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        for (String h : AD_HOSTS) { if (host.equals(h) || host.endsWith("." + h)) return true; }
        return false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { android.webkit.CookieManager.getInstance().flush(); } catch (Throwable t) {}   // 防 cookie 丢失
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {   // #浏览器 网页文件上传结果回传
            android.net.Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int c = data.getClipData().getItemCount();
                    results = new android.net.Uri[c];
                    for (int i = 0; i < c; i++) results[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getDataString() != null) {
                    results = new android.net.Uri[]{ android.net.Uri.parse(data.getDataString()) };
                }
            }
            if (fileChooserCb != null) { try { fileChooserCb.onReceiveValue(results); } catch (Throwable t) {} fileChooserCb = null; }
            return;
        }
        if (requestCode != REQ_SAVE_DOC) return;
        PwBridge b = bridge;
        if (b == null) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            b._saveBuf = null;
            notifySaveResult(false, "已取消");
            return;
        }
        try {
            java.io.OutputStream os = getContentResolver().openOutputStream(data.getData());
            os.write(b._saveBuf.toByteArray()); os.flush(); os.close();
            String nm = b._saveName;
            b._saveBuf = null;
            notifySaveResult(true, nm);
        } catch (Throwable t) {
            b._saveBuf = null;
            android.util.Log.e("WubiIME_PW", "SAF write failed", t);
            notifySaveResult(false, "写入失败");
        }
    }

    private void notifySaveResult(final boolean ok, final String msg) {
        handler.post(() -> {
            try {
                if (webView != null) {
                    String js = "if(window.onSaveResult)window.onSaveResult(" + ok + "," +
                        org.json.JSONObject.quote(msg == null ? "" : msg) + ");";
                    webView.evaluateJavascript(js, null);
                }
            } catch (Throwable t) {}
        });
    }
}
