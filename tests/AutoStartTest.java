// 自動起動まわり（重ねて表示の許可の注意・本部への状態報告）の純粋部分のテスト。Android に依存しない。
// 実行（JDK は Codex 作業フォルダの同梱品）:
//   javac -encoding UTF-8 -d <out> app/src/main/java/org/screenlite/webkiosk/data/AutoStart.java app/src/main/java/org/screenlite/webkiosk/music/Registration.java tests/AutoStartTest.java
//   java -cp <out> AutoStartTest
import org.screenlite.webkiosk.data.AutoStart;
import org.screenlite.webkiosk.music.Registration;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class AutoStartTest {
  static int failed = 0, passed = 0;
  static void eq(String label, Object e, Object a) {
    if (Objects.equals(e, a)) { passed++; System.out.println("PASS " + label); }
    else { failed++; System.out.println("FAIL " + label + " expected=" + e + " actual=" + a); }
  }
  static void ok(String label, boolean c) { eq(label, true, c); }
  static String hmac(byte[] key, String msg) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
    StringBuilder sb = new StringBuilder();
    for (byte x : mac.doFinal(msg.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", x & 0xff));
    return sb.toString();
  }
  public static void main(String[] a) throws Exception {
    // ===== 注意を出すかどうか =====
    eq("Android 6 未満は許可の仕組みが無いので注意を出さない", false, AutoStart.needsOverlayNotice(22, false));
    eq("Android 6 以上で未許可なら注意を出す", true, AutoStart.needsOverlayNotice(23, false));
    eq("Android 14 で未許可なら注意を出す", true, AutoStart.needsOverlayNotice(34, false));
    eq("許可済みなら注意を出さない", false, AutoStart.needsOverlayNotice(34, true));
    // ===== 注意の文言（テレビの前の人が迷わず設定できること） =====
    String n = AutoStart.NOTICE;
    ok("文言に設定の名前「他のアプリの上に重ねて表示」がある", n != null && n.contains("他のアプリの上に重ねて表示"));
    ok("文言にアプリ名「掲示物テレビ」がある", n != null && n.contains("掲示物テレビ"));
    ok("文言に「特別なアプリアクセス」への道順がある", n != null && n.contains("特別なアプリアクセス"));
    ok("文言に自動で起動しない旨がある", n != null && n.contains("自動"));
    // ===== 監査 M3: 注意は短く・表示に戻ってから2分間だけ（法定掲示を長く覆わない） =====
    ok("文言は60字以内（2行以内に収める）", n != null && n.length() <= 60);
    eq("注意を出す時間は2分", 120_000L, AutoStart.NOTICE_SHOW_MS);
    eq("未許可・表示に戻った直後は出す", true, AutoStart.showNotice(34, false, 0L));
    eq("未許可・1分59秒後も出す", true, AutoStart.showNotice(34, false, 119_999L));
    eq("未許可・2分たったら消す", false, AutoStart.showNotice(34, false, 120_000L));
    eq("許可済みなら直後でも出さない", false, AutoStart.showNotice(34, true, 0L));
    eq("Android 6 未満は出さない", false, AutoStart.showNotice(22, false, 0L));
    // ===== 監査 M4: 設定画面を開いたら3分間は前面に戻さない =====
    eq("設定を開いた後の見回り休止は3分", 180_000L, AutoStart.SETTINGS_PAUSE_MS);
    eq("見えていなくて休止なしなら前に出す", true, AutoStart.shouldBringToFront(false, 1_000_000L, 0L));
    eq("見えているなら前に出さない", false, AutoStart.shouldBringToFront(true, 1_000_000L, 0L));
    eq("休止中は前に出さない", false, AutoStart.shouldBringToFront(false, 1_000_000L, 1_000_001L));
    eq("休止が明けたら前に出す", true, AutoStart.shouldBringToFront(false, 1_000_001L, 1_000_001L));
    // ===== 本部への状態報告（版・許可の有無を署名に含める） =====
    byte[] key = new byte[32]; for (int i = 0; i < 32; i++) key[i] = (byte) i;
    eq("署名は HMAC(store|ip|port|device|ts|ver|ovl)・許可あり=1",
        hmac(key, "002|192.168.1.7|8787|devABCDEFGHIJKLMNOP|1700000000000|1008|1"),
        Registration.sign(key, "002", "192.168.1.7", 8787, "devABCDEFGHIJKLMNOP", 1700000000000L, 1008, true));
    eq("署名は 許可なし=0",
        hmac(key, "002|192.168.1.7|8787|devABCDEFGHIJKLMNOP|1700000000000|1008|0"),
        Registration.sign(key, "002", "192.168.1.7", 8787, "devABCDEFGHIJKLMNOP", 1700000000000L, 1008, false));
    eq("旧形式の署名は変わらない（古い本部とも合う）",
        hmac(key, "002|192.168.1.7|8787|devABCDEFGHIJKLMNOP|1700000000000"),
        Registration.sign(key, "002", "192.168.1.7", 8787, "devABCDEFGHIJKLMNOP", 1700000000000L));
    eq("送信本文に ver と ovl が入る（key なし）",
        "mode=tvaddr&store=002&ip=192.168.1.7&port=8787&device=devABCDEFGHIJKLMNOP&ts=1700000000000&sig=ab&ver=1008&ovl=0",
        Registration.formBody("002", "192.168.1.7", 8787, "devABCDEFGHIJKLMNOP", "", 1700000000000L, "ab", 1008, false));
    eq("送信本文に ver と ovl が入る（初回 key 付き）",
        "mode=tvaddr&store=002&ip=192.168.1.7&port=8787&device=devABCDEFGHIJKLMNOP&ts=1700000000000&sig=ab&ver=1008&ovl=1&key=KEYKEY",
        Registration.formBody("002", "192.168.1.7", 8787, "devABCDEFGHIJKLMNOP", "KEYKEY", 1700000000000L, "ab", 1008, true));
    System.out.println(failed == 0 ? "ALL PASS (" + passed + ")" : failed + " FAILED / " + passed + " passed");
    if (failed > 0) System.exit(1);
  }
}
