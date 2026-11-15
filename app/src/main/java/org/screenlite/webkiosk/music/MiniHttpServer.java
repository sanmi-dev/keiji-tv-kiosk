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
    static final int MAX_HEAD = 8 * 1024, MAX_BODY = 16 * 1024, DRAIN_LIMIT = 1024 * 1024;
    /** 要求ヘッダ＋本文を読み切るまでの期限・同時に処理する接続数・待ち行列の長さ */
    static final int READ_DEADLINE_MS = 3000, MAX_CONN = 16, MAX_QUEUE = 16;
    private final int wantPort;
    private final Handler handler;
    private volatile ServerSocket server;
    private ExecutorService pool;
    private ScheduledExecutorService watchdog;
    private Thread acceptor;

    public MiniHttpServer(int port, Handler handler) { this.wantPort = port; this.handler = handler; }

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
            write(out, r);
        } catch (IOException ignored) {
        } finally { if (guard != null) guard.cancel(false); closeQuiet(c); }
    }
    private static MusicHttp.Response simple(int status) {
        return new MusicHttp.Response(status, "text/plain; charset=utf-8", ("" + status).getBytes(StandardCharsets.UTF_8), new HashMap<>());
    }
    private static String reason(int s) {
        switch (s) {
            case 200: return "OK"; case 204: return "No Content"; case 302: return "Found"; case 400: return "Bad Request";
            case 401: return "Unauthorized"; case 403: return "Forbidden"; case 404: return "Not Found"; case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large"; case 429: return "Too Many Requests"; case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable"; default: return "Error";
        }
    }
    private static void write(OutputStream out, MusicHttp.Response r) throws IOException {
        byte[] body = r.status == 204 || r.body == null ? new byte[0] : r.body;
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n");
        if (r.contentType != null && r.status != 204) sb.append("Content-Type: ").append(r.contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n");
        if (r.headers != null) for (Map.Entry<String, String> e : r.headers.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (k == null || v == null || k.contains("\n") || v.contains("\n") || v.contains("\r")) continue;
            sb.append(k).append(": ").append(v).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
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
    private static void closeQuiet(Socket c) { try { c.close(); } catch (IOException ignored) { } }
}
