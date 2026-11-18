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

    /** 「重ねて表示」が未許可なら、更新後に自動で前に出られないので案内する。 */
    public static String overlayNote(boolean canOverlay) {
        return canOverlay ? "" : "更新が終わったら、画面の『開く』を押してください";
    }

    /** 「不明なアプリ」設定を開く前に立てる更新待ちフラグのキー。 */
    public static final String PREF_PENDING = "update_pending";
    public static long firstDelayMs(boolean pending) { return pending ? 5_000L : FIRST_DELAY_MS; }

    /** ダウンロード全体の締め切り。 */
    public static final long DOWNLOAD_TOTAL_MS = 10L * 60 * 1000;
    public static boolean downloadExpired(long startMs, long nowMs) { return nowMs - startMs > DOWNLOAD_TOTAL_MS; }

    /** 暗転中に押した決定キーは暗転を解くだけ。 */
    public static boolean shouldInstallOnKey(boolean wasIdle) { return !wasIdle; }

    public static final String MSG_BROKEN_APK = "落とした更新ファイルが壊れています。もう一度押してください";
}
