package org.screenlite.webkiosk.data;

import java.net.URI;

/** 音楽ブラウザで最後に流していたページを覚え、起動時にそこへ戻すための判定（純粋関数）。 */
public final class MusicResume {
    public static final String MP3 = "mp3";
    private static final int MAX_LEN = 2000;
    private MusicResume() { }

    /** 保存する値。保存しないなら null。https のページ、またはテレビ内MP3ページ（"mp3"）だけ。 */
    public static String toSaved(String url) {
        if (url == null || url.length() > MAX_LEN) return null;
        try {
            URI u = new URI(url);
            String scheme = u.getScheme(), host = u.getHost();
            if (host == null) return null;
            if ("https".equals(scheme)) return url;
            if ("http".equals(scheme) && "127.0.0.1".equals(host) && "/mp3".equals(u.getPath())) return MP3;
        } catch (Exception ignored) { }
        return null;
    }

    /** 起動時に開くページ。1.2.6: 保存の中身に関係なく、いつもテレビ内MP3ページ。 */
    public static String startUrl(String saved, String home, int port) {
        return "http://127.0.0.1:" + port + "/mp3";
    }

    /** 読み込みに失敗したページを開き直すまでの待ち時間（1回目5秒・倍々・60秒で頭打ち）。 */
    public static long retryDelayMs(int attempt) {
        if (attempt <= 1) return 5000L;
        if (attempt >= 5) return 60000L;
        return 5000L << (attempt - 1);
    }

    /** 開き直す対象か（http/https のページだけ。about:blank などは対象外）。 */
    public static boolean shouldRetry(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(java.util.Locale.ROOT);
        return l.startsWith("http://") || l.startsWith("https://");
    }
}
