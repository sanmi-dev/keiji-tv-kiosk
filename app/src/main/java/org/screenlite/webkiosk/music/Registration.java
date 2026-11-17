package org.screenlite.webkiosk.music;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
/** 本部GASへテレビの店内アドレスを知らせる本文と署名。 */
public final class Registration {
    private static final Pattern IPV4 = Pattern.compile("(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})");
    private static final Pattern STORE = Pattern.compile("[A-Za-z0-9_-]{1,10}");
    private static final Pattern ID_FIELD = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]*)\"");

    public static boolean isPrivateIPv4(String ip) {
        if (ip == null) return false;
        Matcher m = IPV4.matcher(ip);
        if (!m.matches()) return false;
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) { o[i] = Integer.parseInt(m.group(i + 1)); if (o[i] > 255) return false; }
        return o[0] == 10 || (o[0] == 172 && o[1] >= 16 && o[1] <= 31) || (o[0] == 192 && o[1] == 168);
    }
    public static String sign(byte[] key, String store, String ip, int port, String deviceId, long ts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] d = mac.doFinal((store + "|" + ip + "|" + port + "|" + deviceId + "|" + ts).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** 新形式: 版（versionCode）と重ねて表示の許可の有無も署名に含める。 */
    public static String sign(byte[] key, String store, String ip, int port, String deviceId, long ts, int ver, boolean ovl) {
        return hmacHex(key, store + "|" + ip + "|" + port + "|" + deviceId + "|" + ts + "|" + ver + "|" + (ovl ? "1" : "0"));
    }
    private static String hmacHex(byte[] key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] d = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** 新形式の送信本文（&sig の後に &ver と &ovl、その後に初回だけ &key）。 */
    public static String formBody(String store, String ip, int port, String deviceId, String keyB64, long ts, String sig, int ver, boolean ovl) {
        StringBuilder sb = new StringBuilder("mode=tvaddr");
        sb.append("&store=").append(enc(store)).append("&ip=").append(enc(ip)).append("&port=").append(port)
          .append("&device=").append(enc(deviceId)).append("&ts=").append(ts).append("&sig=").append(enc(sig))
          .append("&ver=").append(ver).append("&ovl=").append(ovl ? "1" : "0");
        if (keyB64 != null && !keyB64.isEmpty()) sb.append("&key=").append(enc(keyB64));
        return sb.toString();
    }
    public static String formBody(String store, String ip, int port, String deviceId, String keyB64, long ts, String sig) {
        StringBuilder sb = new StringBuilder("mode=tvaddr");
        sb.append("&store=").append(enc(store)).append("&ip=").append(enc(ip)).append("&port=").append(port)
          .append("&device=").append(enc(deviceId)).append("&ts=").append(ts).append("&sig=").append(enc(sig));
        if (keyB64 != null && !keyB64.isEmpty()) sb.append("&key=").append(enc(keyB64));
        return sb.toString();
    }
    /** evaluateJavascript の戻り値（JSON文字列）や localStorage の値から店舗IDを取り出す。不正なら ""。 */
    public static String storeId(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        s = s.trim();
        if (s.startsWith("{")) {
            Matcher m = ID_FIELD.matcher(s);
            s = m.find() ? m.group(1) : "";
        }
        if ("null".equals(s) || !STORE.matcher(s).matches()) return "";
        return s;
    }
    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }
}
