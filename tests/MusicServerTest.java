// 店内LANの音楽操作サーバー（テレビ側）の純粋部分のテスト。Android に依存しない。
// 実行（JDK は Codex 作業フォルダの同梱品）:
//   javac -encoding UTF-8 -d <out> app/src/main/java/org/screenlite/webkiosk/data/StartupConfig.java app/src/main/java/org/screenlite/webkiosk/music/*.java tests/MusicServerTest.java
//   java -cp <out> MusicServerTest
import org.screenlite.webkiosk.data.StartupConfig;
import org.screenlite.webkiosk.music.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class MusicServerTest {
  static int failed = 0, passed = 0;
  interface Body { void run() throws Exception; }
  static void check(String label, Body b) {
    try { b.run(); passed++; System.out.println("PASS " + label); }
    catch (Throwable t) { failed++; System.out.println("FAIL " + label + "  -> " + t); }
  }
  static void ok(boolean c, String m) { if (!c) throw new AssertionError(m); }
  static void eq(Object e, Object a) { if (!Objects.equals(e, a)) throw new AssertionError("expected=" + e + " actual=" + a); }

  static final class MemStore implements PinPairing.TokenStore {
    List<String> saved = new ArrayList<>();
    public List<String> load() { return new ArrayList<>(saved); }
    public void save(List<String> t) { saved = new ArrayList<>(t); }
  }
  static final class FakeFrames implements MusicHttp.Frames {
    byte[] jpeg = null;
    public byte[] latestJpeg() { return jpeg; }
    public String stateJson() { return "{\"url\":\"x\"}"; }
  }
  static final class FakeAssets implements MusicHttp.Assets {
    public byte[] get(String name) {
      if (name.equals("store.html")) return "<html>store</html>".getBytes(StandardCharsets.UTF_8);
      if (name.equals("mp3.html")) return "<html>mp3</html>".getBytes(StandardCharsets.UTF_8);
      if (name.equals("mp3/chime-1.mp3")) return new byte[]{1, 2, 3};
      return null;
    }
  }
  static Map<String, String> hdr(String... kv) {
    Map<String, String> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
    return m;
  }
  static MusicHttp.Request req(String method, String path, Map<String, String> h, String body) {
    return new MusicHttp.Request(method, path, h, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), "192.168.1.20");
  }
  static String str(MusicHttp.Response r) { return new String(r.body, StandardCharsets.UTF_8); }
  static String tokenOf(String json) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"token\"\\s*:\\s*\"([A-Za-z0-9_-]{32})\"").matcher(json);
    ok(m.find(), "token not in " + json);
    return m.group(1);
  }

  public static void main(String[] args) throws Exception {
    // ---------- StartupConfig ----------
    check("S1 音楽の初期URLはテスト用YouTube", () -> eq("https://www.youtube.com/watch?v=RY11iULaX7E", StartupConfig.MUSIC_HOME));
    check("S2 掲示の入口は従来どおり", () -> {
      eq("https://sanmi-dev.github.io/sanmi-apps/tv", StartupConfig.signage(""));
      eq("https://sanmi-dev.github.io/sanmi-apps/tv?store=002", StartupConfig.signage("https://sanmi-dev.github.io/sanmi-apps/tv?store=002"));
    });

    // ---------- PinPairing ----------
    check("P1 番号は4桁・10分以内は同じ・10分過ぎたら変わりうる", () -> {
      PinPairing p = new PinPairing(new MemStore(), new Random(1));
      String a = p.pin(0);
      ok(a.matches("[0-9]{4}"), "4桁でない: " + a);
      eq(a, p.pin(9 * 60_000L));
      // 10分を過ぎると作り直す（乱数次第で同じ値もありうるため、作り直したことは P2 で確かめる）
      ok(p.pin(11 * 60_000L).matches("[0-9]{4}"), "4桁でない");
    });
    check("P2 正しい番号で32文字の通行証・一度使った番号は無効", () -> {
      PinPairing p = new PinPairing(new MemStore(), new Random(2));
      String pin = p.pin(0);
      PinPairing.Result r = p.pair(pin, 1000);
      eq(200, r.status);
      ok(r.token != null && r.token.matches("[A-Za-z0-9_-]{32}"), "token: " + r.token);
      ok(p.valid(r.token), "発行した通行証が通らない");
      eq(401, p.pair(pin, 2000).status);
    });
    check("P3 間違い番号は401・通行証なし", () -> {
      PinPairing p = new PinPairing(new MemStore(), new Random(3));
      String pin = p.pin(0);
      String wrong = pin.equals("0000") ? "1111" : "0000";
      PinPairing.Result r = p.pair(wrong, 1000);
      eq(401, r.status); eq(null, r.token);
      ok(!p.valid(""), "空文字が通った"); ok(!p.valid(null), "null が通った"); ok(!p.valid("x".repeat(32)), "でたらめが通った");
    });
    check("P4 5回間違えたら5分ロック（正しい番号でも429）→5分後は通る", () -> {
      PinPairing p = new PinPairing(new MemStore(), new Random(4));
      String pin = p.pin(0);
      String wrong = pin.equals("0000") ? "1111" : "0000";
      for (int i = 0; i < 5; i++) eq(401, p.pair(wrong, 1000 + i).status);
      eq(429, p.pair(pin, 2000).status);
      eq(429, p.pair(pin, 2000 + 4 * 60_000L).status);
      String pin2 = p.pin(2000 + 5 * 60_000L + 1000);
      eq(200, p.pair(pin2, 2000 + 5 * 60_000L + 1000).status);
    });
    check("P5 不正な形式（3桁・英字・null）は401で数える", () -> {
      PinPairing p = new PinPairing(new MemStore(), new Random(5));
      p.pin(0);
      eq(401, p.pair("123", 1).status); eq(401, p.pair("abcd", 2).status); eq(401, p.pair(null, 3).status);
    });
    check("P6 通行証は保存され、作り直しても有効・最大10件で古い順に消える", () -> {
      MemStore s = new MemStore();
      PinPairing p = new PinPairing(s, new Random(6));
      List<String> tokens = new ArrayList<>();
      long t = 0;
      for (int i = 0; i < 11; i++) { t += 1000; tokens.add(p.pair(p.pin(t), t).token); }
      eq(10, s.saved.size());
      PinPairing again = new PinPairing(s, new Random(7));
      ok(!again.valid(tokens.get(0)), "一番古い通行証が残っている");
      ok(again.valid(tokens.get(10)), "最新の通行証が復元されない");
    });
    check("P7 revokeAll で全通行証が無効", () -> {
      MemStore s = new MemStore();
      PinPairing p = new PinPairing(s, new Random(8));
      String tok = p.pair(p.pin(0), 1).token;
      p.revokeAll();
      ok(!p.valid(tok), "残っている"); eq(0, s.saved.size());
    });

    // ---------- CommandQueue ----------
    check("C1 正しい操作は番号付きで順に取り出せ、取り出すと空", () -> {
      CommandQueue q = new CommandQueue(100);
      long a = q.submit("action=navigate&value=" + URLEncoder.encode("https://www.youtube.com/", "UTF-8"));
      long b = q.submit("action=click&x=0.5&y=0.25");
      ok(a > 0 && b > a, "番号が増えない");
      List<CommandQueue.Command> d = q.drain();
      eq(2, d.size());
      eq("navigate", d.get(0).action); eq("https://www.youtube.com/", d.get(0).value);
      eq("click", d.get(1).action); eq(0.5, d.get(1).x); eq(0.25, d.get(1).y);
      eq(0, q.drain().size());
    });
    check("C2 各操作の値が入る（scroll/text/key/preset/stop/back/forward/reload）", () -> {
      CommandQueue q = new CommandQueue(100);
      q.submit("action=scroll&dy=-300");
      q.submit("action=text&value=" + URLEncoder.encode("音楽 ピアノ", "UTF-8"));
      q.submit("action=key&value=Enter");
      q.submit("action=preset&value=youtube");
      q.submit("action=preset&value=mp3");
      for (String a : new String[]{"stop", "back", "forward", "reload"}) q.submit("action=" + a);
      List<CommandQueue.Command> d = q.drain();
      eq(9, d.size());
      eq(-300, d.get(0).dy); eq("音楽 ピアノ", d.get(1).value); eq("Enter", d.get(2).value);
      eq("youtube", d.get(3).value); eq("mp3", d.get(4).value); eq("reload", d.get(8).action);
    });
    String[] bad = {
      "", "action=eval&value=x", "action=navigate&value=" + enc("javascript:alert(1)"),
      "action=navigate&value=" + enc("file:///sdcard/x"), "action=navigate&value=" + enc("intent://x"),
      "action=navigate&value=" + enc("https://" + "a".repeat(2100)),
      "action=click&x=1.5&y=0.2", "action=click&x=-0.1&y=0.2", "action=click&x=abc&y=0.2", "action=click&x=NaN&y=0.2",
      "action=scroll&dy=999999", "action=scroll&dy=1.5",
      "action=text&value=" + "a".repeat(501), "action=text&value=",
      "action=key&value=F13", "action=preset&value=spotify"
    };
    for (String b : bad) check("C3 不正な操作は拒否: " + (b.length() > 50 ? b.substring(0, 50) + "…" : b), () -> {
      CommandQueue q = new CommandQueue(100);
      try { q.submit(b); throw new AssertionError("受け付けた"); } catch (IllegalArgumentException expected) { }
      eq(0, q.drain().size());
    });
    check("C4 満杯（上限）なら IllegalStateException", () -> {
      CommandQueue q = new CommandQueue(3);
      for (int i = 0; i < 3; i++) q.submit("action=reload");
      try { q.submit("action=reload"); throw new AssertionError("受け付けた"); } catch (IllegalStateException expected) { }
    });

    // ---------- MusicHttp ----------
    check("H1 Host が外部名なら403（DNS rebinding 対策）", () -> {
      MusicHttp h = new MusicHttp(new PinPairing(new MemStore(), new Random(9)), new CommandQueue(10), new FakeFrames(), new FakeAssets());
      eq(403, h.handle(req("GET", "/store", hdr("host", "evil.example:8787"), null), 0).status);
      eq(403, h.handle(req("GET", "/store", hdr("host", "8.8.8.8:8787"), null), 0).status);
      eq(403, h.handle(req("GET", "/store", hdr(), null), 0).status);
      eq(200, h.handle(req("GET", "/store", hdr("host", "192.168.1.7:8787"), null), 0).status);
      eq(200, h.handle(req("GET", "/store", hdr("host", "10.0.0.5:8787"), null), 0).status);
      eq(200, h.handle(req("GET", "/store", hdr("host", "172.20.1.2:8787"), null), 0).status);
      eq(200, h.handle(req("GET", "/store", hdr("host", "127.0.0.1:8787"), null), 0).status);
      eq(403, h.handle(req("GET", "/store", hdr("host", "172.32.1.2:8787"), null), 0).status);
    });
    check("H2 / は /store へ転送・/store は HTML・共通ヘッダ・CORS許可なし", () -> {
      MusicHttp h = new MusicHttp(new PinPairing(new MemStore(), new Random(10)), new CommandQueue(10), new FakeFrames(), new FakeAssets());
      MusicHttp.Response r = h.handle(req("GET", "/", hdr("host", "192.168.1.7:8787"), null), 0);
      eq(302, r.status); eq("/store", r.headers.get("Location"));
      MusicHttp.Response s = h.handle(req("GET", "/store", hdr("host", "192.168.1.7:8787"), null), 0);
      ok(s.contentType.startsWith("text/html"), s.contentType); eq("<html>store</html>", str(s));
      eq("no-store", s.headers.get("Cache-Control")); eq("nosniff", s.headers.get("X-Content-Type-Options"));
      ok(!s.headers.containsKey("Access-Control-Allow-Origin"), "CORS を許可している");
    });
    check("H3 通行証なしの操作APIは401・操作は積まれない", () -> {
      CommandQueue q = new CommandQueue(10);
      MusicHttp h = new MusicHttp(new PinPairing(new MemStore(), new Random(11)), q, new FakeFrames(), new FakeAssets());
      Map<String, String> H = hdr("host", "192.168.1.7:8787");
      eq(401, h.handle(req("POST", "/api/command", H, "action=reload"), 0).status);
      eq(401, h.handle(req("GET", "/api/state", H, null), 0).status);
      eq(401, h.handle(req("GET", "/api/frame", H, null), 0).status);
      eq(401, h.handle(req("POST", "/api/command", hdr("host", "192.168.1.7:8787", "authorization", "Bearer " + "a".repeat(32)), "action=reload"), 0).status);
      eq(0, q.drain().size());
    });
    check("H4 番号で通行証→操作が積まれる・画面と状態が取れる", () -> {
      CommandQueue q = new CommandQueue(10);
      PinPairing p = new PinPairing(new MemStore(), new Random(12));
      FakeFrames f = new FakeFrames();
      MusicHttp h = new MusicHttp(p, q, f, new FakeAssets());
      Map<String, String> H = hdr("host", "192.168.1.7:8787");
      MusicHttp.Response pr = h.handle(req("POST", "/api/pair", H, "pin=" + p.pin(0)), 1);
      eq(200, pr.status); ok(pr.contentType.startsWith("application/json"), pr.contentType);
      String tok = tokenOf(str(pr));
      Map<String, String> A = hdr("host", "192.168.1.7:8787", "authorization", "Bearer " + tok);
      MusicHttp.Response c = h.handle(req("POST", "/api/command", A, "action=preset&value=mp3"), 2);
      eq(200, c.status); ok(str(c).contains("\"id\""), str(c));
      eq("preset", q.drain().get(0).action);
      eq(204, h.handle(req("GET", "/api/frame", A, null), 3).status);
      f.jpeg = new byte[]{9, 9};
      MusicHttp.Response fr = h.handle(req("GET", "/api/frame", A, null), 4);
      eq(200, fr.status); eq("image/jpeg", fr.contentType); eq(2, fr.body.length);
      MusicHttp.Response st = h.handle(req("GET", "/api/state", A, null), 5);
      eq(200, st.status); eq("{\"url\":\"x\"}", str(st));
    });
    check("H5 不正な操作は400・満杯は503・番号違いは401・ロック中は429", () -> {
      CommandQueue q = new CommandQueue(1);
      PinPairing p = new PinPairing(new MemStore(), new Random(13));
      MusicHttp h = new MusicHttp(p, q, new FakeFrames(), new FakeAssets());
      Map<String, String> H = hdr("host", "192.168.1.7:8787");
      String pin = p.pin(0);
      String tok = tokenOf(str(h.handle(req("POST", "/api/pair", H, "pin=" + pin), 1)));
      Map<String, String> A = hdr("host", "192.168.1.7:8787", "authorization", "Bearer " + tok);
      eq(400, h.handle(req("POST", "/api/command", A, "action=eval"), 2).status);
      eq(200, h.handle(req("POST", "/api/command", A, "action=reload"), 3).status);
      eq(503, h.handle(req("POST", "/api/command", A, "action=reload"), 4).status);
      String pin2 = p.pin(10);
      String wrong = pin2.equals("0000") ? "1111" : "0000";
      for (int i = 0; i < 5; i++) eq(401, h.handle(req("POST", "/api/pair", H, "pin=" + wrong), 20 + i).status);
      eq(429, h.handle(req("POST", "/api/pair", H, "pin=" + pin2), 30).status);
    });
    check("H6 MP3: /mp3 はページ・決まった名前のmp3だけ返す・他は404・メソッド違いは405", () -> {
      MusicHttp h = new MusicHttp(new PinPairing(new MemStore(), new Random(14)), new CommandQueue(10), new FakeFrames(), new FakeAssets());
      Map<String, String> H = hdr("host", "127.0.0.1:8787");
      MusicHttp.Response m = h.handle(req("GET", "/mp3", H, null), 0);
      eq(200, m.status); eq("<html>mp3</html>", str(m));
      MusicHttp.Response a = h.handle(req("GET", "/mp3/chime-1.mp3", H, null), 0);
      eq(200, a.status); eq("audio/mpeg", a.contentType);
      eq(404, h.handle(req("GET", "/mp3/../store.html", H, null), 0).status);
      eq(404, h.handle(req("GET", "/mp3/none.mp3", H, null), 0).status);
      eq(404, h.handle(req("GET", "/nothing", H, null), 0).status);
      eq(405, h.handle(req("POST", "/store", H, ""), 0).status);
      eq(405, h.handle(req("GET", "/api/pair", H, null), 0).status);
    });

    // ---------- MiniHttpServer（実ソケット） ----------
    check("N1 本物の通信で /store・操作・大きすぎる本文は413", () -> {
      PinPairing p = new PinPairing(new MemStore(), new Random(15));
      CommandQueue q = new CommandQueue(10);
      MusicHttp h = new MusicHttp(p, q, new FakeFrames(), new FakeAssets());
      MiniHttpServer srv = new MiniHttpServer(0, r -> h.handle(r, System.currentTimeMillis()));
      srv.start();
      try {
        int port = srv.port();
        ok(port > 0, "port");
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/store").openConnection();
        eq(200, c.getResponseCode());
        eq("<html>store</html>", new String(c.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        String pin = p.pin(System.currentTimeMillis());
        String tok = tokenOf(post(port, "/api/pair", "pin=" + pin, null, 200));
        post(port, "/api/command", "action=text&value=" + enc("音楽"), tok, 200);
        eq("音楽", q.drain().get(0).value);
        post(port, "/api/command", "action=text&value=" + "a".repeat(20_000), tok, 413);
      } finally { srv.stop(); }
    });
    check("N2 stop 後は接続できない", () -> {
      MiniHttpServer srv = new MiniHttpServer(0, r -> new MusicHttp(new PinPairing(new MemStore(), new Random(16)), new CommandQueue(1), new FakeFrames(), new FakeAssets()).handle(r, 0));
      srv.start(); int port = srv.port(); srv.stop();
      try {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/store").openConnection();
        c.setConnectTimeout(1500); c.getResponseCode();
        throw new AssertionError("つながった");
      } catch (IOException expected) { }
    });

    check("N3 ゆっくり送り続ける接続が8本あっても、正規の要求は4秒以内に返る（監査M2）", () -> {
      MusicHttp h = new MusicHttp(new PinPairing(new MemStore(), new Random(17)), new CommandQueue(10), new FakeFrames(), new FakeAssets());
      MiniHttpServer srv = new MiniHttpServer(0, r -> h.handle(r, 0));
      srv.start();
      List<Socket> slow = new ArrayList<>();
      Thread drip = null;
      try {
        int port = srv.port();
        for (int i = 0; i < 8; i++) {
          Socket s = new Socket("127.0.0.1", port);
          s.getOutputStream().write("GET /store HTTP/1.1\r\nHost: 127.0.0.1\r\n".getBytes(StandardCharsets.US_ASCII));
          slow.add(s);
        }
        drip = new Thread(() -> {
          try { for (int k = 0; k < 30; k++) { Thread.sleep(500); for (Socket s : slow) { try { s.getOutputStream().write('X'); } catch (IOException ignore) { } } } }
          catch (InterruptedException ignore) { }
        });
        drip.setDaemon(true); drip.start();
        Thread.sleep(300);
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/store").openConnection();
        c.setConnectTimeout(4000); c.setReadTimeout(4000);
        eq(200, c.getResponseCode());
        ok(System.currentTimeMillis() - t0 < 4000, "遅い: " + (System.currentTimeMillis() - t0) + "ms");
      } finally {
        if (drip != null) drip.interrupt();
        for (Socket s : slow) try { s.close(); } catch (IOException ignore) { }
        srv.stop();
      }
    });

    // ---------- Registration ----------
    check("R1 私設IPv4の判定", () -> {
      for (String s : new String[]{"192.168.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.254"}) ok(Registration.isPrivateIPv4(s), s);
      for (String s : new String[]{"8.8.8.8", "172.32.0.1", "127.0.0.1", "192.168.1", "192.168.1.256", "localhost", "", "fe80::1", "192.168.01.1"}) ok(!Registration.isPrivateIPv4(s), s);
      ok(!Registration.isPrivateIPv4(null), "null");
    });
    check("R2 署名は HMAC-SHA256(store|ip|port|device|ts) の16進小文字", () -> {
      byte[] key = new byte[32]; for (int i = 0; i < 32; i++) key[i] = (byte) i;
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] d = mac.doFinal("002|192.168.1.7|8787|devABCDEFGHIJKLMNOP|1700000000000".getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(); for (byte x : d) sb.append(String.format("%02x", x));
      eq(sb.toString(), Registration.sign(key, "002", "192.168.1.7", 8787, "devABCDEFGHIJKLMNOP", 1700000000000L));
    });
    check("R3 送信本文（初回は key 付き・2回目以降は key なし）", () -> {
      String b1 = Registration.formBody("002", "192.168.1.7", 8787, "devABCDEFGHIJKLMNOP", "KEYKEY", 1700000000000L, "ab");
      eq("mode=tvaddr&store=002&ip=192.168.1.7&port=8787&device=devABCDEFGHIJKLMNOP&ts=1700000000000&sig=ab&key=KEYKEY", b1);
      String b2 = Registration.formBody("002", "192.168.1.7", 8787, "devABCDEFGHIJKLMNOP", "", 1700000000000L, "ab");
      eq("mode=tvaddr&store=002&ip=192.168.1.7&port=8787&device=devABCDEFGHIJKLMNOP&ts=1700000000000&sig=ab", b2);
    });
    check("R4 店舗IDの取り出し（JSON・素の値・不正）", () -> {
      eq("002", Registration.storeId("{\"id\":\"002\",\"name\":\"サフラン\"}"));
      eq("002", Registration.storeId("\"{\\\"id\\\":\\\"002\\\",\\\"name\\\":\\\"x\\\"}\""));
      eq("002", Registration.storeId("002"));
      eq("", Registration.storeId(null)); eq("", Registration.storeId("null")); eq("", Registration.storeId(""));
      eq("", Registration.storeId("{\"id\":\"../x\"}")); eq("", Registration.storeId("12345678901"));
    });

    System.out.println((failed == 0 ? "ALL PASS " : "FAILED " + failed + " / ") + (passed + failed) + " checks");
    if (failed > 0) System.exit(1);
  }

  static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { throw new RuntimeException(e); } }
  static String post(int port, String path, String body, String token, int expect) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    c.setRequestMethod("POST"); c.setDoOutput(true);
    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    try { c.getOutputStream().write(b); } catch (IOException e) { if (expect == 413) return ""; throw e; }
    eq(expect, c.getResponseCode());
    InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
    return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
  }
}
