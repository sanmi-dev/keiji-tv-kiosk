import org.screenlite.webkiosk.data.StartupConfig;
public class StartupConfigTest {
  static void eq(String a,String b) { if(!a.equals(b)) throw new AssertionError("expected="+a+" actual="+b); }
  public static void main(String[] args) {
    String entry="https://sanmi-dev.github.io/sanmi-apps/tv";
    eq(entry,StartupConfig.signage(""));
    eq(entry,StartupConfig.signage("about:blank"));
    eq(entry+"?store=002",StartupConfig.signage(entry+"?store=002"));
    System.out.println("PASS 3 startup configuration checks");
  }
}
