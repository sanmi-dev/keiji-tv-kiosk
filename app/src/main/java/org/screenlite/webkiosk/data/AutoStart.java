package org.screenlite.webkiosk.data;

/** 電源オン時の自動起動に必要な「他のアプリの上に重ねて表示」の許可まわり（Android 非依存）。 */
public final class AutoStart {
    private AutoStart() {}

    /** テレビの画面に出す注意の文言（60字以内・2行以内）。 */
    public static final String NOTICE = "自動起動の設定が必要：設定＞アプリ＞特別なアプリアクセス＞他のアプリの上に重ねて表示＞掲示物テレビをオン";

    /** 注意を出す時間（表示に戻ってから）。法定掲示を長く覆わない。 */
    public static final long NOTICE_SHOW_MS = 120_000L;

    /** 設定画面を開いたあと、掲示を前面に戻さない時間。 */
    public static final long SETTINGS_PAUSE_MS = 180_000L;

    /** Android 6（API 23）以上で許可が無いときだけ注意を出す。 */
    public static boolean needsOverlayNotice(int sdkInt, boolean canDrawOverlays) {
        return sdkInt >= 23 && !canDrawOverlays;
    }

    /** 注意が必要で、表示に戻ってから2分未満のときだけ帯を出す。 */
    public static boolean showNotice(int sdkInt, boolean canDrawOverlays, long msSinceResume) {
        return needsOverlayNotice(sdkInt, canDrawOverlays) && msSinceResume < NOTICE_SHOW_MS;
    }

    /** 掲示が見えておらず、設定のための休止中でもなければ前面に戻す。 */
    public static boolean shouldBringToFront(boolean visible, long now, long pauseUntil) {
        return !visible && now >= pauseUntil;
    }
}
