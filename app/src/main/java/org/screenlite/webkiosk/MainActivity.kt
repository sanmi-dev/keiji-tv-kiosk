package org.screenlite.webkiosk

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.viewinterop.AndroidView
import org.screenlite.webkiosk.app.RemoteMusicView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import org.screenlite.webkiosk.app.FullScreenHelper
import org.screenlite.webkiosk.app.IdleBrightnessController
import org.screenlite.webkiosk.app.NotificationPermissionHelper
import org.screenlite.webkiosk.app.StayOnTopServiceStarter
import org.screenlite.webkiosk.app.TapUnlockHandler
import org.screenlite.webkiosk.components.MainScreen
import org.screenlite.webkiosk.components.TouchKioskInputOverlay
import org.screenlite.webkiosk.components.TvKioskInputOverlay
import org.screenlite.webkiosk.data.AutoStart
import org.screenlite.webkiosk.data.KioskSettingsFactory
import org.screenlite.webkiosk.ui.theme.ScreenliteWebKioskTheme
import org.screenlite.webkiosk.ui.theme.isTvDevice

private const val BACK_HOLD_MS = 3000L

class MainActivity : ComponentActivity() {
    private lateinit var unlockHandler: TapUnlockHandler
    lateinit var idleController: IdleBrightnessController
    /** 「他のアプリの上に重ねて表示」が未許可で、電源オン時に自動で起動できない状態なら true。 */
    val overlayNotice = mutableStateOf(false)
    private val noticeHandler = Handler(Looper.getMainLooper())
    private var noticeShownAt = 0L
    private var noticeGranted = true
    // 表示に戻ってから2分たったら帯を消す（法定掲示を長く覆わない）
    private val hideNotice = Runnable { overlayNotice.value = false }

    private fun updateOverlayNotice() {
        val ms = SystemClock.elapsedRealtime() - noticeShownAt
        overlayNotice.value = AutoStart.showNotice(Build.VERSION.SDK_INT, noticeGranted, ms)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FullScreenHelper.enableImmersiveMode(this.window)
        // PCから入力するため、テレビの掲示の上にソフトキーボードを出さない。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        StayOnTopServiceStarter.ensureRunning(this)

        unlockHandler = TapUnlockHandler {
            openSettings()
        }

        val settings = KioskSettingsFactory.get(this)
        idleController = IdleBrightnessController(this, settings)

        setContent {
            ScreenliteWebKioskTheme {
                AppContent(unlockHandler, this)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        idleController.onUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    // サンミ改造: テレビではリモコンのボタンを Web ページへ渡す（元の Screenlite は全部捨てていた）。
    // 戻るボタンだけはアプリが受け取り、ページを閉じないようにする。戻るを3秒押し続けると設定画面。
    private var backHandled = false
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        idleController.onUserInteraction()
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) backHandled = false
                if (!backHandled && event.eventTime - event.downTime >= BACK_HOLD_MS) {
                    backHandled = true
                    openSettings()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onPause() {
        super.onPause()
        idleController.stop()
        noticeHandler.removeCallbacks(hideNotice)
    }

    override fun onResume() {
        super.onResume()
        idleController.start()
        // 設定画面で許可して戻ってきたら注意を消せるよう、表示に戻るたびに確かめる
        noticeGranted = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
        noticeShownAt = SystemClock.elapsedRealtime()
        updateOverlayNotice()
        noticeHandler.removeCallbacks(hideNotice)
        if (overlayNotice.value) noticeHandler.postDelayed(hideNotice, AutoStart.NOTICE_SHOW_MS)
    }
}

@Composable
fun AppContent(unlockHandler: TapUnlockHandler, activity: Activity) {
    val context = LocalContext.current
    val musicBrowser = remember { RemoteMusicView(context) }
    // テレビ内の音楽操作サーバー（店のPCが店内LANで直接つなぐ）と本部への店内アドレス登録
    val musicHub = remember { org.screenlite.webkiosk.app.MusicHub(context, musicBrowser) }
    DisposableEffect(musicBrowser) {
        musicHub.start()
        onDispose { musicHub.stop(); musicBrowser.shutdown() }
    }
    val pinText by musicHub.pinText
    val idleController = remember { (activity as MainActivity).idleController }
    val showOverlayNotice by (activity as MainActivity).overlayNotice
    val isIdleMode by idleController.isIdleMode.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d("MainActivity", "Notification permission granted: $isGranted")
    }

    LaunchedEffect(isIdleMode) {
        Log.d("IdleDebug", "Compose: isIdleMode state changed to: $isIdleMode")
    }

    LaunchedEffect(Unit) {
        if (!NotificationPermissionHelper.hasPermission(context)) {
            NotificationPermissionHelper.requestPermission(permissionLauncher)
        }
    }

    val isTv = isTvDevice()

    Box(Modifier.fillMaxSize().background(Color.White)) {
        // 音楽用ページは掲示の下に保持し、PCにはこのページの画像だけを送る。
        AndroidView(factory = { musicBrowser }, modifier = Modifier.fillMaxSize())
        // 掲示WebViewの読み込み中・透明部分でも音楽サイトをテレビには見せない。
        Box(Modifier.fillMaxSize().background(Color.Black))
        MainScreen(activity = activity, modifier = Modifier.fillMaxSize())

        // 下端: 自動起動できない注意の帯（掲示全体は覆わない）。音楽の接続番号はその上の右端に出して重ねない。
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            // 店のPCが番号を求めたときだけ、右下に小さく2分間出す
            pinText?.let { pin ->
                androidx.compose.material3.Text(
                    text = "音楽の接続番号 $pin",
                    color = Color.White,
                    fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp),
                    modifier = Modifier
                        .padding(24.dp)
                        .background(Color(0xCC1E3A5F))
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
            if (showOverlayNotice) {
                androidx.compose.material3.Text(
                    text = AutoStart.NOTICE,
                    color = Color.White,
                    fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xEEB71C1C))
                        .padding(horizontal = 32.dp, vertical = 14.dp)
                )
            }
        }

        // サンミ改造: テレビでは入力をふさぐ板（TvKioskInputOverlay）を置かない
        if (!isTv) {
            TouchKioskInputOverlay(
                onTap = { unlockHandler.registerTap() },
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        val idleFocusRequester = remember { FocusRequester() }

        if (isIdleMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        idleController.onUserInteraction()
                    }
                    .focusable()
                    .focusRequester(idleFocusRequester)
                    .onKeyEvent {
                        if (it.key == Key.DirectionCenter &&
                            it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                            idleController.onUserInteraction()
                            true
                        } else {
                            false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {}
        }
    }
}
