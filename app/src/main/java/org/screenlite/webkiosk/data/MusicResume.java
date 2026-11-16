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

    /** 起動時に開くページ。保存が無い・使えないなら home。 */
    public static String startUrl(String saved, String home, int port) {
        if (MP3.equals(saved)) return "http://127.0.0.1:" + port + "/mp3";
        String ok = toSaved(saved);
        return (ok != null && !MP3.equals(ok)) ? ok : home;
    }
}
