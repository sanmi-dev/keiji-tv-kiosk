package org.screenlite.webkiosk.data;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 新しい版の確認（純粋部分）。公開 Release latest の version.json を読む。 */
public final class UpdateCheck {
    private UpdateCheck() {}
    public static final String VERSION_URL = "https://github.com/sanmi-dev/keiji-tv-kiosk/releases/latest/download/version.json";
    public static final String APK_URL = "https://github.com/sanmi-dev/keiji-tv-kiosk/releases/latest/download/keiji-tv.apk";
    public static final long INTERVAL_MS = 3L * 60 * 60 * 1000;
    public static final long FIRST_DELAY_MS = 60_000L;
    private static final Pattern P = Pattern.compile("\"versionCode\"\\s*:\\s*\"?(-?\\d+)\"?");

    /** versionCode を返す。読めない・範囲外は -1。 */
    public static int parseVersionCode(String json) {
        if (json == null) return -1;
        Matcher m = P.matcher(json);
        if (!m.find()) return -1;
        String s = m.group(1);
        if (s.startsWith("-") || s.length() > 10) return -1;
        long v = Long.parseLong(s);
        return v > Integer.MAX_VALUE ? -1 : (int) v;
    }

    public static boolean isNewer(int remote, int current) { return remote >= 0 && remote > current; }

    /** リモコンの決定(23)・Enter(66)・テンキーの Enter(160)。 */
    public static boolean isConfirmKey(int keyCode) { return keyCode == 23 || keyCode == 66 || keyCode == 160; }

    /** 続けてこの回数失敗したらポップアップを引っ込める。 */
    public static final int MAX_INSTALL_FAILS = 3;
    public static boolean shouldHideAfterFailures(int fails) { return fails >= MAX_INSTALL_FAILS; }

    /** 転送先は https だけ（大文字小文字は無視）。 */
    public static boolean isHttps(String url) {
        if (url == null) return false;
        int i = url.indexOf(':');
        return i == 5 && url.substring(0, i).equalsIgnoreCase("https");
    }

    public static final long MAX_JSON_BYTES = 64L * 1024;
    public static final long MAX_APK_BYTES = 100L * 1024 * 1024;
    public static boolean withinLimit(long size, long max) { return size >= 0 && size <= max; }

    /** 確認画面を開いたあと、結果が来なければこの時間で押せる状態に戻す。 */
    public static final long WAIT_RESULT_MS = 4L * 60 * 1000;
}
