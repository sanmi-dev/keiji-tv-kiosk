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

    // ===== 1.2.6 Fable レビュー対応 =====
    String ka = read("app/src/main/java/org/screenlite/webkiosk/app/KioskApplication.kt");
    // MAJOR-1 更新確認はアプリ全体の起動で始める（掲示画面が落ちる版でも動く）
    eq("M1: KioskApplication.onCreate で更新確認を始める", true, ka.contains("UpdateManager.start("));
    eq("M1: MainActivity では始めない", false, ma.contains("UpdateManager.start("));
    // MAJOR-2 重ねて表示が未許可なら「開く」を押す案内
    eq("M2: 未許可なら案内文", true, UpdateCheck.overlayNote(false).contains("『開く』"));
    eq("M2: 許可済みなら案内なし", "", UpdateCheck.overlayNote(true));
    eq("M2: install で重ねて表示の許可を見る", true, um.contains("canDrawOverlays") && um.contains("overlayNote"));
    // MAJOR-3 設定を開く前に「更新待ち」を保存、起動時は早めに確認
    eq("M3: 更新待ちなら5秒後に確認", 5_000L, UpdateCheck.firstDelayMs(true));
    eq("M3: 通常は60秒後", 60_000L, UpdateCheck.firstDelayMs(false));
    eq("M3: 更新待ちを SharedPreferences に保存", true, um.contains("getSharedPreferences") && um.contains("UpdateCheck.PREF_PENDING"));
    eq("M3: 起動時に更新待ちを読んで初回の待ちを決める", true, um.contains("firstDelayMs("));
    // MAJOR-4 全画面の暗幕をやめ、下端の帯にする
    eq("M4: 全画面の暗幕(0x99000000)なし", false, ma.contains("0x99000000"));
    eq("M4: ポップアップは下端に寄せる", true, java.util.regex.Pattern.compile("UpdatePopup[\\s\\S]{0,1500}?BottomCenter").matcher(ma).find());
    // MINOR④ ダウンロード全体の締め切り10分
    eq("m4: 全体の締め切り10分", 10L * 60 * 1000, UpdateCheck.DOWNLOAD_TOTAL_MS);
    eq("m4: 10分ちょうどまでは続ける", false, UpdateCheck.downloadExpired(0, 10L * 60 * 1000));
    eq("m4: 10分を超えたら打ち切る", true, UpdateCheck.downloadExpired(0, 10L * 60 * 1000 + 1));
    eq("m4: 締め切りが download でつながっている", true, um.contains("downloadExpired("));
    // MINOR③ 暗転中の決定キーは暗転を解くだけ
    eq("m3: 暗転中かを onUserInteraction の前に取る", true,
        java.util.regex.Pattern.compile("dispatchKeyEvent[\\s\\S]{0,200}?isIdleMode\\.value[\\s\\S]{0,200}?onUserInteraction").matcher(ma).find());
    eq("m3: 暗転中は install しない判定", true, UpdateCheck.shouldInstallOnKey(false) && !UpdateCheck.shouldInstallOnKey(true));
    eq("m3: 判定が MainActivity でつながっている", true, ma.contains("shouldInstallOnKey("));
    // MINOR⑤ apk が読めないときは「壊れています」
    eq("m5: 壊れた apk の文言", true, UpdateCheck.MSG_BROKEN_APK.contains("壊れて"));
    eq("m5: 読めない apk は壊れている扱い", true, um.contains("MSG_BROKEN_APK"));

    // ===== アップデートボタンの結果をはっきり伝える（Mizore「静かに終わる」11-18） =====
    eq("U1: 曖昧な「新しい版があれば」を出さない", false, store.contains("新しい版があれば"));
    eq("U2: 押した直後に「確かめています」", true, store.contains("テレビに確かめています"));
    eq("U3: 結果を目立つ行(#msg)にも出す", true,
        java.util.regex.Pattern.compile("function showUpdate[\\s\\S]{0,1500}?\\$\\('msg'\\)\\.textContent").matcher(store).find());
    eq("U4: 結果をお知らせ(トースト)でも出す", true,
        java.util.regex.Pattern.compile("function showUpdate[\\s\\S]{0,1500}?toast\\(").matcher(store).find() && store.contains("id=\"toast\""));
    eq("U5: 最新ならいまの版を添える", true, store.contains("最新です（いまの版 "));
    eq("U6: 新しい版の番号を添える", true, store.contains("新しい版があります（版 "));
    String rv2 = read("app/src/main/java/org/screenlite/webkiosk/app/RemoteMusicView.java");
    eq("U7: テレビが新しい版の番号を状態に載せる", true, rv2.contains(".put(\"newer\", UpdateManager.getNewerCode()"));
    eq("U9: テレビが応答しなくても40秒で「まだ届きません」を出す（状態の取得に頼らない）", true,
        java.util.regex.Pattern.compile("updWaiting = Date\\.now\\(\\);[\\s\\S]{0,300}?setTimeout\\(").matcher(store).find());
    eq("U8: UpdateManager が番号を返す", true, um.contains("fun getNewerCode()"));

    System.out.println(failed == 0 ? "ALL PASS (" + passed + ")" : failed + " FAILED / " + passed + " passed");
    if (failed > 0) System.exit(1);
  }
}
