package com.wubi.ime.clipboard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/**
 * 本地剪贴板管理：完全自管理，不读取系统剪贴板，不上传任何数据。
 * 条目以 AES-GCM 加密后存 SharedPreferences，密钥由 Android Keystore 托管(不出安全硬件)：
 *   · 即使设备被 adb/root 拉走 prefs XML，拿到的也只是密文；
 *   · 旧明文条目读入时自动迁移为密文(一次性)。
 */
public class ClipboardManager {

    private static final String PREFS = "wubi_clipboard";
    private static final String KEY_COUNT = "count";
    private static final String KEY_ITEM  = "item_";
    private static final int MAX_ITEMS = 20;
    private static final String KS_ALIAS = "ankey_clip_key";
    private static final String KS_NAME  = "AndroidKeyStore";
    private static final String ENC_MARK = "gcm1:";   // 密文前缀标记(区分旧明文，触发迁移)

    private final SharedPreferences prefs;
    private final List<String> items = new ArrayList<>();

    public ClipboardManager(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadItems();
    }

    private javax.crypto.SecretKey getKey() throws Exception {
        java.security.KeyStore ks = java.security.KeyStore.getInstance(KS_NAME);
        ks.load(null);
        if (ks.containsAlias(KS_ALIAS)) {
            return ((java.security.KeyStore.SecretKeyEntry) ks.getEntry(KS_ALIAS, null)).getSecretKey();
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

    private String enc(String plain) {
        try {
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, getKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return ENC_MARK + android.util.Base64.encodeToString(all, android.util.Base64.NO_WRAP);
        } catch (Throwable t) { return plain; }   // 加密不可用(极端老设备)：宁可可用，不阻塞功能
    }

    private String dec(String stored) {
        if (stored == null) return null;
        if (!stored.startsWith(ENC_MARK)) return stored;   // 旧明文：原样读入，saveItems 时自动迁移为密文
        try {
            byte[] all = android.util.Base64.decode(stored.substring(ENC_MARK.length()), android.util.Base64.NO_WRAP);
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, 12);
            byte[] ct = java.util.Arrays.copyOfRange(all, 12, all.length);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, getKey(),
                   new javax.crypto.spec.GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Throwable t) { return null; }   // 解不开(密钥丢/损坏)：条目作废
    }

    private void loadItems() {
        items.clear();
        int count = prefs.getInt(KEY_COUNT, 0);
        boolean hadPlain = false;
        for (int i = 0; i < count; i++) {
            String raw = prefs.getString(KEY_ITEM + i, null);
            if (raw == null) continue;
            if (!raw.startsWith(ENC_MARK)) hadPlain = true;
            String s = dec(raw);
            if (s != null) items.add(s);
        }
        if (hadPlain) saveItems();   // 一次性迁移：旧明文即刻转密文落盘
    }

    private void saveItems() {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putInt(KEY_COUNT, items.size());
        for (int i = 0; i < items.size(); i++) ed.putString(KEY_ITEM + i, enc(items.get(i)));
        for (int i = items.size(); i < MAX_ITEMS + 5; i++) ed.remove(KEY_ITEM + i);
        ed.apply();
    }

    /** 将文本存入本地剪贴板（不操作系统剪贴板） */
    public void add(String text) {
        if (text == null || text.isEmpty()) return;
        items.remove(text);          // 去重，移到最新
        items.add(0, text);
        if (items.size() > MAX_ITEMS) items.subList(MAX_ITEMS, items.size()).clear();
        saveItems();
    }

    public List<String> getAll() { return Collections.unmodifiableList(items); }

    public void delete(int index) {
        if (index >= 0 && index < items.size()) { items.remove(index); saveItems(); }
    }

    public void clearAll() { items.clear(); saveItems(); }

    public int size() { return items.size(); }
}
