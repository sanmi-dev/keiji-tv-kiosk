package org.screenlite.webkiosk.music;
import java.net.URI;
import java.net.URLDecoder;
import java.util.*;
/** PCからの操作を検査して積む。不正は IllegalArgumentException、満杯は IllegalStateException。 */
public final class CommandQueue {
    public static final class Command {
        public final long id; public final String action, value; public final double x, y; public final int dy;
        public Command(long id, String action, String value, double x, double y, int dy) { this.id = id; this.action = action; this.value = value; this.x = x; this.y = y; this.dy = dy; }
    }
    static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "Enter", "Tab", "Backspace", "Escape", "Space", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight",
        "PageUp", "PageDown", "Home", "End", "Delete"));
    private final int max;
    private final ArrayDeque<Command> q = new ArrayDeque<>();
    private long seq = 0;

    public CommandQueue(int max) { this.max = max; }

    public long submit(String formBody) {
        Map<String, String> f = parseForm(formBody);
        String action = f.getOrDefault("action", "");
        String value = null; double x = 0, y = 0; int dy = 0;
        switch (action) {
            case "navigate": value = f.get("value"); if (!okUrl(value)) throw bad(); break;
            case "click": x = unit(f.get("x")); y = unit(f.get("y")); break;
            case "scroll":
                try { dy = Integer.parseInt(f.getOrDefault("dy", "")); } catch (NumberFormatException e) { throw bad(); }
                if (Math.abs(dy) > 5000) throw bad();
                break;
            case "text": value = f.get("value"); if (value == null || value.isEmpty() || value.length() > 500) throw bad(); break;
            case "key": value = f.get("value"); if (value == null || !KEYS.contains(value)) throw bad(); break;
            case "preset": value = f.get("value"); if (!"youtube".equals(value) && !"mp3".equals(value)) throw bad(); break;
            case "scrollTo": y = unit(f.get("y")); break;
            case "cast": value = f.get("value"); if (!okCast(value)) throw bad(); break;
            case "autoReturn": value = f.get("value"); if (!"on".equals(value) && !"off".equals(value)) throw bad(); break;
            case "checkUpdate": break;
            case "stop": case "back": case "forward": case "reload": break;
            default: throw bad();
        }
        synchronized (this) {
            if (q.size() >= max) throw new IllegalStateException("queue full");
            long id = ++seq;
            q.add(new Command(id, action, value, x, y, dy));
            return id;
        }
    }
    public synchronized List<Command> drain() {
        List<Command> l = new ArrayList<>(q);
        q.clear();
        return l;
    }
    private static IllegalArgumentException bad() { return new IllegalArgumentException("bad command"); }
    private static double unit(String s) {
        double d;
        try { d = Double.parseDouble(s); } catch (Exception e) { throw bad(); }
        if (Double.isNaN(d) || d < 0 || d > 1) throw bad();
        return d;
    }
    static boolean okUrl(String v) {
        if (v == null || v.isEmpty() || v.length() > 2048) return false;
        try {
            URI u = new URI(v);
            String s = u.getScheme();
            return ("http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s)) && u.getHost() != null;
        } catch (Exception e) { return false; }
    }
    static final Set<String> CAST_HOSTS = new HashSet<>(Arrays.asList(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"));
    /** テレビに映すリンク: https・YouTube のホストだけ・userinfo とポート指定は不可・2048字以内。 */
    public static boolean okCast(String v) {
        if (v == null || v.isEmpty() || v.length() > 2048) return false;
        try {
            URI u = new URI(v);
            if (!"https".equalsIgnoreCase(u.getScheme())) return false;
            if (u.getRawUserInfo() != null || u.getPort() != -1) return false;
            String auth = u.getRawAuthority();
            if (auth == null || auth.indexOf('@') >= 0) return false;
            String host = u.getHost();
            return host != null && CAST_HOSTS.contains(host.toLowerCase(Locale.ROOT));
        } catch (Exception e) { return false; }
    }
    static Map<String, String> parseFormSafe(String body) {
        try { return parseForm(body); } catch (IllegalArgumentException e) { return new HashMap<>(); }
    }
    static Map<String, String> parseForm(String body) {
        Map<String, String> m = new HashMap<>();
        if (body == null) return m;
        for (String part : body.split("&")) {
            if (part.isEmpty()) continue;
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i), v = i < 0 ? "" : part.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception e) { throw bad(); }
        }
        return m;
    }
}
