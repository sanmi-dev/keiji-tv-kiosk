// 音楽を鳴らす持ち主はアプリ全体で1つだけ（画面が2つできても音楽が二重に鳴らない）のテスト。Android に依存しない。
// 実行: javac -encoding UTF-8 -d <out> app/src/main/java/org/screenlite/webkiosk/music/SingleOwner.java tests/SingleOwnerTest.java
//       java -cp <out> SingleOwnerTest
import org.screenlite.webkiosk.music.SingleOwner;
import java.nio.file.*;
import java.util.Objects;

public class SingleOwnerTest {
  static int failed = 0, passed = 0;
  static void eq(String label, Object e, Object a) {
    if (Objects.equals(e, a)) { passed++; System.out.println("PASS " + label); }
    else { failed++; System.out.println("FAIL " + label + " expected=" + e + " actual=" + a); }
  }
  public static void main(String[] args) throws Exception {
    SingleOwner<String> o = new SingleOwner<>();
    eq("最初の持ち主: 前の持ち主はいない", null, o.claim("A"));
    eq("今の持ち主は A", "A", o.current());
    eq("2つ目の画面が来たら、前の持ち主 A を返す（A の音楽を止めるため）", "A", o.claim("B"));
    eq("今の持ち主は B", "B", o.current());
    eq("同じ B がもう一度名乗っても、前の持ち主は無し（自分を止めない）", null, o.claim("B"));
    o.release("A");
    eq("古い A が後から片付けても、B の持ち主は消えない", "B", o.current());
    o.release("B");
    eq("今の持ち主 B が片付けたら空になる", null, o.current());
    eq("空のあとに C が来たら前の持ち主は無し", null, o.claim("C"));

    // 掲示物テレビの画面は1つだけ（何から起動しても同じ画面を使う）
    String mf = new String(Files.readAllBytes(Paths.get("app/src/main/AndroidManifest.xml")), "UTF-8");
    int i = mf.indexOf("android:name=\".MainActivity\"");
    int j = mf.indexOf(">", i);
    String tag = i < 0 ? "" : mf.substring(i, j);
    eq("MainActivity は singleTask（画面が2つできない）", true, tag.contains("android:launchMode=\"singleTask\""));

    // 画面の組み立て（MainActivity.kt）で持ち主を名乗り、前の持ち主を止めている
    String ma = new String(Files.readAllBytes(Paths.get("app/src/main/java/org/screenlite/webkiosk/MainActivity.kt")), "UTF-8");
    eq("MainActivity.kt で SingleOwner を使って前の音楽を止める", true, ma.contains(".claim(") && ma.contains(".release("));
    System.out.println(failed == 0 ? "ALL PASS (" + passed + ")" : failed + " FAILED / " + passed + " passed");
    if (failed > 0) System.exit(1);
  }
}
