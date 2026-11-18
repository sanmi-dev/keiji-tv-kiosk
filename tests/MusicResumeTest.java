// 起動時に「最後に流していた音楽ページ」へ戻す判定のテスト。Android に依存しない。
// 実行: javac -encoding UTF-8 -d <out> app/src/main/java/org/screenlite/webkiosk/data/*.java tests/MusicResumeTest.java
//       java -cp <out> MusicResumeTest
import org.screenlite.webkiosk.data.MusicResume;
import java.util.Objects;

public class MusicResumeTest {
  static int failed = 0, passed = 0;
  static void eq(String label, Object e, Object a) {
    if (Objects.equals(e, a)) { passed++; System.out.println("PASS " + label); }
    else { failed++; System.out.println("FAIL " + label + " expected=" + e + " actual=" + a); }
  }
  public static void main(String[] a) {
    String HOME = "https://www.youtube.com/watch?v=HOME";
    // 保存する値
    eq("https の YouTube はそのまま保存", "https://www.youtube.com/watch?v=A&list=L", MusicResume.toSaved("https://www.youtube.com/watch?v=A&list=L"));
    eq("テレビ内のMP3ページは mp3 として保存（ポートが変わっても戻れる）", "mp3", MusicResume.toSaved("http://127.0.0.1:8787/mp3"));
    eq("about:blank（停止）は保存しない", null, MusicResume.toSaved("about:blank"));
    eq("null は保存しない", null, MusicResume.toSaved(null));
    eq("http の外部サイトは保存しない", null, MusicResume.toSaved("http://example.com/"));
    eq("127.0.0.1 の /mp3 以外は保存しない", null, MusicResume.toSaved("http://127.0.0.1:8787/store"));
    eq("javascript: は保存しない", null, MusicResume.toSaved("javascript:alert(1)"));
    StringBuilder longUrl = new StringBuilder("https://www.youtube.com/watch?v=");
    for (int i = 0; i < 3000; i++) longUrl.append('x');
    eq("長すぎるURLは保存しない", null, MusicResume.toSaved(longUrl.toString()));
    // 起動時に開くページ
    // 1.2.6（Mizore 11-18「電源オンは MP3 に戻す」）: 保存の中身に関係なく起動は MP3
    eq("保存なしでも MP3", "http://127.0.0.1:8787/mp3", MusicResume.startUrl(null, HOME, 8787));
    eq("保存が mp3 なら今のポートのMP3ページ", "http://127.0.0.1:9000/mp3", MusicResume.startUrl("mp3", HOME, 9000));
    eq("保存が https でも MP3", "http://127.0.0.1:8787/mp3", MusicResume.startUrl("https://www.youtube.com/watch?v=A&list=L", HOME, 8787));
    eq("保存が壊れていても MP3", "http://127.0.0.1:8787/mp3", MusicResume.startUrl("http://evil/", HOME, 8787));
    System.out.println(failed == 0 ? "ALL PASS (" + passed + ")" : failed + " FAILED / " + passed + " passed");
    if (failed > 0) System.exit(1);
  }
}
