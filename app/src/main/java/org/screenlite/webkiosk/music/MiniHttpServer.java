package org.screenlite.webkiosk.music;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
/** 店内LAN向けの小さな HTTP/1.1 サーバー（1接続1リクエスト・Connection: close）。 */
public final class MiniHttpServer {
    public interface Handler { MusicHttp.Response handle(MusicHttp.Request r); }
    /** PUT /api/song の本文をメモリに読まずに渡す。headersOnly の body は空。 */
    public interface Uploader { MusicHttp.Response upload(MusicHttp.Request headersOnly, InputStream body, long len); }
    static final int MAX_HEAD = 8 * 1024, MAX_BODY = 16 * 1024, DRAIN_LIMIT = 1024 * 1024;
    /** 要求ヘッダ＋本文を読み切るまでの期限・同時に処理する接続数・待ち行列の長さ */
    static final int READ_DEADLINE_MS = 3000, MAX_CONN = 16, MAX_QUEUE = 16;
    /** 曲の受け取り中の期限（1回の読み取り・全体） */
    static final int UPLOAD_READ_MS = 20_000, UPLOAD_DEADLINE_MS = 10 * 60_000;
    private final Uploader uploader;
    private final java.util.concurrent.Semaphore uploadSlot = new java.util.concurrent.Semaphore(1);
    /** ファイル応答（曲の取り出し）の書き込み期限・同時本数 */
    static final int WRITE_DEADLINE_MS = 2 * 60_000, MAX_FILE_RESPONSES = 3;
    private final java.util.concurrent.Semaphore fileSlot = new java.util.concurrent.Semaphore(MAX_FILE_RESPONSES);
    private final int wantPort;
    private final Handler handler;
    private volatile ServerSocket server;
    private ExecutorService pool;
    private ScheduledExecutorService watchdog;
    private Thread acceptor;

    public MiniHttpServer(int port, Handler handler) { this(port, handler, null); }
    public MiniHttpServer(int port, Handler handler, Uploader uploader) { this.wantPort = port; this.handler = handler; this.uploader = uploader; }

    public synchronized void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), wantPort), 32);
        server = s;
        pool = new ThreadPoolExecutor(MAX_CONN, MAX_CONN, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(MAX_QUEUE));
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "music-http-wd"); t.setDaemon(true); return t; });
        acceptor = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket c = s.accept();
                    try { pool.execute(() -> serve(c)); } catch (Exception e) { closeQuiet(c); }
                } catch (IOException e) {
                    if (s.isClosed()) break;
                }
            }
        }, "music-http");
        acceptor.setDaemon(true);
        acceptor.start();
    }
    public int port() { ServerSocket s = server; return s == null ? -1 : s.getLocalPort(); }
    public synchronized void stop() {
        ServerSocket s = server;
        if (s != null) { try { s.close(); } catch (IOException ignored) { } }
        if (pool != null) pool.shutdownNow();
        if (watchdog != null) watchdog.shutdownNow();
    }

    private void serve(Socket c) {
        ScheduledFuture<?> guard = null;
        final ScheduledFuture<?>[] upGuard = { null };
        boolean fileSlotHeld = false;
        try {
            c.setSoTimeout(READ_DEADLINE_MS);
            // 読み取り全体の期限: 少しずつ送り続ける接続も期限で切る
            try { guard = watchdog.schedule(() -> closeQuiet(c), READ_DEADLINE_MS, TimeUnit.MILLISECONDS); } catch (Exception e) { return; }
            InputStream in = new BufferedInputStream(c.getInputStream(), 4096);
            OutputStream out = new BufferedOutputStream(c.getOutputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 3) { write(out, simple(400)); return; }
            Map<String, String> headers = new HashMap<>();
            int total = line.length();
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                total += h.length();
                if (total > MAX_HEAD) { write(out, simple(431)); return; }
                int i = h.indexOf(':');
                if (i > 0) headers.put(h.substring(0, i).trim().toLowerCase(), h.substring(i + 1).trim());
            }
            long len = 0;
            try { if (headers.containsKey("content-length")) len = Long.parseLong(headers.get("content-length")); }
            catch (NumberFormatException e) { write(out, simple(400)); return; }
            if (len < 0) { write(out, simple(400)); return; }
            String path0 = parts[1];
            int q0 = path0.indexOf('?');
            if (q0 >= 0) path0 = path0.substring(0, q0);
            if (uploader != null && "PUT".equals(parts[0]) && "/api/song".equals(path0)) {
                if (!headers.containsKey("content-length")) { write(out, simple(411)); return; }
                if (len > SongLibrary.MAX_SONG_BYTES) { write(out, simple(413)); return; }
                // 送信枠の取得と期限の延長は、Host と通行証の確認が済んで本文を読み始めるときにする
                final ScheduledFuture<?> headGuard = guard;
                String remote = c.getInetAddress() == null ? "" : c.getInetAddress().getHostAddress();
                Limited body = new Limited(in, len, () -> {
                    if (!uploadSlot.tryAcquire()) return false;
                    headGuard.cancel(false);
                    try { upGuard[0] = watchdog.schedule(() -> closeQuiet(c), UPLOAD_DEADLINE_MS, TimeUnit.MILLISECONDS); } catch (Exception e) { uploadSlot.release(); return false; }
                    try { c.setSoTimeout(UPLOAD_READ_MS); } catch (IOException e) { /* そのまま読む */ }
                    return true;
                });
                MusicHttp.Response r;
                try {
                    try { r = uploader.upload(new MusicHttp.Request(parts[0], path0, headers, new byte[0], remote), body, len); }
                    catch (RuntimeException e) { r = simple(500); }
                    if (body.busy) r = simple(503);
                    if (r.status == 401 || r.status == 403 || body.busy) {
                        // 本文を待たずに即応答して切る
                        write(out, r);
                        try { c.shutdownOutput(); } catch (IOException ignored) { }
                        return;
                    }
                    // 断った送信の残りは小さければ読み捨てる（相手に応答を届けるため）
                    if (body.left > 0 && body.left <= DRAIN_LIMIT) { byte[] buf = new byte[4096]; while (body.read(buf, 0, buf.length) > 0) { } }
                    write(out, r);
                } finally { if (body.acquired) uploadSlot.release(); if (upGuard[0] != null) upGuard[0].cancel(false); }
                return;
            }
            if (len > MAX_BODY) {
                if (len <= DRAIN_LIMIT) { long left = len; byte[] buf = new byte[4096]; int n; while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) left -= n; }
                write(out, simple(413));
                return;
            }
            byte[] body = new byte[(int) len];
            int off = 0;
            while (off < body.length) { int n = in.read(body, off, body.length - off); if (n < 0) break; off += n; }
            if (off < body.length) return;
            guard.cancel(false);
            if (c.isClosed()) return;
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            String remote = c.getInetAddress() == null ? "" : c.getInetAddress().getHostAddress();
            MusicHttp.Response r;
            try { r = handler.handle(new MusicHttp.Request(parts[0], path, headers, body, remote)); }
            catch (RuntimeException e) { r = simple(500); }
            if (r.file != null && r.status != 204) {
                // ファイル応答は同時本数を制限し、書き込みにも期限を付ける（受け取らない相手でワーカーが埋まらないように）
                if (!fileSlot.tryAcquire()) { write(out, simple(503)); return; }
                fileSlotHeld = true;
                try { guard = watchdog.schedule(() -> closeQuiet(c), WRITE_DEADLINE_MS, TimeUnit.MILLISECONDS); } catch (Exception e) { return; }
            }
            write(out, r);
        } catch (IOException ignored) {
        } finally {
            if (guard != null) guard.cancel(false);
            if (upGuard[0] != null) upGuard[0].cancel(false);
            if (fileSlotHeld) fileSlot.release();
            closeQuiet(c);
        }
    }
    private static MusicHttp.Response simple(int status) {
        return new MusicHttp.Response(status, "text/plain; charset=utf-8", ("" + status).getBytes(StandardCharsets.UTF_8), new HashMap<>());
    }
    private static String reason(int s) {
        switch (s) {
            case 200: return "OK"; case 204: return "No Content"; case 302: return "Found"; case 400: return "Bad Request";
            case 401: return "Unauthorized"; case 403: return "Forbidden"; case 404: return "Not Found"; case 405: return "Method Not Allowed";
            case 411: return "Length Required"; case 413: return "Payload Too Large"; case 500: return "Internal Server Error"; case 507: return "Insufficient Storage"; case 429: return "Too Many Requests"; case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable"; default: return "Error";
        }
    }
    private static void write(OutputStream out, MusicHttp.Response r) throws IOException {
        File file = r.status == 204 ? null : r.file;
        byte[] body = r.status == 204 || r.body == null || file != null ? new byte[0] : r.body;
        long length = file != null ? file.length() : body.length;
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        if (r.contentType != null && r.status != 204) sb.append("Content-Type: ").append(r.contentType).append("\r\n");
        sb.append("Content-Length: ").append(length).append("\r\n");
        sb.append("Connection: close\r\n");
        if (r.headers != null) for (Map.Entry<String, String> e : r.headers.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (k == null || v == null || k.contains("\n") || v.contains("\n") || v.contains("\r")) continue;
            sb.append(k).append(": ").append(v).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (file != null) {
            try (InputStream f = new FileInputStream(file)) {
                byte[] buf = new byte[16 * 1024]; long left = length; int n;
                while (left > 0 && (n = f.read(buf, 0, (int) Math.min(buf.length, left))) > 0) { out.write(buf, 0, n); left -= n; }
            }
        } else out.write(body);
        out.flush();
    }
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > MAX_HEAD) throw new IOException("line too long");
        }
        if (ch < 0 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }
    interface OnFirstRead { boolean begin(); }
    /** 本文の長さまでしか読ませない入口。最初に読むときに送信枠を取る（取れなければ busy）。 */
    static final class Limited extends InputStream {
        private final InputStream in; private final OnFirstRead first; long left; boolean started, acquired, busy;
        Limited(InputStream in, long len, OnFirstRead first) { this.in = in; this.left = len; this.first = first; }
        private void start() throws IOException {
            if (started) return;
            started = true;
            if (first.begin()) acquired = true;
            else { busy = true; }
        }
        @Override public int read() throws IOException {
            start(); if (busy) throw new IOException("busy");
            if (left <= 0) return -1; int b = in.read(); if (b >= 0) left--; return b;
        }
        @Override public int read(byte[] b, int off, int n) throws IOException {
            start(); if (busy) throw new IOException("busy");
            if (left <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(n, left));
            if (r > 0) left -= r;
            return r;
        }
    }
    private static void closeQuiet(Socket c) { try { c.close(); } catch (IOException ignored) { } }
}
