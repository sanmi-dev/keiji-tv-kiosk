package org.screenlite.webkiosk.music;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
/** PC から送った MP3 の保存・一覧・順番（Android 非依存）。ファイル名は内部 id＋.mp3、一覧は songs.json。 */
public final class SongLibrary {
    public static final long MAX_SONG_BYTES = 30L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    static final int MAX_NAME = 80;
    private static final String INDEX = "songs.json";

    public static final class Song {
        public final String id, name; public final long size;
        public Song(String id, String name, long size) { this.id = id; this.name = name; this.size = size; }
    }
    public static final class Result {
        public final String code; public final Song song;
        Result(String code, Song song) { this.code = code; this.song = song; }
    }

    private final File dir;
    private final long maxSong, maxTotal;
    private final List<Song> songs = new ArrayList<>();
    private final SecureRandom rng = new SecureRandom();
    /** 受信中の曲のために予約している容量 */
    private long reserved = 0;
    private final java.util.Set<String> inFlight = new java.util.HashSet<>();

    public SongLibrary(File dir) { this(dir, MAX_SONG_BYTES, MAX_TOTAL_BYTES); }
    public SongLibrary(File dir, long maxSong, long maxTotal) {
        this.dir = dir; this.maxSong = maxSong; this.maxTotal = maxTotal;
        dir.mkdirs();
        load();
        sweep();
    }

    /** 表示名を安全にする。.mp3 で終わらない・空なら null。 */
    public static String cleanName(String raw) {
        if (raw == null) return null;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || c == '\\') continue;
            b.append(c);
        }
        String s = b.toString();
        while (s.contains("..")) s = s.replace("..", ".");
        s = s.trim();
        if (!s.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3")) return null;
        String base = s.substring(0, s.length() - 4).trim();
        while (base.startsWith(".")) base = base.substring(1);
        if (base.isEmpty()) return null;
        if (base.length() > MAX_NAME - 4) base = base.substring(0, MAX_NAME - 4);
        return base + s.substring(s.length() - 4);
    }

    /** 同じ名前があれば (2)(3)… を付ける。 */
    public static String uniqueName(String name, Collection<String> taken) {
        if (!taken.contains(name)) return name;
        String base = name.substring(0, name.length() - 4), ext = name.substring(name.length() - 4);
        for (int i = 2; ; i++) {
            String n = base + "(" + i + ")" + ext;
            if (!taken.contains(n)) return n;
        }
    }

    /** 先頭が ID3 か MPEG フレーム同期なら MP3 とみなす。 */
    public static boolean looksLikeMp3(byte[] h) {
        if (h == null || h.length < 3) return false;
        if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return true;
        return (h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0;
    }

    /** 受信は鍵の外で行う。鍵を握るのは「上限確認＋容量の予約」と「rename＋一覧追加」だけ。 */
    public Result save(String rawName, InputStream in, long len) {
        String name = cleanName(rawName);
        if (name == null) return new Result("bad_name", null);
        if (len <= 0 || len > maxSong) return new Result("too_large", null);
        String id;
        File part, dst;
        synchronized (this) {
            if (total() + reserved + len > maxTotal) return new Result("full", null);
            if (dir.getUsableSpace() < reserved + len + 8L * 1024 * 1024) return new Result("no_space", null);
            id = newId();
            part = new File(dir, id + ".part"); dst = new File(dir, id + ".mp3");
            reserved += len;
        }
        boolean done = false;
        try {
            byte[] head = new byte[4]; int headN = 0;
            long left = len;
            try (OutputStream o = new BufferedOutputStream(new FileOutputStream(part))) {
                byte[] buf = new byte[16 * 1024]; int n;
                while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                    for (int i = 0; i < n && headN < head.length; i++) head[headN++] = buf[i];
                    o.write(buf, 0, n);
                    left -= n;
                }
            }
            if (left > 0) return new Result("broken", null);
            byte[] h = new byte[headN]; System.arraycopy(head, 0, h, 0, headN);
            if (!looksLikeMp3(h)) return new Result("not_mp3", null);
            synchronized (this) {
                if (!part.renameTo(dst)) return new Result("broken", null);
                List<String> names = new ArrayList<>();
                for (Song x : songs) names.add(x.name);
                Song s = new Song(id, uniqueName(name, names), len);
                songs.add(s);
                if (!persist()) { songs.remove(s); dst.delete(); return new Result("broken", null); }
                done = true;
                return new Result("ok", s);
            }
        } catch (IOException e) {
            return new Result("broken", null);
        } finally {
            part.delete();
            synchronized (this) {
                reserved -= len;
                inFlight.remove(id);
                if (!done) dst.delete();
            }
        }
    }

    public synchronized List<Song> list() { return new ArrayList<>(songs); }

    public synchronized boolean move(String id, int delta) {
        int i = indexOf(id);
        if (i < 0) return false;
        int j = i + (delta < 0 ? -1 : 1);
        if (j < 0 || j >= songs.size()) return false;
        Song t = songs.get(i); songs.set(i, songs.get(j)); songs.set(j, t);
        persist();
        return true;
    }

    public synchronized boolean remove(String id) {
        int i = indexOf(id);
        if (i < 0) return false;
        Song s = songs.remove(i);
        new File(dir, s.id + ".mp3").delete();
        persist();
        return true;
    }

    /** 一覧にある id のファイルだけ返す。 */
    public synchronized File file(String id) {
        if (indexOf(id) < 0) return null;
        File f = new File(dir, id + ".mp3");
        return f.isFile() ? f : null;
    }

    public synchronized String toJson() {
        StringBuilder b = new StringBuilder("{\"songs\":[");
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":\"").append(s.id).append("\",\"name\":\"").append(esc(s.name)).append("\",\"size\":").append(s.size).append('}');
        }
        long used = total();
        long free = Math.max(0, Math.min(maxTotal - used, dir.getUsableSpace()));
        b.append("],\"free\":").append(free).append(",\"total\":").append(used).append('}');
        return b.toString();
    }

    private long total() { long t = 0; for (Song s : songs) t += s.size; return t; }
    private int indexOf(String id) {
        if (id == null) return -1;
        for (int i = 0; i < songs.size(); i++) if (songs.get(i).id.equals(id)) return i;
        return -1;
    }
    private String newId() {
        String a = "abcdefghijklmnopqrstuvwxyz0123456789";
        while (true) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 16; i++) b.append(a.charAt(rng.nextInt(a.length())));
            String id = b.toString();
            if (indexOf(id) < 0 && !new File(dir, id + ".mp3").exists() && !new File(dir, id + ".part").exists() && inFlight.add(id)) return id;
        }
    }
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.toString();
    }

    /** 一覧の保存: JSON 配列を一時ファイルに書いて rename。 */
    private boolean persist() {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            if (i > 0) b.append(",\n");
            b.append("{\"id\":\"").append(s.id).append("\",\"name\":\"").append(esc(s.name)).append("\",\"size\":").append(s.size).append('}');
        }
        b.append("]");
        File tmp = new File(dir, INDEX + ".tmp"), dst = new File(dir, INDEX);
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { tmp.delete(); return false; }
        if (!tmp.renameTo(dst)) {
            dst.delete();
            if (!tmp.renameTo(dst)) { tmp.delete(); return false; }
        }
        return true;
    }

    /** 一覧に無い書きかけ・はぐれファイル（*.part・*.mp3・songs.json.tmp）を消す。 */
    private void sweep() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            if (n.equals(INDEX + ".tmp") || n.endsWith(".part")) { f.delete(); continue; }
            if (n.endsWith(".mp3") && indexOf(n.substring(0, n.length() - 4)) < 0) f.delete();
        }
    }

    /** persist() が書いた形だけを読む小さな読み手。壊れた行は飛ばす。 */
    private void load() {
        File f = new File(dir, INDEX);
        if (!f.isFile()) return;
        String t;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            t = o.toString("UTF-8");
        } catch (IOException e) { return; }
        int p = 0;
        while (true) {
            int a = t.indexOf("{\"id\":\"", p);
            if (a < 0) break;
            try {
                int[] pos = {a + 7};
                String id = str(t, pos);
                pos[0] = t.indexOf("\"name\":\"", pos[0]) + 8;
                String name = str(t, pos);
                int z = t.indexOf("\"size\":", pos[0]) + 7, e = z;
                while (e < t.length() && Character.isDigit(t.charAt(e))) e++;
                long size = Long.parseLong(t.substring(z, e));
                p = e;
                if (id.matches("[a-z0-9]{1,24}") && new File(dir, id + ".mp3").isFile() && cleanName(name) != null) songs.add(new Song(id, name, size));
            } catch (RuntimeException ex) { break; }
        }
    }
    private static String str(String t, int[] pos) {
        StringBuilder b = new StringBuilder();
        int i = pos[0];
        while (true) {
            char c = t.charAt(i++);
            if (c == '"') break;
            if (c == '\\') {
                char d = t.charAt(i++);
                if (d == 'u') { b.append((char) Integer.parseInt(t.substring(i, i + 4), 16)); i += 4; }
                else b.append(d);
            } else b.append(c);
        }
        pos[0] = i;
        return b.toString();
    }
}
