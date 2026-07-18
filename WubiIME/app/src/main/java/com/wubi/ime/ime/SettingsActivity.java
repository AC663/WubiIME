package com.wubi.ime.ime;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
public class SettingsActivity extends Activity {

    // 隐私铁律：设置页(App 入口)不持有、不读取、不展示私有剪贴板。
    //   剪贴板数据只在输入法键盘使用现场(键盘内剪贴面板)可调取。
    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(buildUI());
    }

    private View buildUI() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(0xFFFAF9F7);

        TextView title = new TextView(this);
        title.setText("\u5B89\u952E\u8F93\u5165\u6CD5 \u00B7 \u8BBE\u7F6E");
        title.setTextSize(22);
        title.setTextColor(0xFF1E1A16);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        addSection(root, "1. \u542F\u7528\u8F93\u5165\u6CD5");
        Button btnEnable = makeButton("\u524D\u5F80\u7CFB\u7EDF\u8BED\u8A00\u8BBE\u7F6E \u2192", 0xFFE8501A);
        btnEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(btnEnable);

        Button btnSwitch = makeButton("\u5207\u6362\u81F3\u672C\u8F93\u5165\u6CD5 \u2192", 0xFF888888);
        btnSwitch.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
        root.addView(btnSwitch);

        addSection(root, "\u9690\u79C1\u58F0\u660E");
        TextView privacy = new TextView(this);
        privacy.setText(
            "\u672C\u8F93\u5165\u6CD5\u627F\u8BFA\uFF1A\n" +
            "\u2713 \u96F6\u7F51\u7EDC\u8BF7\u6C42\uFF0C\u5B8C\u5168\u79BB\u7EBF\u8FD0\u884C\n" +
            "\u2713 \u4E0D\u8BFB\u53D6\u7CFB\u7EDF\u526A\u8D34\u677F\n" +
            "\u2713 \u4E0D\u4E0A\u4F20\u4EFB\u4F55\u8F93\u5165\u5185\u5BB9\n" +
            "\u2713 \u4E0D\u7533\u8BF7\u5371\u9669\u6743\u9650\n" +
            "\u2713 \u65E0\u5E7F\u544A\u3001\u65E0\u8FFD\u8E2A\u3001\u65E0\u540E\u53F0\u670D\u52A1");
        privacy.setTextSize(14);
        privacy.setTextColor(0xFF444444);
        privacy.setLineSpacing(6f, 1f);
        root.addView(privacy);

        sv.addView(root);
        return sv;
    }

    private void addSection(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(0xFFE8501A);
        tv.setPadding(0, 24, 0, 8);
        root.addView(tv);
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 6, 0, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
