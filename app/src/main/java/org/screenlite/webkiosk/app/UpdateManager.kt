package org.screenlite.webkiosk.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import org.screenlite.webkiosk.BuildConfig
import org.screenlite.webkiosk.data.UpdateCheck
import org.screenlite.webkiosk.service.StayOnTopService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 新しい版の確認・ダウンロード・インストール依頼（プロセス全体で1つ）。
 * 起動の約60秒後と3時間ごと、または店のPCの「アップデート」で確認する。
 * newerCode / message / working / fails / lastSeenRemote の読み書きは UI スレッドだけで行う。
 */
object UpdateManager {
    private const val TAG = "UpdateManager"
    const val ACTION_RESULT = "org.screenlite.webkiosk.UPDATE_INSTALL_RESULT"
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var started = false
    @Volatile private var busy = false

    /** PC に返す状況: idle / checking / latest / newer / error */
    @Volatile private var statusValue: String = "idle"
    @JvmStatic fun getStatus(): String = statusValue

    /** 公開版の versionCode（新しいときだけ >0）。0 ならポップアップを出さない。 */
    @get:JvmName("newerCodeState") val newerCode = mutableStateOf(0)
    @Volatile private var newerCodeMirror: Int = 0
    @JvmStatic fun getNewerCode(): Int = newerCodeMirror
    /** ポップアップに出す補足（ダウンロード中・失敗理由など）。 */
    val message = mutableStateOf("")
    val working = mutableStateOf(false)

    /** 続けて失敗した回数（UI スレッドのみ）。 */
    private var fails = 0
    /** 前回見た公開版の versionCode（UI スレッドのみ）。変わったら fails を 0 に戻す。 */
    private var lastSeenRemote = -1
    /** 進行中の PackageInstaller の Session（無ければ -1）。 */
    private val session = AtomicInteger(-1)
    @Volatile private var appCtx: Context? = null

    /** 落とした apk が公開版より古い（version.json だけ先に新しくなった瞬間など）。 */
    private class NotReadyException : Exception()
    /** 落とした apk が読めない。 */
    private class BrokenApkException : Exception()

    /** 更新待ちフラグ（キー "update_pending" = UpdateCheck.PREF_PENDING）を置く場所。 */
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    private fun clearPending() {
        val ctx = appCtx ?: return
        try { prefs(ctx).edit().remove(UpdateCheck.PREF_PENDING).apply() } catch (_: Throwable) { }
    }

    private val periodic = object : Runnable {
        override fun run() {
            checkNow()
            ui.postDelayed(this, UpdateCheck.INTERVAL_MS)
        }
    }

    /** 確認画面を開いたあと、結果が来ないまま時間がたったら押せる状態に戻す。 */
    private val waitTimeout = Runnable {
        if (working.value) {
            abandonOld()
            fail("確認画面が閉じました。もう一度押してください")
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val ctx = context.applicationContext
        appCtx = ctx
        var pending = false
        try {
            val p = prefs(ctx)
            pending = p.getBoolean(UpdateCheck.PREF_PENDING, false)
            if (pending) p.edit().remove(UpdateCheck.PREF_PENDING).apply()
        } catch (e: Throwable) { Log.w(TAG, "prefs: ${e.javaClass.simpleName}") }
        ui.postDelayed(periodic, UpdateCheck.firstDelayMs(pending))
    }

    /** 店のPCからの「アップデート」。どのスレッドから呼んでもよい。手動の確認なので失敗回数を戻す。 */
    @JvmStatic fun requestCheck() {
        ui.post { fails = 0 }
        checkNow()
    }

    private fun checkNow() {
        if (busy) return
        busy = true
        statusValue = "checking"
        worker.execute {
            try {
                val remote = UpdateCheck.parseVersionCode(httpGet(UpdateCheck.VERSION_URL))
                statusValue = when {
                    remote < 0 -> "error"
                    UpdateCheck.isNewer(remote, BuildConfig.VERSION_CODE) -> {
                        ui.post { onNewer(remote) }
                        "newer"
                    }
                    else -> { clearPending(); "latest" }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "check failed: ${e.javaClass.simpleName}")
                statusValue = "error"
            } finally { busy = false }
        }
    }

    /** UI スレッド。 */
    private fun onNewer(remote: Int) {
        if (remote != lastSeenRemote) { lastSeenRemote = remote; fails = 0 }
        if (newerCode.value != remote) {
            // 引っ込めたあとに同じ版でまた出すときは、1回失敗でまた引っ込める
            if (UpdateCheck.shouldHideAfterFailures(fails)) fails = UpdateCheck.MAX_INSTALL_FAILS - 1
            newerCode.value = remote
            newerCodeMirror = remote
            message.value = ""
        }
    }

    /** UI スレッド。失敗を数え、続けて失敗したらポップアップを引っ込める。 */
    private fun fail(msg: String) {
        ui.removeCallbacks(waitTimeout)
        working.value = false
        fails++
        if (UpdateCheck.shouldHideAfterFailures(fails)) {
            newerCode.value = 0
            newerCodeMirror = 0
            message.value = ""
        } else {
            message.value = msg
        }
    }

    /** 古い Session が残っていれば捨てる。どのスレッドからでもよい。 */
    private fun abandonOld() {
        val id = session.getAndSet(-1)
        val ctx = appCtx
        if (id < 0 || ctx == null) return
        try { ctx.packageManager.packageInstaller.abandonSession(id) } catch (e: Exception) {
            Log.w(TAG, "abandon: ${e.javaClass.simpleName}")
        }
    }

    /** GitHub は別ホストへ転送するので、転送を自分でたどる（https のみ）。 */
    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        if (!UpdateCheck.isHttps(u.toString())) throw IOException("not https")
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 20000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location"); c.disconnect()
                if (loc == null) throw IOException("redirect")
                u = URL(u, loc)
                if (!UpdateCheck.isHttps(u.toString())) throw IOException("not https")
            } else {
                if (code != 200) { c.disconnect(); throw IOException("HTTP $code") }
                return c
            }
        }
        throw IOException("too many redirects")
    }

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                // 上限+1 バイトまで読んで、超えていたらやめる
                while (true) {
                    val room = UpdateCheck.MAX_JSON_BYTES + 1 - total
                    if (room <= 0) break
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), room).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n); total += n
                }
                if (!UpdateCheck.withinLimit(total, UpdateCheck.MAX_JSON_BYTES)) throw IOException("too large")
                return out.toString("UTF-8")
            }
        } finally { c.disconnect() }
    }

    /** ポップアップの「更新する」・リモコンの決定。UI スレッドで呼ぶ。 */
    fun install(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (newerCode.value <= 0) return
        if (working.value) return
        val ctx = context.applicationContext
        appCtx = ctx
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                StayOnTopService.pauseForSettings()
                try { prefs(ctx).edit().putBoolean(UpdateCheck.PREF_PENDING, true).apply() } catch (_: Throwable) { }
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                // 設定画面を開けたなら失敗には数えない
                message.value = "掲示物テレビに『不明なアプリのインストール』を許可してから、もう一度押してください"
            } catch (e: Exception) {
                Log.w(TAG, "settings: ${e.javaClass.simpleName}")
                fail("『不明なアプリのインストール』の設定画面を開けませんでした。もう一度押してください")
            }
            return
        }
        ui.removeCallbacks(waitTimeout)
        abandonOld()
        working.value = true
        val note = if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) UpdateCheck.overlayNote(false) else ""
        val suffix = if (note.isEmpty()) "" else "\n" + note
        message.value = "ダウンロードしています…" + suffix
        worker.execute {
            val f = File(ctx.cacheDir, "update.apk")
            var stage = "download"
            try {
                download(f, suffix)
                stage = "check"
                checkArchive(ctx, f)
                stage = "commit"
                commit(ctx, f)
                ui.post {
                    // 先に失敗の結果が届いていたら何もしない
                    if (working.value) {
                        message.value = "インストールの確認画面を開いています…"
                        ui.removeCallbacks(waitTimeout)
                        ui.postDelayed(waitTimeout, UpdateCheck.WAIT_RESULT_MS)
                    }
                }
            } catch (e: BrokenApkException) {
                ui.post { fail(UpdateCheck.MSG_BROKEN_APK) }
            } catch (e: NotReadyException) {
                ui.post { fail("新しい版の準備がまだ終わっていません。少し待ってからもう一度押してください") }
            } catch (e: Throwable) {
                Log.w(TAG, "$stage failed: ${e.javaClass.simpleName}")
                val name = e.javaClass.simpleName
                ui.post {
                    if (stage == "commit") fail("インストールできませんでした（$name）。もう一度押してください")
                    else fail("ダウンロードできませんでした（$name）。もう一度押してください")
                }
            } finally {
                try { f.delete() } catch (_: Throwable) { }
            }
        }
    }

    private fun download(f: File, suffix: String) {
        val c = open(UpdateCheck.APK_URL)
        try {
            val total = c.contentLengthLong
            if (total > 0 && !UpdateCheck.withinLimit(total, UpdateCheck.MAX_APK_BYTES)) throw IOException("too large")
            c.inputStream.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var lastPct = -1
                    val startMs = SystemClock.elapsedRealtime()
                    while (true) {
                        if (UpdateCheck.downloadExpired(startMs, SystemClock.elapsedRealtime())) throw IOException("timeout")
                        val n = input.read(buf); if (n < 0) break
                        done += n
                        if (!UpdateCheck.withinLimit(done, UpdateCheck.MAX_APK_BYTES)) throw IOException("too large")
                        out.write(buf, 0, n)
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; ui.post { if (working.value) message.value = "ダウンロードしています… $pct%$suffix" } }
                        }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    /** 落とした apk の版が今の版より新しいことを確かめる。読めなければ失敗扱い。 */
    private fun checkArchive(ctx: Context, f: File) {
        val info = ctx.packageManager.getPackageArchiveInfo(f.path, 0) ?: throw BrokenApkException()
        val code: Int = if (Build.VERSION.SDK_INT >= 28) {
            val v = info.longVersionCode
            if (v < 0 || v > Int.MAX_VALUE) -1 else v.toInt()
        } else {
            @Suppress("DEPRECATION") info.versionCode
        }
        if (!UpdateCheck.isNewer(code, BuildConfig.VERSION_CODE)) throw NotReadyException()
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        session.set(id)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("keiji-tv.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                // Android 14+ の暗黙インテント制限に当たらないよう、明示 Intent にする
                val intent = Intent(ctx, UpdateResultReceiver::class.java).setAction(ACTION_RESULT).setPackage(ctx.packageName)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val pi = PendingIntent.getBroadcast(ctx, id, intent, flags)
                StayOnTopService.pauseForSettings()
                s.commit(pi.intentSender)
            }
        } catch (t: Throwable) {
            session.compareAndSet(id, -1)
            try { installer.abandonSession(id) } catch (_: Throwable) { }
            throw t
        }
    }

    internal fun onResult(ctx: Context, intent: Intent) {
        // いま待っている Session 以外（時間切れで捨てた Session の遅れた結果など）は捨てる。失敗を2回数えない
        val sid = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sid < 0 || sid != session.get()) {
            Log.w(TAG, "ignore result of session $sid")
            return
        }
        val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            var opened = false
            if (confirm != null) {
                StayOnTopService.pauseForSettings()
                try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); opened = true } catch (e: Exception) {
                    Log.w(TAG, "confirm: ${e.javaClass.simpleName}")
                }
            }
            if (!opened) ui.post { abandonOld(); fail("インストール画面を開けませんでした。もう一度押してください") }
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            ui.post { ui.removeCallbacks(waitTimeout); session.set(-1); working.value = false; clearPending() }
        } else {
            val m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            ui.post { session.set(-1); fail("インストールできませんでした（$st $m）。もう一度押してください") }
        }
    }
}

class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { UpdateManager.onResult(context, intent) }
}
