# ===== 安键输入法 R8 混淆规则：最小保留面，其余自家代码全部混淆 =====
# JS 桥：WebView 通过反射按方法名调用，@JavascriptInterface 方法名与所在类实例必须保留
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# 系统组件入口(Manifest 引用类名)：AGP 会自动 keep，此处显式双保险
-keep class com.wubi.ime.ime.WubiWebViewIME { public protected *; }
-keep class com.wubi.ime.ime.AppActivity { public protected *; }
-keep class com.wubi.ime.ime.SettingsActivity { public protected *; }
# 其余(引擎/剪贴板/主题等)全部混淆压缩——不再 keep com.wubi.ime.**
