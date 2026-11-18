// 1.2.6: 「PCで探してテレビに映す」(cast)・ホームから自動で掲示に戻る機能のオン/オフ・起動時に音楽ページを開き直す、の純粋部分のテスト。
// 実行: javac -encoding UTF-8 -d <out> app/src/main/java/org/screenlite/webkiosk/data/AutoStart.java app/src/main/java/org/screenlite/webkiosk/data/MusicResume.java app/src/main/java/org/screenlite/webkiosk/music/CommandQueue.java tests/CastTest.java
//       java -cp <out> CastTest   （keiji-tv-kiosk フォルダで実行）
import org.screenlite.webkiosk.data.AutoStart;
import org.screenlite.webkiosk.data.MusicResume;
import org.screenlite.webkiosk.music.CommandQueue;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

public class CastTest {
  static int failed = 0, passed = 0;
  static void eq(String label, Object e, Object a) {
    if (Objects.equals(e, a)) { passed++; System.out.println("PASS " + label); }
    else { failed++; System.out.println("FAIL " + label + " expected=" + e + " actual=" + a); }
  }
  static boolean rejects(CommandQueue q, String body) {
    try { q.submit(body); return false; } catch (IllegalArgumentException e) { return true; }
  }
  static String enc(String s) throws Exception { return java.net.URLEncoder.encode(s, "UTF-8"); }
  static String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), "UTF-8"); }

  public static void main(String[] a) throws Exception {
    String S = "app/src/main/java/org/screenlite/webkiosk/";

    // ===== テレビに映す（cast）: YouTube の https リンクだけ受ける =====
    CommandQueue q = new CommandQueue(50);
    q.submit("action=cast&value=" + enc("https://www.youtube.com/watch?v=abc123&list=PLx"));
    q.submit("action=cast&value=" + enc("https://m.youtube.com/watch?v=abc123"));
    q.submit("action=cast&value=" + enc("https://music.youtube.com/watch?v=abc123"));
    q.submit("action=cast&value=" + enc("https://youtube.com/playlist?list=PLx"));
    q.submit("action=cast&value=" + enc("https://youtu.be/abc123"));
    List<CommandQueue.Command> got = q.drain();
    eq("cast を受け付ける", "cast", got.get(0).action);
    eq("cast のリンクがそのまま届く", "https://www.youtube.com/watch?v=abc123&list=PLx", got.get(0).value);
    eq("YouTube の5種類のホストを受ける", 5, got.size());
    eq("cast: http は拒否", true, rejects(q, "action=cast&value=" + enc("http://www.youtube.com/watch?v=abc")));
    eq("cast: 別サイトは拒否", true, rejects(q, "action=cast&value=" + enc("https://evil.example/watch?v=abc")));
    eq("cast: @ で偽装したホストは拒否", true, rejects(q, "action=cast&value=" + enc("https://www.youtube.com@evil.example/watch?v=abc")));
    eq("cast: 似た名前のホストは拒否", true, rejects(q, "action=cast&value=" + enc("https://www.youtube.com.evil.example/watch?v=abc")));
    eq("cast: 引数に YouTube を含むだけの別サイトは拒否", true, rejects(q, "action=cast&value=" + enc("https://evil.example/?u=https://www.youtube.com/watch?v=abc")));
    eq("cast: javascript: は拒否", true, rejects(q, "action=cast&value=" + enc("javascript:alert(1)")));
    eq("cast: 値なしは拒否", true, rejects(q, "action=cast"));
    eq("cast: 長すぎる(2048字超)は拒否", true, rejects(q, "action=cast&value=" + enc("https://www.youtube.com/watch?v=" + new String(new char[2100]).replace('\0', 'a'))));
    eq("cast: ポート付きは拒否", true, rejects(q, "action=cast&value=" + enc("https://www.youtube.com:8443/watch?v=abc")));
    String rv = read(S + "app/RemoteMusicView.java");
    eq("テレビ側で cast を開く処理がある", true, rv.contains("\"cast\""));
    // 監査の差し戻し: 直前に PC で操作していても、映した動画が止まっていたら自動で再生する（操作の時刻を消す）
    eq("cast のとき直前の操作の時刻を消す", true, java.util.regex.Pattern.compile("case \"cast\"[\\s\\S]{0,600}?lastUserCmd = -1").matcher(rv).find());

    // ===== ホームに出たとき自動で掲示に戻る: オン/オフ =====
    q.submit("action=autoReturn&value=on");
    q.submit("action=autoReturn&value=off");
    got = q.drain();
    eq("autoReturn on を受ける", "on", got.get(0).value);
    eq("autoReturn off を受ける", "off", got.get(1).value);
    eq("autoReturn: on/off 以外は拒否", true, rejects(q, "action=autoReturn&value=1"));
    eq("autoReturn: 値なしは拒否", true, rejects(q, "action=autoReturn"));
    // 見回り（数秒ごとの前面復帰）は設定に従う
    eq("オン・見えていない・休止なし → 戻す", true, AutoStart.shouldPatrolReturn(true, false, 1000, 0));
    eq("オフ・見えていない → 戻さない", false, AutoStart.shouldPatrolReturn(false, false, 1000, 0));
    eq("オン・見えている → 何もしない", false, AutoStart.shouldPatrolReturn(true, true, 1000, 0));
    eq("オン・設定のための休止中 → 戻さない", false, AutoStart.shouldPatrolReturn(true, false, 1000, 5000));
    // 電源オン（画面が点いたとき）はオフでも掲示を開く＝朝は必ず掲示が出る
    eq("オフでも電源オンでは戻す（既存の判定を使う）", true, AutoStart.shouldBringToFront(false, 1000, 0));
    eq("既定はオン", true, AutoStart.AUTO_RETURN_DEFAULT);
    // 監査の差し戻し: オフでも「起動してから掲示が一度見えるまで」は見回りを続ける（起動直後の1回が空振りしても掲示が出る）
    eq("オフ・まだ一度も掲示が見えていない → 見回りを続ける", true, AutoStart.patrolEnabled(false, false));
    eq("オフ・掲示が一度見えた → 見回りを止める", false, AutoStart.patrolEnabled(false, true));
    eq("オン・まだ見えていない → 見回る", true, AutoStart.patrolEnabled(true, false));
    eq("オン・一度見えた → 見回る", true, AutoStart.patrolEnabled(true, true));
    eq("見回りが「一度見えたか」を見ている", true, read(S + "service/StayOnTopService.kt").contains("patrolEnabled"));
    String sv = read(S + "service/StayOnTopService.kt");
    eq("見回りが設定を見ている", true, sv.contains("shouldPatrolReturn"));
    String st = read(S + "SettingsActivity.kt") + read(S + "MainActivity.kt");
    eq("テレビの設定画面にオン/オフがある（文言）", true, st.contains("自動で掲示に戻る"));
    String store = read("app/src/main/assets/store.html");
    eq("PC の操作画面にオン/オフがある", true, store.contains("autoReturn") && store.contains("自動で掲示に戻る"));
    eq("状態に autoReturn を載せる", true, rv.contains("autoReturn"));

    // ===== 起動直後にネットがまだ無くて音楽ページを開けなかったら、開き直す =====
    eq("開き直し 1回目は5秒後", 5000L, MusicResume.retryDelayMs(1));
    eq("開き直し 2回目は10秒後", 10000L, MusicResume.retryDelayMs(2));
    eq("開き直し 3回目は20秒後", 20000L, MusicResume.retryDelayMs(3));
    eq("開き直し 4回目は40秒後", 40000L, MusicResume.retryDelayMs(4));
    eq("開き直し 5回目以降は60秒ごと", 60000L, MusicResume.retryDelayMs(5));
    eq("開き直し 100回目も60秒", 60000L, MusicResume.retryDelayMs(100));
    eq("開き直し 0以下は5秒", 5000L, MusicResume.retryDelayMs(0));
    eq("ネットのページは開き直す", true, MusicResume.shouldRetry("https://m.youtube.com/watch?v=abc"));
    eq("停止（about:blank）は開き直さない", false, MusicResume.shouldRetry("about:blank"));
    eq("null は開き直さない", false, MusicResume.shouldRetry(null));
    eq("テレビ内のMP3ページも開き直す（サーバーの起動待ち）", true, MusicResume.shouldRetry("http://127.0.0.1:8787/mp3"));
    eq("http/https 以外は開き直さない", false, MusicResume.shouldRetry("chrome-error://chromewebdata/"));
    eq("開き直しがつながっている", true, rv.contains("retryDelayMs") && rv.contains("shouldRetry"));

    // ===== 1.2.5 の監査で残った軽い指摘 =====
    String um = read(S + "app/UpdateManager.kt");
    eq("インストール結果は自分の Session のものだけ受ける", true, um.contains("EXTRA_SESSION_ID"));

    // ===== プリセット（YouTube テスト曲・MP3）: 開いた直後の停止を「人が止めた」と見なさない（11-18） =====
    eq("P1: preset は操作時刻を記録する一覧に入れない", false,
        java.util.regex.Pattern.compile("case \"click\":[^\\n]*case \"preset\":[^\\n]*\\n\\s*lastUserCmd = SystemClock").matcher(rv).find());
    eq("P2: preset のとき直前の操作の時刻を消す", true,
        java.util.regex.Pattern.compile("case \"preset\":\\s*\\n[\\s\\S]{0,300}?lastUserCmd = -1").matcher(rv).find());

    // ===== 版 =====
    String g = read("app/build.gradle.kts");
    eq("版は 1011 / keiji-1.2.6", true, g.contains("versionCode = 1011") && g.contains("\"keiji-1.2.6\""));

    System.out.println(failed == 0 ? "ALL PASS (" + passed + ")" : failed + " FAILED / " + passed + " passed");
    if (failed > 0) System.exit(1);
  }
}
