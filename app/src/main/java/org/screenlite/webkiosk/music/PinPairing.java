package org.screenlite.webkiosk.music;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
/** 店のPCを4桁の番号でつなぐ。番号は10分有効・1回限り。5回間違えると5分ロック。通行証は最大10件。 */
public final class PinPairing {
    public interface TokenStore { List<String> load(); void save(List<String> tokens); }
    public static final class Result {
        public final int status; public final String token;
        public Result(int status, String token) { this.status = status; this.token = token; }
    }
    static final long PIN_TTL = 10 * 60_000L, LOCK_MS = 5 * 60_000L;
    static final int MAX_FAIL = 5, MAX_TOKENS = 10;
    private final TokenStore store;
    private final Random rng;
    private final List<String> tokens = new ArrayList<>();
    private String pin = null;
    private long pinAt = 0;
    private int fails = 0;
    private long lockedUntil = 0;

    public PinPairing(TokenStore store, Random rng) {
        this.store = store; this.rng = rng;
        List<String> l = store.load();
        if (l != null) for (String t : l) if (t != null && t.matches("[A-Za-z0-9_-]{32}")) tokens.add(t);
        while (tokens.size() > MAX_TOKENS) tokens.remove(0);
    }
    public synchronized String pin(long now) {
        if (pin == null || now - pinAt >= PIN_TTL || now < pinAt) {
            pin = String.format("%04d", rng.nextInt(10000));
            pinAt = now;
        }
        return pin;
    }
    public synchronized Result pair(String given, long now) {
        if (now < lockedUntil) return new Result(429, null);
        if (lockedUntil != 0) { lockedUntil = 0; fails = 0; }
        boolean live = pin != null && now - pinAt < PIN_TTL && now >= pinAt;
        if (given == null || !given.matches("[0-9]{4}") || !live || !eqConst(pin, given)) {
            if (++fails >= MAX_FAIL) lockedUntil = now + LOCK_MS;
            return new Result(401, null);
        }
        fails = 0;
        pin = null;
        byte[] b = new byte[24];
        rng.nextBytes(b);
        String tok = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokens.add(tok);
        while (tokens.size() > MAX_TOKENS) tokens.remove(0);
        store.save(new ArrayList<>(tokens));
        return new Result(200, tok);
    }
    public synchronized boolean valid(String token) {
        if (token == null || token.length() != 32) return false;
        boolean hit = false;
        for (String t : tokens) hit |= eqConst(t, token);
        return hit;
    }
    public synchronized void revokeAll() {
        tokens.clear();
        store.save(new ArrayList<>());
    }
    static boolean eqConst(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
