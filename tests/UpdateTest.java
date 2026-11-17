// 自動更新（新しい版の確認）と、店のPCからの「アップデート」・スクロールバー操作の純粋部分のテスト。Android に依存しない。
// 実行: javac -encoding UTF-8 -d <out> app/src/main/java/org/screenlite/webkiosk/data/UpdateCheck.java app/src/main/java/org/screenlite/webkiosk/music/CommandQueue.java tests/UpdateTest.java
//       java -cp <out> UpdateTest   （keiji-tv-kiosk フォルダで実行）
import org.screenlite.webkiosk.data.UpdateCheck;
import org.screenlite.webkiosk.music.CommandQueue;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

public class UpdateTest {
  static int failed = 0, passed = 0;
  static void eq(String label, Object e, Object a) {
    if (Objects.equals(e, a)) { passed++; System.out.println("PASS " + label); }
    else { failed++; System.out.println("FAIL " + label + " expected=" + e + " actual=" + a); }
  }
  static boolean rejects(CommandQueue q, String body) {
    try { q.submit(body); return false; } catch (IllegalArgumentException e) { return true; }
  }
  static String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), "UTF-8"); }

  public static void main(String[] a) throws Exception {
    // ===== 版の確認先（公開 Release の latest に apk と version.json を置く） =====
    eq("版の確認先", "https://github.com/sanmi-dev/keiji-tv-kiosk/releases/latest/download/version.json", UpdateCheck.VERSION_URL);
    eq("apk の取得先", "https://github.com/sanmi-dev/keiji-tv-kiosk/releases/latest/download/keiji-tv.apk", UpdateCheck.APK_URL);
    eq("見に行く間隔は3時間", 3L * 60 * 60 * 1000, UpdateCheck.INTERVAL_MS);
    // ===== version.json の読み取り =====
    eq("versionCode を読む", 1010, UpdateCheck.parseVersionCode("{\"versionCode\":1010,\"versionName\":\"keiji-1.2.5\"}"));
    eq("空白・改行があっても読む", 1010, UpdateCheck.parseVersionCode("{\n  \"versionName\": \"x\",\n  \"versionCode\" : 1010\n}"));
    eq("壊れていたら -1", -1, UpdateCheck.parseVersionCode("<html>404</html>"));
    eq("null は -1", -1, UpdateCheck.parseVersionCode(null));
    eq("負の数は -1", -1, UpdateCheck.parseVersionCode("{\"versionCode\":-5}"));
    eq("大きすぎる（10桁超）は -1", -1, UpdateCheck.parseVersionCode("{\"versionCode\":99999999999}"));
    eq("文字列の数字も読む", 1010, UpdateCheck.parseVersionCode("{\"versionCode\":\"1010\"}"));
    // ===== 新しいかどうか =====
    eq("公開版が新しい → 更新あり", true, UpdateCheck.isNewer(1010, 1009));
    eq("同じ版 → 更新なし", false, UpdateCheck.isNewer(1009, 1009));
    eq("公開版が古い → 更新なし（戻さない）", false, UpdateCheck.isNewer(1008, 1009));
    eq("読めなかった(-1) → 更新なし", false, UpdateCheck.isNewer(-1, 1009));

    // ===== 店のPCからの操作: アップデート確認・スクロールバー =====
    CommandQueue q = new CommandQueue(50);
    q.submit("action=checkUpdate");
    q.submit("action=scrollTo&y=0.5");
    q.submit("action=scrollTo&y=0");
    q.submit("action=scrollTo&y=1");
    List<CommandQueue.Command> got = q.drain();
    eq("checkUpdate を受け付ける", "checkUpdate", got.get(0).action);
    eq("scrollTo を受け付ける（位置は0〜1）", "scrollTo", got.get(1).action);
    eq("scrollTo の位置", 0.5, got.get(1).y);
    eq("scrollTo 0 と 1 も受け付ける", 3, (int) got.stream().filter(c -> c.action.equals("scrollTo")).count());
    eq("scrollTo の範囲外は拒否", true, rejects(q, "action=scrollTo&y=1.5"));
    eq("scrollTo の数字でないものは拒否", true, rejects(q, "action=scrollTo&y=abc"));
    eq("scrollTo の位置なしは拒否", true, rejects(q, "action=scrollTo"));

    // ===== 店のPCの操作画面（テレビ内の store.html） =====
    String store = read("app/src/main/assets/store.html");
    eq("操作画面に「アップデート」ボタンがある", true, store.contains("checkUpdate") && store.contains("アップデート"));
    eq("操作画面にスクロールバーがある（scrollTo を送る）", true, store.contains("scrollTo") && store.contains("id=\"scrollbar\""));
    eq("スクロールバーに上下のボタンがある", true, store.contains("▲") && store.contains("▼"));

    // ===== GitHub Actions が version.json を公開する =====
    String wf = read(".github/workflows/build.yml");
    eq("Actions が version.json を作って Release に上げる", true, wf.contains("version.json") && wf.contains("versionCode"));

    // ===== テレビ側の部品がつながっている =====
    String mf = read("app/src/main/AndroidManifest.xml");
    eq("インストールの依頼に必要な権限 REQUEST_INSTALL_PACKAGES", true, mf.contains("android.permission.REQUEST_INSTALL_PACKAGES"));
    String ma = read("app/src/main/java/org/screenlite/webkiosk/MainActivity.kt");
    eq("掲示の手前に更新のポップアップを出す（文言）", true, ma.contains("新しい版があります"));

    // ===== 監査の差し戻し（1.2.5）: 押せない・消せないポップアップを残さない =====
    // ① 決定キーはフォーカスに頼らず受ける
    eq("決定キー: リモコンの決定(23)", true, UpdateCheck.isConfirmKey(23));
    eq("決定キー: Enter(66)", true, UpdateCheck.isConfirmKey(66));
    eq("決定キー: テンキーの Enter(160)", true, UpdateCheck.isConfirmKey(160));
    eq("決定キーでない: 戻る(4)", false, UpdateCheck.isConfirmKey(4));
    eq("決定キーでない: 下(20)", false, UpdateCheck.isConfirmKey(20));
    eq("ポップアップ中の決定キーを画面全体で受ける", true, ma.contains("dispatchKeyEvent") && ma.contains("isConfirmKey"));
    // ② 続けて失敗したら引っ込める
    eq("失敗は3回まで", 3, UpdateCheck.MAX_INSTALL_FAILS);
    eq("失敗0回 → 出したまま", false, UpdateCheck.shouldHideAfterFailures(0));
    eq("失敗2回 → 出したまま", false, UpdateCheck.shouldHideAfterFailures(2));
    eq("失敗3回 → 引っ込める", true, UpdateCheck.shouldHideAfterFailures(3));
    eq("失敗4回 → 引っ込める", true, UpdateCheck.shouldHideAfterFailures(4));
    String um = read("app/src/main/java/org/screenlite/webkiosk/app/UpdateManager.kt");
    eq("失敗を数えて引っ込める処理がつながっている", true, um.contains("shouldHideAfterFailures"));
    // ③ 失敗時の後始末
    eq("失敗したら OS 側の作業領域を捨てる", true, um.contains("abandonSession"));
    eq("一時 apk を消す", true, um.contains(".delete()"));
    // 転送先は https だけ
    eq("https は可", true, UpdateCheck.isHttps("https://objects.githubusercontent.com/x"));
    eq("大文字の HTTPS も可", true, UpdateCheck.isHttps("HTTPS://example.com/x"));
    eq("http は不可", false, UpdateCheck.isHttps("http://example.com/x"));
    eq("file は不可", false, UpdateCheck.isHttps("file:///sdcard/x.apk"));
    eq("null は不可", false, UpdateCheck.isHttps(null));
    eq("https で始まるだけの別物は不可", false, UpdateCheck.isHttps("httpsx://example.com"));
    eq("転送先の検査がつながっている", true, um.contains("isHttps"));
    // 大きさの上限
    eq("version.json は 64KB まで", 64L * 1024, UpdateCheck.MAX_JSON_BYTES);
    eq("apk は 100MB まで", 100L * 1024 * 1024, UpdateCheck.MAX_APK_BYTES);
    eq("上限ちょうどは可", true, UpdateCheck.withinLimit(64L * 1024, UpdateCheck.MAX_JSON_BYTES));
    eq("上限を1バイト超えたら不可", false, UpdateCheck.withinLimit(64L * 1024 + 1, UpdateCheck.MAX_JSON_BYTES));
    eq("負の大きさは不可", false, UpdateCheck.withinLimit(-1, UpdateCheck.MAX_JSON_BYTES));
    eq("上限の検査がつながっている", true, um.contains("MAX_JSON_BYTES") && um.contains("MAX_APK_BYTES"));
    // 確認画面を開いたあとの待ち（結果が来なければ4分で押せるように戻す）
    eq("結果待ちは4分", 4L * 60 * 1000, UpdateCheck.WAIT_RESULT_MS);
    eq("結果待ちの時間がつながっている", true, um.contains("WAIT_RESULT_MS"));
    // 落としてきた apk が本当に新しいか（version.json だけ先に新しくなる瞬間の対策）
    eq("落とした apk の版を確かめる", true, um.contains("getPackageArchiveInfo"));
    // Actions: 版が読めなければ失敗させる
    eq("Actions: 版が空なら止める", true, wf.contains("test -n \"$VC\""));
    // 古い WebView でもスクロールが効く
    String rv = read("app/src/main/java/org/screenlite/webkiosk/app/RemoteMusicView.java");
    eq("スクロールの予備（window.scrollBy / window.scrollTo）", true, rv.contains("window.scrollBy") && rv.contains("window.scrollTo"));

    System.out.println(failed == 0 ? "ALL PASS (" + passed + ")" : failed + " FAILED / " + passed + " passed");
    if (failed > 0) System.exit(1);
  }
}
