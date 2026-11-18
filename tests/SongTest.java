// 1.2.6: PC から送った MP3 の保存・一覧・送信の受け口、電源オンは MP3、操作画面の2モード（Mizore 承認済みモック）のテスト。
// 実行: kt-test.sh SongTest app/src/main/java/org/screenlite/webkiosk/data/StartupConfig.java app/src/main/java/org/screenlite/webkiosk/data/MusicResume.java app/src/main/java/org/screenlite/webkiosk/music/*.java
import org.screenlite.webkiosk.data.MusicResume;
import org.screenlite.webkiosk.music.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class SongTest {
  static int failed = 0, passed = 0;
  interface Body { void run() throws Exception; }
  static void check(String label, Body b) {
    try { b.run(); passed++; System.out.println("PASS " + label); }
    catch (Throwable t) { failed++; System.out.println("FAIL " + label + "  -> " + t); }
  }
  static void ok(boolean c, String m) { if (!c) throw new AssertionError(m); }
  static void eq(Object e, Object a) { if (!Objects.equals(e, a)) throw new AssertionError("expected=" + e + " actual=" + a); }
  static String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), "UTF-8"); }
  static File tmpDir() throws IOException { return Files.createTempDirectory("songs").toFile(); }
  static byte[] mp3(int n) { byte[] b = new byte[n]; b[0] = 'I'; b[1] = 'D'; b[2] = '3'; return b; }
  static SongLibrary.Result save(SongLibrary lib, String name, byte[] b) throws IOException {
    return lib.save(name, new ByteArrayInputStream(b), b.length);
  }
  static int filesIn(File d) { File[] f = d.listFiles(x -> x.getName().endsWith(".mp3") || x.getName().endsWith(".part")); return f == null ? 0 : f.length; }

  static final class MemStore implements PinPairing.TokenStore {
    List<String> saved = new ArrayList<>();
    public List<String> load() { return new ArrayList<>(saved); }
    public void save(List<String> t) { saved = new ArrayList<>(t); }
  }
  static final class FakeFrames implements MusicHttp.Frames {
    public byte[] latestJpeg() { return null; }
    public String stateJson() { return "{}"; }
  }
  static final class FakeAssets implements MusicHttp.Assets {
    public byte[] get(String name) { return name.equals("mp3.html") ? "<html>mp3</html>".getBytes(StandardCharsets.UTF_8) : null; }
  }

  public static void main(String[] a) throws Exception {
    // ===== S1 電源オンはいつも MP3 =====
    check("S1 前回 YouTube でも起動は MP3", () -> eq("http://127.0.0.1:8787/mp3", MusicResume.startUrl("https://www.youtube.com/watch?v=x", "https://www.youtube.com/", 8787)));
    check("S1 保存なし・壊れていても MP3", () -> {
      eq("http://127.0.0.1:8790/mp3", MusicResume.startUrl(null, "https://www.youtube.com/", 8790));
      eq("http://127.0.0.1:8787/mp3", MusicResume.startUrl("%%壊れ", "https://www.youtube.com/", 8787));
    });

    // ===== S2 名前・中身の判定 =====
    check("S2 上限は1曲30MB・合計1GB", () -> { eq(30L * 1024 * 1024, SongLibrary.MAX_SONG_BYTES); eq(1024L * 1024 * 1024, SongLibrary.MAX_TOTAL_BYTES); });
    check("S2 名前からフォルダ記号・制御文字を除く", () -> {
      String n = SongLibrary.cleanName("..\\a/b<>:\"|?*\u0001曲.mp3");
      ok(n != null && n.endsWith(".mp3"), "mp3で終わる: " + n);
      ok(!n.contains("/") && !n.contains("\\") && !n.contains("..") && !n.contains("\u0001"), "危ない文字なし: " + n);
    });
    check("S2 mp3 以外の名前は不可", () -> { eq(null, SongLibrary.cleanName("a.wav")); eq(null, SongLibrary.cleanName("")); eq(null, SongLibrary.cleanName(null)); });
    check("S2 大文字 .MP3 は可", () -> ok(SongLibrary.cleanName("A.MP3") != null, "MP3"));
    check("S2 長すぎる名前は縮める（80字以内）", () -> ok(SongLibrary.cleanName("あ".repeat(300) + ".mp3").length() <= 80, "len"));
    check("S2 同じ名前は (2)(3) を付ける", () -> {
      eq("曲(2).mp3", SongLibrary.uniqueName("曲.mp3", Arrays.asList("曲.mp3")));
      eq("曲(3).mp3", SongLibrary.uniqueName("曲.mp3", Arrays.asList("曲.mp3", "曲(2).mp3")));
      eq("新.mp3", SongLibrary.uniqueName("新.mp3", Arrays.asList("曲.mp3")));
    });
    check("S2 中身が MP3 か（ID3 / フレーム同期）", () -> {
      ok(SongLibrary.looksLikeMp3(new byte[]{'I', 'D', '3', 3}), "ID3");
      ok(SongLibrary.looksLikeMp3(new byte[]{(byte) 0xFF, (byte) 0xFB, 0, 0}), "FFFB");
      ok(!SongLibrary.looksLikeMp3("<html>".getBytes()), "html");
      ok(!SongLibrary.looksLikeMp3(new byte[]{1}), "短い");
      ok(!SongLibrary.looksLikeMp3(null), "null");
    });

    // ===== S2/S3 保存・一覧・順番・外す =====
    check("S2 保存すると一覧の最後に入る・ファイルは内部の名前", () -> {
      File d = tmpDir(); SongLibrary lib = new SongLibrary(d);
      eq("ok", save(lib, "朝.mp3", mp3(100)).code);
      eq("ok", save(lib, "昼.mp3", mp3(100)).code);
      List<SongLibrary.Song> l = lib.list();
      eq(2, l.size()); eq("朝.mp3", l.get(0).name); eq("昼.mp3", l.get(1).name);
      ok(l.get(0).id.matches("[a-z0-9]{1,24}"), "id: " + l.get(0).id);
      eq(2, filesIn(d));
    });
    check("S2 同じ名前で送ると別名で残す（上書きしない）", () -> {
      SongLibrary lib = new SongLibrary(tmpDir());
      save(lib, "朝.mp3", mp3(10)); SongLibrary.Result r = save(lib, "朝.mp3", mp3(20));
      eq("ok", r.code); eq("朝(2).mp3", lib.list().get(1).name);
    });
    check("S2 MP3 でない中身は断り、何も残さない", () -> {
      File d = tmpDir(); SongLibrary lib = new SongLibrary(d);
      eq("not_mp3", save(lib, "x.mp3", "<html>nope</html>".getBytes()).code);
      eq(0, lib.list().size()); eq(0, filesIn(d));
    });
    check("S2 名前が mp3 でなければ断る", () -> eq("bad_name", save(new SongLibrary(tmpDir()), "x.wav", mp3(10)).code));
    check("S2 1曲の上限を超えたら読まずに断る", () -> {
      File d = tmpDir(); SongLibrary lib = new SongLibrary(d, 50, 1000);
      eq("too_large", save(lib, "x.mp3", mp3(51)).code); eq(0, filesIn(d));
    });
    check("S2 合計の上限を超えたら断る", () -> {
      File d = tmpDir(); SongLibrary lib = new SongLibrary(d, 50, 80);
      eq("ok", save(lib, "a.mp3", mp3(50)).code);
      eq("full", save(lib, "b.mp3", mp3(40)).code); eq(1, lib.list().size()); eq(1, filesIn(d));
    });
    check("S2 送信が途中で切れたら書きかけを残さない", () -> {
      File d = tmpDir(); SongLibrary lib = new SongLibrary(d);
      SongLibrary.Result r = lib.save("x.mp3", new ByteArrayInputStream(mp3(10)), 100);
      eq("broken", r.code); eq(0, lib.list().size()); eq(0, filesIn(d));
    });
    check("S3 順番の入れ替え（上へ・下へ・端は動かない）", () -> {
      SongLibrary lib = new SongLibrary(tmpDir());
      save(lib, "1.mp3", mp3(10)); save(lib, "2.mp3", mp3(10)); save(lib, "3.mp3", mp3(10));
      String id3 = lib.list().get(2).id, id1 = lib.list().get(0).id;
      ok(lib.move(id3, -1), "up"); eq("3.mp3", lib.list().get(1).name);
      ok(!lib.move(id1, -1), "先頭は上へ行けない");
      ok(lib.move(id1, 1), "down"); eq("1.mp3", lib.list().get(1).name);
      ok(!lib.move("nope", 1), "無い id");
    });
    check("S3 外すとテレビのファイルも消える", () -> {
      File d = tmpDir(); SongLibrary lib = new SongLibrary(d);
      save(lib, "1.mp3", mp3(10)); save(lib, "2.mp3", mp3(10));
      ok(lib.remove(lib.list().get(0).id), "remove"); eq(1, lib.list().size()); eq(1, filesIn(d));
      ok(!lib.remove("nope"), "無い id");
    });
    check("S3 電源を切っても一覧と順番が残る（作り直して読める）", () -> {
      File d = tmpDir(); SongLibrary lib = new SongLibrary(d);
      save(lib, "1.mp3", mp3(10)); save(lib, "2.mp3", mp3(10)); lib.move(lib.list().get(1).id, -1);
      List<SongLibrary.Song> l = new SongLibrary(d).list();
      eq(2, l.size()); eq("2.mp3", l.get(0).name);
    });
    check("S3 ファイルの取り出しは一覧にある id だけ（../ は不可）", () -> {
      SongLibrary lib = new SongLibrary(tmpDir()); save(lib, "1.mp3", mp3(10));
      ok(lib.file(lib.list().get(0).id) != null, "ある");
      eq(null, lib.file("../songs")); eq(null, lib.file("nope")); eq(null, lib.file(null));
    });
    check("S3 一覧の JSON に曲と空き容量（名前は JSON として安全）", () -> {
      SongLibrary lib = new SongLibrary(tmpDir()); save(lib, "a\"b.mp3", mp3(10));
      String j = lib.toJson();
      ok(j.contains("\"songs\"") && j.contains("\"free\""), j);
      ok(j.contains("a\\\"b.mp3"), "エスケープ: " + j);
    });

    // ===== 監査 1回目の差し戻し =====
    check("A1(M1) ゆっくり送られている間も一覧はすぐ読める（受信中に一覧の鍵を握らない）", () -> {
      SongLibrary lib = new SongLibrary(tmpDir());
      save(lib, "先.mp3", mp3(10));
      InputStream slow = new InputStream() {
        int n = 0;
        public int read() throws IOException {
          if (n == 0) { n++; return 'I'; } if (n == 1) { n++; return 'D'; } if (n == 2) { n++; return '3'; }
          try { Thread.sleep(3000); } catch (InterruptedException e) { throw new IOException(e); }
          return -1;
        }
      };
      Thread t = new Thread(() -> { try { lib.save("遅.mp3", slow, 100); } catch (Exception ignored) { } });
      t.start(); Thread.sleep(300);
      long t0 = System.currentTimeMillis(); int n = lib.list().size(); long ms = System.currentTimeMillis() - t0;
      t.join();
      ok(ms < 1000, "一覧に " + ms + "ms かかった"); eq(1, n);
    });
    check("A2(m2) 起動時に一覧にない書きかけ・はぐれファイルを掃除", () -> {
      File d = tmpDir(); SongLibrary lib = new SongLibrary(d); save(lib, "1.mp3", mp3(10));
      new FileOutputStream(new File(d, "abc.part")).close();
      new FileOutputStream(new File(d, "zzz.mp3")).close();
      new SongLibrary(d);
      eq(1, filesIn(d));
    });
    String mini = read("app/src/main/java/org/screenlite/webkiosk/music/MiniHttpServer.java");
    check("A3(M2) ファイル応答に書き込みの期限と同時本数の上限がある", () -> ok(mini.contains("WRITE_DEADLINE") && mini.contains("Semaphore"), "write deadline"));
    String mp3src = read("app/src/main/assets/mp3.html");
    check("A4(m3) MP3 ページは一周ごとに一覧を取り直し、全部失敗なら同梱曲へ", () -> {
      int c = 0, i = -1; while ((i = mp3src.indexOf("loadList(", i + 1)) >= 0) c++;
      ok(c >= 2, "loadList の呼び出しが " + c + " 回"); ok(mp3src.contains("allFailed"), "allFailed");
    });

    // ===== S2/S3 HTTP の受け口 =====
    PinPairing p = new PinPairing(new MemStore(), new Random(21));
    File d = tmpDir(); SongLibrary lib = new SongLibrary(d);
    MusicHttp h = new MusicHttp(p, new CommandQueue(10), new FakeFrames(), new FakeAssets());
    h.setSongs(lib);
    MiniHttpServer srv = new MiniHttpServer(0, r -> h.handle(r, System.currentTimeMillis()), (r, in, len) -> h.upload(r, in, len));
    srv.start();
    try {
      int port = srv.port();
      String tok = tokenOf(post(port, "/api/pair", "pin=" + p.pin(System.currentTimeMillis()), null));
      check("H1 通行証なしの送信は 401 で何も残さない", () -> {
        eq(401, put(port, "a.mp3", mp3(1000), null, "127.0.0.1"));
        eq(0, lib.list().size()); eq(0, filesIn(d));
      });
      check("H2 Host がおかしい送信は 403", () -> eq(403, put(port, "a.mp3", mp3(1000), tok, "evil.example")));
      check("H3 通行証ありで 1MB を送ると保存される（大きな本文も受け取れる）", () -> {
        eq(200, put(port, "大きい曲.mp3", mp3(1024 * 1024), tok, "127.0.0.1"));
        eq(1, lib.list().size()); eq("大きい曲.mp3", lib.list().get(0).name);
      });
      check("H4 一覧は通行証が要る（GET /api/songs）", () -> {
        eq(401, get(port, "/api/songs", null).code);
        Resp r = get(port, "/api/songs", tok); eq(200, r.code); ok(r.body.contains("大きい曲.mp3"), r.body);
      });
      check("H5 テレビ内の MP3 ページ用の一覧（/mp3/list）と曲の取り出し", () -> {
        Resp r = get(port, "/mp3/list", null); eq(200, r.code); ok(r.body.contains(lib.list().get(0).id), r.body);
        Resp f = get(port, "/mp3/s/" + lib.list().get(0).id, null); eq(200, f.code); eq(1024 * 1024, f.len);
        eq(404, get(port, "/mp3/s/nope", null).code);
      });
      check("H6 順番と外すは通行証が要る", () -> {
        String id = lib.list().get(0).id;
        eq(401, postCode(port, "/api/songs/remove", "id=" + id, null));
        eq(200, postCode(port, "/api/songs/remove", "id=" + id, tok)); eq(0, lib.list().size());
        eq(400, postCode(port, "/api/songs/move", "id=nope&dir=up", tok));
      });
      check("A5(m1) 通行証なしの送信は本文を待たずに 401、送信枠もふさがない", () -> {
        try (Socket s = new Socket("127.0.0.1", port)) {
          s.setSoTimeout(3000);
          s.getOutputStream().write(("PUT /api/song HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Song-Name: a.mp3\r\nContent-Length: 1000\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
          eq(401, status(s));
          eq(200, put(port, "枠.mp3", mp3(1000), tok, "127.0.0.1"));
        }
        lib.remove(lib.list().get(lib.list().size() - 1).id);
      });
      check("H7 30MB 超を名乗る送信は 413（受け取らない）", () -> eq(413, putHead(port, "x.mp3", SongLibrary.MAX_SONG_BYTES + 1, tok)));
    } finally { srv.stop(); }

    // ===== 操作画面・MP3 ページ・キー =====
    String store = read("app/src/main/assets/store.html");
    String mp3p = read("app/src/main/assets/mp3.html");
    String rv = read("app/src/main/java/org/screenlite/webkiosk/app/RemoteMusicView.java");
    check("M1 MP3 ページは送った曲の一覧を読む・空なら同梱曲", () -> ok(mp3p.contains("/mp3/list") && mp3p.contains("morning-light.mp3"), "list"));
    check("B1 基本モードとブラウザモードがある", () -> ok(store.contains("id=\"browserMode\"") && store.contains("ブラウザモードで操作する") && store.contains("基本モードに戻る"), "modes"));
    check("B2 曲を送る（PUT /api/song）・一覧（/api/songs）・ドラッグ", () -> ok(store.contains("/api/song") && store.contains("/api/songs") && store.contains("drop"), "upload"));
    check("B3 ブラウザモードの右端に ⤒ ▲ ▼ ⤓", () -> ok(store.contains("⤒") && store.contains("⤓"), "sb"));
    check("B4 キーボードをそのまま送る（keydown）・日本語は確定後（compositionend）", () -> ok(store.contains("keydown") && store.contains("compositionend") && store.contains("isComposing"), "kb"));
    check("B5 Esc 長押しで解除", () -> ok(store.contains("Esc") && store.contains("長押し"), "esc"));
    check("B6 配色は緑", () -> ok(store.contains("#2a6f5f"), "green"));
    check("K1 PageUp/PageDown/Home/End/Delete を送れる", () -> {
      CommandQueue q = new CommandQueue(10);
      for (String k : new String[]{"PageUp", "PageDown", "Home", "End", "Delete"}) q.submit("action=key&value=" + k);
      eq(5, q.drain().size());
    });
    check("K2 テレビ側でそのキーを押す", () -> ok(rv.contains("KEYCODE_PAGE_UP") && rv.contains("KEYCODE_PAGE_DOWN") && rv.contains("KEYCODE_MOVE_HOME") && rv.contains("KEYCODE_MOVE_END") && rv.contains("KEYCODE_FORWARD_DEL"), "keys"));

    System.out.println(failed == 0 ? "ALL PASS (" + passed + ")" : failed + " FAILED / " + passed + " passed");
    if (failed > 0) System.exit(1);
  }

  // ---- 通信の小道具 ----
  static final class Resp { int code; String body = ""; int len; }
  static String tokenOf(String json) { int i = json.indexOf("\"token\":\""); return json.substring(i + 9, json.indexOf('"', i + 9)); }
  static String post(int port, String path, String body, String tok) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    c.setRequestMethod("POST"); c.setDoOutput(true);
    if (tok != null) c.setRequestProperty("Authorization", "Bearer " + tok);
    c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
    return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
  }
  static int postCode(int port, String path, String body, String tok) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    c.setRequestMethod("POST"); c.setDoOutput(true);
    if (tok != null) c.setRequestProperty("Authorization", "Bearer " + tok);
    c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    return c.getResponseCode();
  }
  static Resp get(int port, String path, String tok) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
    if (tok != null) c.setRequestProperty("Authorization", "Bearer " + tok);
    Resp r = new Resp(); r.code = c.getResponseCode();
    InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
    byte[] b = in == null ? new byte[0] : in.readAllBytes();
    r.len = b.length; r.body = new String(b, StandardCharsets.UTF_8); return r;
  }
  /** 生のソケットで PUT /api/song（Host を自由に付けるため）。 */
  static int put(int port, String name, byte[] body, String tok, String host) throws IOException {
    try (Socket s = new Socket("127.0.0.1", port)) {
      s.setSoTimeout(20000);
      OutputStream o = s.getOutputStream();
      String head = "PUT /api/song HTTP/1.1\r\nHost: " + host + "\r\n" + (tok != null ? "Authorization: Bearer " + tok + "\r\n" : "")
          + "X-Song-Name: " + URLEncoder.encode(name, "UTF-8") + "\r\nContent-Length: " + body.length + "\r\n\r\n";
      o.write(head.getBytes(StandardCharsets.US_ASCII));
      try { o.write(body); o.flush(); } catch (IOException ignored) { }
      return status(s);
    }
  }
  static int putHead(int port, String name, long len, String tok) throws IOException {
    try (Socket s = new Socket("127.0.0.1", port)) {
      s.setSoTimeout(10000);
      String head = "PUT /api/song HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + tok + "\r\nX-Song-Name: " + name + "\r\nContent-Length: " + len + "\r\n\r\n";
      s.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
      return status(s);
    }
  }
  static int status(Socket s) throws IOException {
    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
    String line = br.readLine();
    if (line == null) return -1;
    return Integer.parseInt(line.split(" ")[1]);
  }
}
