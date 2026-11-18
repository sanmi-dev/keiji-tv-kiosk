package org.screenlite.webkiosk.music;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/** 操作画面の HTTP 振り分け（Android 非依存）。 */
public final class MusicHttp {
    public interface Frames { byte[] latestJpeg(); String stateJson(); }
    public interface Assets { byte[] get(String name); }
    public static final class Request {
        public final String method, path, remoteIp; public final Map<String, String> headers; public final byte[] body;
        public Request(String method, String path, Map<String, String> headers, byte[] body, String remoteIp) { this.method = method; this.path = path; this.headers = headers; this.body = body; this.remoteIp = remoteIp; }
    }
    public static final class Response {
        public final int status; public final String contentType; public final byte[] body; public final Map<String, String> headers;
        /** null でなければ本文はこのファイル（ストリームで返す）。 */
        public final java.io.File file;
        public Response(int status, String contentType, byte[] body, Map<String, String> headers) { this(status, contentType, body, headers, null); }
        public Response(int status, String contentType, byte[] body, Map<String, String> headers, java.io.File file) { this.status = status; this.contentType = contentType; this.body = body; this.headers = headers; this.file = file; }
    }
    private static final Pattern HOST = Pattern.compile("^([0-9.]+)(:[0-9]{1,5})?$");
    private static final Pattern MP3 = Pattern.compile("^/mp3/([a-z0-9-]+\\.mp3)$");
    private static final Pattern SONG = Pattern.compile("^/mp3/s/([a-z0-9]{1,24})$");
    private static final Pattern BEARER = Pattern.compile("^Bearer\\s+([A-Za-z0-9_-]{32})$");
    static final int PIN_REQ_PER_MIN = 4;

    private final PinPairing pairing;
    private final CommandQueue queue;
    private final Frames frames;
    private final Assets assets;
    private volatile Runnable pinRequestListener = null;
    private final ArrayDeque<Long> pinRequests = new ArrayDeque<>();
    private volatile SongLibrary songs = null;

    public MusicHttp(PinPairing pairing, CommandQueue queue, Frames frames, Assets assets) {
        this.pairing = pairing; this.queue = queue; this.frames = frames; this.assets = assets;
    }
    /** POST /api/pin-request が来たときに呼ばれる（テレビ画面に番号を出す）。 */
    public void setPinRequestListener(Runnable r) { this.pinRequestListener = r; }

    public void setSongs(SongLibrary lib) { this.songs = lib; }

    /** PUT /api/song: 本文はストリームのまま曲の保存へ。 */
    public Response upload(Request r, java.io.InputStream in, long len) {
        if (!hostOk(header(r, "host"))) return text(403, "forbidden");
        if (!authed(r)) return json(401, "{\"ok\":false,\"code\":\"auth\"}");
        SongLibrary lib = songs;
        if (lib == null) return json(503, "{\"ok\":false,\"code\":\"unavailable\"}");
        String raw = header(r, "x-song-name"), name;
        try { name = java.net.URLDecoder.decode(raw == null ? "" : raw, "UTF-8"); }
        catch (Exception e) { name = ""; }
        SongLibrary.Result res = lib.save(name, in, len);
        int st;
        switch (res.code) {
            case "ok": st = 200; break;
            case "too_large": st = 413; break;
            case "full": case "no_space": st = 507; break;
            default: st = 400;
        }
        return json(st, "{\"ok\":" + "ok".equals(res.code) + ",\"code\":\"" + res.code + "\"}");
    }

    public Response handle(Request r, long now) {
        if (!hostOk(header(r, "host"))) return text(403, "forbidden");
        String p = r.path == null ? "" : r.path;
        String m = r.method == null ? "" : r.method;
        switch (p) {
            case "/":
                if (!m.equals("GET")) return text(405, "method");
                Response red = new Response(302, "text/plain; charset=utf-8", new byte[0], common());
                red.headers.put("Location", "/store");
                return red;
            case "/store": return page(m, "store.html");
            case "/mp3": return page(m, "mp3.html");
            case "/api/pin-request": {
                if (!m.equals("POST")) return text(405, "method");
                synchronized (pinRequests) {
                    while (!pinRequests.isEmpty() && now - pinRequests.peekFirst() >= 60_000L) pinRequests.pollFirst();
                    if (pinRequests.size() >= PIN_REQ_PER_MIN) return text(429, "too many");
                    pinRequests.addLast(now);
                }
                Runnable l = pinRequestListener;
                if (l != null) { try { l.run(); } catch (RuntimeException ignored) { } }
                return new Response(204, "text/plain; charset=utf-8", new byte[0], common());
            }
            case "/api/pair": {
                if (!m.equals("POST")) return text(405, "method");
                String pin = CommandQueue.parseFormSafe(body(r)).get("pin");
                PinPairing.Result res = pairing.pair(pin, now);
                if (res.status != 200) return json(res.status, "{\"ok\":false}");
                return json(200, "{\"ok\":true,\"token\":\"" + res.token + "\"}");
            }
            case "/api/command": {
                if (!m.equals("POST")) return text(405, "method");
                if (!authed(r)) return json(401, "{\"ok\":false}");
                try {
                    long id = queue.submit(body(r));
                    return json(200, "{\"ok\":true,\"id\":" + id + "}");
                } catch (IllegalArgumentException e) {
                    return json(400, "{\"ok\":false}");
                } catch (IllegalStateException e) {
                    return json(503, "{\"ok\":false}");
                }
            }
            case "/api/state": {
                if (!m.equals("GET")) return text(405, "method");
                if (!authed(r)) return json(401, "{\"ok\":false}");
                String s = frames.stateJson();
                return json(200, s == null ? "{}" : s);
            }
            case "/api/frame": {
                if (!m.equals("GET")) return text(405, "method");
                if (!authed(r)) return json(401, "{\"ok\":false}");
                byte[] j = frames.latestJpeg();
                if (j == null) return new Response(204, "image/jpeg", new byte[0], common());
                return new Response(200, "image/jpeg", j, common());
            }
            case "/api/songs": {
                if (!m.equals("GET")) return text(405, "method");
                if (!authed(r)) return json(401, "{\"ok\":false}");
                return json(200, songsJson());
            }
            case "/mp3/list": {
                if (!m.equals("GET")) return text(405, "method");
                return json(200, songsJson());
            }
            case "/api/songs/move":
            case "/api/songs/remove": {
                if (!m.equals("POST")) return text(405, "method");
                if (!authed(r)) return json(401, "{\"ok\":false}");
                SongLibrary lib = songs;
                if (lib == null) return json(503, "{\"ok\":false}");
                Map<String, String> f = CommandQueue.parseFormSafe(body(r));
                boolean done;
                if (p.equals("/api/songs/remove")) done = lib.remove(f.get("id"));
                else {
                    String dir = f.get("dir");
                    if (!"up".equals(dir) && !"down".equals(dir)) return json(400, "{\"ok\":false}");
                    done = lib.move(f.get("id"), "up".equals(dir) ? -1 : 1);
                }
                return done ? json(200, lib.toJson()) : json(400, "{\"ok\":false}");
            }
            default:
                Matcher sm = SONG.matcher(p);
                if (sm.matches()) {
                    if (!m.equals("GET")) return text(405, "method");
                    SongLibrary lib = songs;
                    java.io.File f = lib == null ? null : lib.file(sm.group(1));
                    if (f == null) return text(404, "not found");
                    return new Response(200, "audio/mpeg", new byte[0], common(), f);
                }
                Matcher mm = MP3.matcher(p);
                if (mm.matches()) {
                    if (!m.equals("GET")) return text(405, "method");
                    byte[] b = assets.get("mp3/" + mm.group(1));
                    if (b == null) return text(404, "not found");
                    return new Response(200, "audio/mpeg", b, common());
                }
                return text(404, "not found");
        }
    }

    private String songsJson() {
        SongLibrary lib = songs;
        return lib == null ? "{\"songs\":[],\"free\":0,\"total\":0}" : lib.toJson();
    }
    private Response page(String m, String name) {
        if (!m.equals("GET")) return text(405, "method");
        byte[] b = assets.get(name);
        if (b == null) return text(404, "not found");
        return new Response(200, "text/html; charset=utf-8", b, common());
    }
    private boolean authed(Request r) {
        String a = header(r, "authorization");
        if (a == null) return false;
        Matcher m = BEARER.matcher(a.trim());
        return m.matches() && pairing.valid(m.group(1));
    }
    static boolean hostOk(String host) {
        if (host == null) return false;
        Matcher m = HOST.matcher(host.trim());
        if (!m.matches()) return false;
        String ip = m.group(1);
        return "127.0.0.1".equals(ip) || Registration.isPrivateIPv4(ip);
    }
    private static String header(Request r, String name) {
        if (r.headers == null) return null;
        String v = r.headers.get(name);
        if (v != null) return v;
        for (Map.Entry<String, String> e : r.headers.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }
    private static String body(Request r) { return r.body == null ? "" : new String(r.body, StandardCharsets.UTF_8); }
    private static Map<String, String> common() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        h.put("X-Frame-Options", "DENY");
        h.put("Referrer-Policy", "no-referrer");
        return h;
    }
    private static Response text(int status, String msg) {
        return new Response(status, "text/plain; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8), common());
    }
    private static Response json(int status, String s) {
        return new Response(status, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8), common());
    }
}
