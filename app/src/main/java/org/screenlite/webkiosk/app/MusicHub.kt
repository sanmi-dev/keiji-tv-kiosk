package org.screenlite.webkiosk.app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import org.screenlite.webkiosk.music.CommandQueue
import org.screenlite.webkiosk.music.MiniHttpServer
import org.screenlite.webkiosk.music.MusicHttp
import org.screenlite.webkiosk.music.PinPairing
import org.screenlite.webkiosk.music.Registration
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 掲示 WebView（tv.html）への参照。店舗IDを読むためだけに使う。 */
object SignageWebRef {
    @Volatile private var ref: WeakReference<WebView>? = null
    fun set(w: WebView) { ref = WeakReference(w) }
    fun get(): WebView? = ref?.get()
}

/**
 * テレビ内で店のPC向け操作画面を配信し（MiniHttpServer）、本部GASへ店内アドレスを定期的に知らせる。
 * 公開APKに秘密は入れない: 端末IDと鍵はテレビ内で初回に作り、SharedPreferences（バックアップ対象外）に置く。
 */
class MusicHub(context: Context, private val music: RemoteMusicView) {
    companion object {
        private const val TAG = "MusicHub"
        /** 音楽を鳴らす持ち主はプロセス全体で1つだけ。 */
        val owner = org.screenlite.webkiosk.music.SingleOwner<MusicHub>()
        const val EXEC = "https://script.google.com/macros/s/AKfycbyvwW1LHg8S6ddtlIIR1V2Yhh5NqTINsfc9zNIEXLgB_LxFMnXF1zcGSo2Q1OHZaiDG/exec"
        private const val PREFS = "music_server"
        private val PORTS = intArrayOf(8787, 8788, 8789, 8790)
        private const val PIN_SHOW_MS = 2 * 60_000L
        private const val STORE_JS = "(function(){try{var q=new URLSearchParams(location.search).get('store');if(q)return q;return localStorage.getItem('keijiTvPick')}catch(e){return ''}})()"
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val rng = SecureRandom()
    private val ui = Handler(Looper.getMainLooper())
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val assetCache = ConcurrentHashMap<String, ByteArray>()
    private val queue = CommandQueue(50)
    private val pairing = PinPairing(object : PinPairing.TokenStore {
        override fun load(): List<String> = (prefs.getString("tokens", "") ?: "").split(",").filter { it.isNotEmpty() }
        override fun save(tokens: List<String>) { prefs.edit().putString("tokens", tokens.joinToString(",")).apply() }
    }, rng)
    private val http = MusicHttp(pairing, queue, music, MusicHttp.Assets { name -> asset(name) })
    @Volatile private var server: MiniHttpServer? = null
    @Volatile private var stopped = false
    @Volatile private var port = 0

    /** テレビ右下に出す接続番号（null なら非表示）。 */
    val pinText = mutableStateOf<String?>(null)
    private val hidePin = Runnable { pinText.value = null; music.setPinShown(false) }

    fun start() {
        http.setPinRequestListener(Runnable {
            val pin = pairing.pin(System.currentTimeMillis())
            ui.post {
                pinText.value = pin
                music.setPinShown(true)
                ui.removeCallbacks(hidePin)
                ui.postDelayed(hidePin, PIN_SHOW_MS)
            }
        })
        worker.execute {
            for (p in PORTS) {
                try {
                    val s = MiniHttpServer(p) { r -> http.handle(r, System.currentTimeMillis()) }
                    if (stopped) break
                    s.start()
                    server = s; port = s.port()
                    // stop() が先に走っていたら、ここで作ったサーバーを自分で閉じる
                    if (stopped) { try { s.stop() } catch (_: Exception) { }; server = null; port = 0 }
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "port $p busy: ${e.message}")
                }
            }
            if (port > 0 && !stopped) ui.post { music.attach(queue, port) }
        }
        worker.scheduleWithFixedDelay({ registerOnce() }, 30, 300, TimeUnit.SECONDS)
    }

    private val allStopped = java.util.concurrent.atomic.AtomicBoolean(false)
    /** サーバーと音楽ブラウザまで止める（二重に呼んでも1回だけ）。UI スレッドで呼ぶ。 */
    fun stopAll() {
        if (!allStopped.compareAndSet(false, true)) return
        stop()
        try { music.shutdown() } catch (_: Exception) { }
    }

    fun stop() {
        stopped = true
        worker.shutdownNow()
        try { server?.stop() } catch (_: Exception) { }
        server = null
        ui.removeCallbacks(hidePin)
    }

    private fun asset(name: String): ByteArray? {
        assetCache[name]?.let { return it }
        return try {
            app.assets.open(name).use { input ->
                val out = ByteArrayOutputStream(); input.copyTo(out); out.toByteArray()
            }.also { assetCache[name] = it }
        } catch (e: Exception) { null }
    }

    private fun deviceId(): String {
        prefs.getString("device_id", null)?.let { if (it.length == 20) return it }
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val id = String(CharArray(20) { abc[rng.nextInt(abc.length)] })
        prefs.edit().putString("device_id", id).commit()
        return id
    }

    private fun keyBytes(): ByteArray {
        prefs.getString("key_b64", null)?.let {
            try { val b = Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING); if (b.size == 32) return b } catch (_: Exception) { }
        }
        val k = ByteArray(32).also { rng.nextBytes(it) }
        prefs.edit().putString("key_b64", Base64.encodeToString(k, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            .putBoolean("key_sent", false).commit()
        return k
    }

    private fun readStoreId(): String {
        val latch = CountDownLatch(1)
        var raw: String? = null
        ui.post {
            val w = SignageWebRef.get()
            if (w == null) { latch.countDown(); return@post }
            try { w.evaluateJavascript(STORE_JS) { v -> raw = v; latch.countDown() } } catch (_: Exception) { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        return Registration.storeId(raw)
    }

    private fun localIp(): String {
        try {
            val list = NetworkInterface.getNetworkInterfaces() ?: return ""
            var fallback = ""
            for (ni in list.toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a !is Inet4Address) continue
                    val ip = a.hostAddress ?: continue
                    if (!Registration.isPrivateIPv4(ip)) continue
                    val n = ni.name ?: ""
                    if (n.startsWith("wlan") || n.startsWith("eth")) return ip
                    if (fallback.isEmpty()) fallback = ip
                }
            }
            return fallback
        } catch (_: Exception) { return "" }
    }

    private fun registerOnce() {
        try {
            val p = port
            if (p <= 0) return
            val store = readStoreId()
            if (store.isEmpty()) return
            val ip = localIp()
            if (ip.isEmpty()) return
            val dev = deviceId()
            val key = keyBytes()
            val sendKey = !prefs.getBoolean("key_sent", false)
            val keyB64 = if (sendKey) prefs.getString("key_b64", "") ?: "" else ""
            val ts = System.currentTimeMillis()
            val ver = org.screenlite.webkiosk.BuildConfig.VERSION_CODE
            val ovl = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app)
            val sig = Registration.sign(key, store, ip, p, dev, ts, ver, ovl)
            val body = Registration.formBody(store, ip, p, dev, keyB64, ts, sig, ver, ovl).toByteArray(Charsets.UTF_8)
            val c = URL(EXEC).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15000; c.readTimeout = 20000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                if (code != 200) return
                val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val j = JSONObject(text)
                if (j.optBoolean("ok")) {
                    if (sendKey) prefs.edit().putBoolean("key_sent", true).apply()
                } else if (j.optString("error") == "need_key") {
                    // 本部でリセットされた: 次回は鍵を同送して登録し直す
                    prefs.edit().putBoolean("key_sent", false).apply()
                }
            } finally { c.disconnect() }
        } catch (e: Exception) {
            Log.w(TAG, "register failed: ${e.javaClass.simpleName}")
        }
    }
}
