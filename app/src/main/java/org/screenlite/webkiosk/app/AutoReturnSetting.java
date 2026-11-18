package org.screenlite.webkiosk.app;

import android.content.Context;
import android.content.SharedPreferences;
import org.screenlite.webkiosk.data.AutoStart;

/** 「ホームに出たとき自動で掲示に戻る」のオン/オフ。読み書きはここだけで行う（見回り・設定画面・店のPCから共通）。 */
public final class AutoReturnSetting {
    private static final String PREFS = "kiosk_auto_return";
    private static final String KEY = "auto_return";
    private AutoReturnSetting() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean get(Context c) {
        try { return prefs(c).getBoolean(KEY, AutoStart.AUTO_RETURN_DEFAULT); } catch (Exception e) { return AutoStart.AUTO_RETURN_DEFAULT; }
    }

    public static void set(Context c, boolean on) {
        try { prefs(c).edit().putBoolean(KEY, on).apply(); } catch (Exception ignored) { }
    }
}
