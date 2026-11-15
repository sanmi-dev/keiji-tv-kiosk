package org.screenlite.webkiosk.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
    private volatile boolean pinShown = false;
    private volatile byte[] jpeg = null;
    private volatile String state = "{}";
    private String pageError = "";
    private long lastCapture = 0;

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
            @Override public void onPageFinished(WebView view, String url) { CookieManager.getInstance().flush(); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) pageError = error.getDescription().toString();
            }
        });
        loadUrl(StartupConfig.MUSIC_HOME);
        ui.postDelayed(this::tick, APPLY_MS);
    }

    /** テレビ内サーバーの操作の列と実際の待受ポートをつなぐ。 */
    public void attach(CommandQueue q, int actualPort) { queue = q; port = actualPort; }
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
        } catch (Exception ignored) {
        } finally {
            if (!closed) ui.postDelayed(this::tick, APPLY_MS);
        }
    }

    private void capture() {
        try {
            state = new JSONObject().put("url", getUrl() == null ? "" : getUrl())
                .put("title", getTitle() == null ? "" : getTitle()).put("error", pageError).put("pin", pinShown).toString();
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
            case "navigate": {
                Uri u = Uri.parse(c.value);
                if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) loadUrl(c.value);
                break;
            }
            case "preset":
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
            case "scroll": scrollBy(0, c.dy); break;
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
                    default: code = KeyEvent.KEYCODE_ESCAPE;
                }
                dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)); dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code)); break;
            }
            default: break;
        }
    }

    public void shutdown() { closed = true; ui.removeCallbacksAndMessages(null); stopLoading(); loadUrl("about:blank"); destroy(); }
}
