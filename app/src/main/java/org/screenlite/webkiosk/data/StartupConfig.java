package org.screenlite.webkiosk.data;
public final class StartupConfig {
    public static final String ENTRY = "https://sanmi-dev.github.io/sanmi-apps/tv";
    /** 音楽画面の初期表示（テスト曲）。 */
    public static final String MUSIC_HOME = "https://www.youtube.com/watch?v=RY11iULaX7E";
    /** テレビ内サーバーの MP3 予備ページ（音楽 WebView から見る）。 */
    public static final String MUSIC_MP3 = "http://127.0.0.1:8787/mp3";
    public static String signage(String saved) {
        return saved == null || saved.trim().isEmpty() || "about:blank".equals(saved.trim()) ? ENTRY : saved.trim();
    }
}
