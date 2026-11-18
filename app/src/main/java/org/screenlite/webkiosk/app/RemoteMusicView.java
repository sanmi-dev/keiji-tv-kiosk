package org.screenlite.webkiosk.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import org.screenlite.webkiosk.data.MusicResume;
import org.screenlite.webkiosk.data.StartupConfig;
import org.screenlite.webkiosk.music.CommandQueue;
import org.screenlite.webkiosk.music.MusicHttp;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** 掲示の下に置く音楽用ブラウザ。テレビ内サーバーが受けた操作（CommandQueue）を適用し、画面の JPEG を作る。 */
public final class RemoteMusicView extends WebView implements MusicHttp.Frames {
    private static final long APPLY_MS = 300, CAPTURE_MS = 1000;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile CommandQueue queue = null;
    private volatile int port = 8787;
    private volatile boolean closed = false;
    private static final String KEY_LAST = "last_music_url";
    private android.content.SharedPreferences prefs;
    private volatile boolean pendingMp3 = false;
    private String lastSaved = null;
    private volatile boolean pinShown = false;
    private volatile byte[] jpeg = null;
    private volatile String state = "{}";
    private String pageError = "";
    private long lastCapture = 0;
    private static final long WATCH_MS = 5000;
    private String watchScript = null;
    private long lastWatch = 0;
    private long lastUserCmd = -1;
    private volatile int scrollY = 0, scrollH = 0, clientH = 0;
    // 起動直後などでページを開けなかったときの開き直し（UI スレッドだけで読み書き）
    private String lastRequested = null;
    private String failedUrl = null;
    private boolean loadFailed = false;
    private boolean retryPending = false;
    private int retryAttempt = 0;
    private final Runnable retryTask = this::retryNow;
    private ConnectivityManager cm = null;
    private ConnectivityManager.NetworkCallback netCallback = null;
    // YouTube のスマホ版は WebView.scrollBy では動かないので、ページの中でスクロールさせる
    private static final String DOC_JS = "var d=document.scrollingElement||document.body;";
    private static final String POS_JS = "(function(){try{" + DOC_JS + "return [Math.round(d.scrollTop),Math.round(d.scrollHeight),Math.round(d.clientHeight||window.innerHeight)].join(',')}catch(e){return ''}})()";

    @SuppressLint("SetJavaScriptEnabled")
    public RemoteMusicView(Context context) {
        super(context);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setDomStorageEnabled(true);
        getSettings().setMediaPlaybackRequiresUserGesture(false);
        getSettings().setAllowFileAccess(false);
        getSettings().setAllowContentAccess(false);
        getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        getSettings().setUseWideViewPort(true);
        getSettings().setLoadWithOverviewMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 23) getSettings().setOffscreenPreRaster(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true);
        setWebChromeClient(new WebChromeClient());
        setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !("http".equals(scheme) || "https".equals(scheme) || "about".equals(scheme));
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                loadFailed = false;
                if (MusicResume.shouldRetry(url)) lastRequested = url;
            }
            @Override public void onPageFinished(WebView view, String url) {
                CookieManager.getInstance().flush();
                // 読み込みに失敗したページ（エラー表示）は「最後の音楽ページ」として覚えない
                if (loadFailed) return;
                if (retryAttempt > 0) { retryAttempt = 0; pageError = ""; }
                remember(url);
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) { if (!loadFailed) remember(url); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                pageError = error.getDescription().toString();
                // 開こうとしていたページ（エラー表示のページではない）を覚えて、時間をおいて開き直す
                String u = request.getUrl() == null ? null : request.getUrl().toString();
                failedUrl = MusicResume.shouldRetry(u) ? u : lastRequested;
                loadFailed = true;
                scheduleRetry();
            }
        });
        try (java.io.InputStream in = context.getAssets().open("loop-watch.js")) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(); byte[] chunk = new byte[4096]; int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            watchScript = buf.toString("UTF-8");
        } catch (Exception e) { watchScript = null; }
        prefs = context.getSharedPreferences("music", Context.MODE_PRIVATE);
        String saved = null;
        try { saved = prefs.getString(KEY_LAST, null); } catch (Exception ignored) { }
        // 1.2.6: 電源オンはいつも MP3。ページはテレビ内サーバーのポートが決まってから開く（決まらなければ10秒後に既定ポートで開く）
        pendingMp3 = true;
        ui.postDelayed(this::openPendingMp3, 10000);
        ui.postDelayed(this::tick, APPLY_MS);
        watchNetwork(context);
    }

    /** ネットがつながった合図で、開けなかったページをすぐ開き直す。 */
    private void watchNetwork(Context context) {
        try {
            cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { ui.post(RemoteMusicView.this::retryOnNetwork); }
            };
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(netCallback);
            } else {
                NetworkRequest req = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
                cm.registerNetworkCallback(req, netCallback);
            }
        } catch (Exception e) { netCallback = null; }
    }

    private void scheduleRetry() {
        if (closed || retryPending || !MusicResume.shouldRetry(failedUrl)) return;
        retryAttempt++;
        retryPending = true;
        ui.postDelayed(retryTask, MusicResume.retryDelayMs(retryAttempt));
    }

    private void retryNow() {
        retryPending = false;
        if (closed || !loadFailed || !MusicResume.shouldRetry(failedUrl)) return;
        loadUrl(failedUrl);
    }

    private void retryOnNetwork() {
        if (closed || !loadFailed || !MusicResume.shouldRetry(failedUrl)) return;
        ui.removeCallbacks(retryTask);
        retryPending = false;
        loadUrl(failedUrl);
    }

    /** PC からページを変える操作が来たら、開き直しの予約をやめてそちらを優先する。 */
    private void cancelRetry() {
        ui.removeCallbacks(retryTask);
        retryPending = false;
        retryAttempt = 0;
        loadFailed = false;
        failedUrl = null;
    }

    /** テレビ内サーバーの操作の列と実際の待受ポートをつなぐ。 */
    public void attach(CommandQueue q, int actualPort) { queue = q; port = actualPort; ui.post(this::openPendingMp3); ui.post(this::fixMp3Port); }

    /** 既定ポートで先に開いたMP3ページが、実際のポートと違えば開き直す。 */
    private void fixMp3Port() {
        if (closed) return;
        String u = getUrl();
        if (MusicResume.MP3.equals(MusicResume.toSaved(u)) && !u.startsWith("http://127.0.0.1:" + port + "/")) {
            loadUrl(MusicResume.startUrl(MusicResume.MP3, StartupConfig.MUSIC_HOME, port));
        }
    }

    private void openPendingMp3() {
        if (closed || !pendingMp3) return;
        pendingMp3 = false;
        loadUrl(MusicResume.startUrl(MusicResume.MP3, StartupConfig.MUSIC_HOME, port));
    }

    /** 最後に流していた音楽ページを覚える（起動時・朝の電源オンでそこから再開する）。 */
    private void remember(String url) {
        String v = MusicResume.toSaved(url);
        if (v == null || v.equals(lastSaved)) return;
        lastSaved = v;
        try { prefs.edit().putString(KEY_LAST, v).apply(); } catch (Exception ignored) { }
    }
    public void setPinShown(boolean shown) { pinShown = shown; }

    @Override public byte[] latestJpeg() { return jpeg; }
    @Override public String stateJson() { return state; }

    private void tick() {
        if (closed) return;
        try {
            CommandQueue q = queue;
            if (q != null) {
                List<CommandQueue.Command> list = q.drain();
                for (CommandQueue.Command c : list) {
                    try { apply(c); } catch (Exception e) { pageError = "操作を反映できません: " + e.getClass().getSimpleName(); }
                }
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastCapture >= CAPTURE_MS) { lastCapture = now; capture(); }
            if (watchScript != null && now - lastWatch >= WATCH_MS) {
                lastWatch = now;
                String u = getUrl();
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                    long since = lastUserCmd < 0 ? 1000000000L : now - lastUserCmd;
                    evaluateJavascript(watchScript + "\n;kjLoopTick(window, Date.now(), " + since + ");", null);
                }
                readScroll();
            }
        } catch (Exception ignored) {
        } finally {
            if (!closed) ui.postDelayed(this::tick, APPLY_MS);
        }
    }

    private void capture() {
        try {
            state = new JSONObject().put("url", getUrl() == null ? "" : getUrl())
                .put("title", getTitle() == null ? "" : getTitle()).put("error", pageError).put("pin", pinShown)
                .put("scrollY", scrollY).put("scrollHeight", scrollH).put("clientHeight", clientH)
                .put("update", UpdateManager.getStatus()).put("newer", UpdateManager.getNewerCode()).put("autoReturn", AutoReturnSetting.get(getContext())).put("version", org.screenlite.webkiosk.BuildConfig.VERSION_CODE).toString();
        } catch (Exception ignored) { }
        int w = getWidth(), h = getHeight();
        if (w < 1 || h < 1) return;
        int width = Math.min(w, 1280), height = Math.max(1, h * width / w);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        try {
            Canvas canvas = new Canvas(bitmap); canvas.scale((float) width / w, (float) height / h); draw(canvas);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.JPEG, 65, bytes);
            byte[] b = bytes.toByteArray();
            if (b.length < 1024 * 1024) jpeg = b;
        } finally { bitmap.recycle(); }
    }

    private void apply(CommandQueue.Command c) {
        pageError = "";
        switch (c.action) {
            case "click": case "key": case "text": case "scroll": case "scrollTo": case "navigate": case "back": case "forward": case "reload":
                lastUserCmd = SystemClock.uptimeMillis(); break;
            default: break;
        }
        switch (c.action) {
            case "navigate": case "cast": case "preset": case "stop": case "back": case "forward": case "reload":
                cancelRetry(); break;
            default: break;
        }
        switch (c.action) {
            case "navigate": {
                Uri u = Uri.parse(c.value);
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) loadUrl(c.value);
                break;
            }
            case "cast":
                // 開いた直後に止まっている動画は loop-watch に再生させたいので、直前の操作の時刻を消して「人が止めた」と見なされないようにする
                lastUserCmd = -1;
                if (CommandQueue.okCast(c.value)) loadUrl(c.value);
                break;
            case "autoReturn": AutoReturnSetting.set(getContext(), "on".equals(c.value)); break;
            case "preset":
                // cast と同じく、開いた直後に止まっている曲は loop-watch に再生させる（「人が止めた」と見なさない）
                lastUserCmd = -1;
                if ("youtube".equals(c.value)) loadUrl(StartupConfig.MUSIC_HOME);
                else if ("mp3".equals(c.value)) loadUrl("http://127.0.0.1:" + port + "/mp3");
                break;
            case "stop": loadUrl("about:blank"); break;
            case "back": if (canGoBack()) goBack(); break;
            case "forward": if (canGoForward()) goForward(); break;
            case "reload": reload(); break;
            case "click": {
                requestFocus(); long at = SystemClock.uptimeMillis();
                float x = (float) c.x * getWidth(), y = (float) c.y * getHeight();
                MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(at, at + 70, MotionEvent.ACTION_UP, x, y, 0);
                dispatchTouchEvent(down); dispatchTouchEvent(up); down.recycle(); up.recycle(); break;
            }
            case "scroll":
                evaluateJavascript("(function(){try{" + DOC_JS + "if(typeof d.scrollBy==='function')d.scrollBy(0," + c.dy + ");else window.scrollBy(0," + c.dy + ")}catch(e){}})()", v -> readScroll());
                break;
            case "scrollTo":
                evaluateJavascript("(function(){try{" + DOC_JS + "var t=Math.max(0,d.scrollHeight-(d.clientHeight||window.innerHeight))*" + c.y + ";if(typeof d.scrollTo==='function')d.scrollTo(0,t);else window.scrollTo(0,t)}catch(e){}})()", v -> readScroll());
                break;
            case "checkUpdate": UpdateManager.requestCheck(); break;
            case "text": {
                requestFocus();
                InputConnection connection = onCreateInputConnection(new EditorInfo());
                if (connection == null || !connection.commitText(c.value, 1)) pageError = "先にサイトの文字入力欄をクリックしてください";
                break;
            }
            case "key": {
                int code;
                switch (c.value) {
                    case "Enter": code = KeyEvent.KEYCODE_ENTER; break;
                    case "Backspace": code = KeyEvent.KEYCODE_DEL; break;
                    case "Tab": code = KeyEvent.KEYCODE_TAB; break;
                    case "Space": code = KeyEvent.KEYCODE_SPACE; break;
                    case "ArrowUp": code = KeyEvent.KEYCODE_DPAD_UP; break;
                    case "ArrowDown": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
                    case "ArrowLeft": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
                    case "ArrowRight": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
                    case "PageUp": code = KeyEvent.KEYCODE_PAGE_UP; break;
                    case "PageDown": code = KeyEvent.KEYCODE_PAGE_DOWN; break;
                    case "Home": code = KeyEvent.KEYCODE_MOVE_HOME; break;
                    case "End": code = KeyEvent.KEYCODE_MOVE_END; break;
                    case "Delete": code = KeyEvent.KEYCODE_FORWARD_DEL; break;
                    default: code = KeyEvent.KEYCODE_ESCAPE;
                }
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)); dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code)); break;
            }
            default: break;
        }
    }

    private void readScroll() {
        String u = getUrl();
        if (closed || u == null || !(u.startsWith("http://") || u.startsWith("https://"))) { scrollY = 0; scrollH = 0; clientH = 0; return; }
        evaluateJavascript(POS_JS, v -> {
            try {
                String[] p = (v == null ? "" : v.replace("\"", "")).split(",");
                if (p.length == 3) { scrollY = Integer.parseInt(p[0]); scrollH = Integer.parseInt(p[1]); clientH = Integer.parseInt(p[2]); }
            } catch (Exception ignored) { }
        });
    }

    public void shutdown() {
        closed = true;
        ui.removeCallbacksAndMessages(null);
        if (cm != null && netCallback != null) {
            try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) { }
            netCallback = null;
        }
        stopLoading(); loadUrl("about:blank"); destroy();
    }
}
