package org.screenlite.webkiosk

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import org.screenlite.webkiosk.app.AutoReturnSetting
import org.screenlite.webkiosk.components.FocusableButton
import org.screenlite.webkiosk.components.SettingsScreen
import org.screenlite.webkiosk.ui.theme.DarkBackgroundColor
import org.screenlite.webkiosk.ui.theme.TvSettingsTheme

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(DarkBackgroundColor.toArgb().toDrawable())
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        setContent {
            TvSettingsTheme {
                SettingsScreen()
            }
        }
    }
}

/** 「ホームに出たとき自動で掲示に戻る」のオン/オフ。押すと切り替えて、すぐ保存する。 */
@Composable
fun AutoReturnToggle() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(AutoReturnSetting.get(context)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FocusableButton(
            text = "ホームに出たとき自動で掲示に戻る：" + if (on) "オン" else "オフ",
            onClick = {
                on = !on
                AutoReturnSetting.set(context, on)
            },
            background = MaterialTheme.colorScheme.surface
        )
        Text(
            text = "オフでも、テレビの電源を入れたときは掲示を開きます",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB0B8C4),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Preview(
    name = "UHD TV Preview",
    device = "spec:width=3840px,height=2160px,dpi=320",
    uiMode = Configuration.UI_MODE_TYPE_TELEVISION
)
@Composable
fun SettingsScreenUhdTvPreview() {
    TvSettingsTheme { SettingsScreen() }
}

@Preview(name = "Settings Screen TV Preview", device = "id:tv_1080p")
@Composable
fun SettingsScreenTvPreview() {
    TvSettingsTheme { SettingsScreen() }
}

@Preview(name = "Settings Screen Phone Preview", device = "id:pixel_4")
@Composable
fun SettingsScreenPhonePreview() {
    TvSettingsTheme { SettingsScreen() }
}