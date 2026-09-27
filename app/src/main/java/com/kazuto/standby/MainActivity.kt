package com.kazuto.standby

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kazuto.standby.media.MediaSessionWatcher
import com.kazuto.standby.service.ChargingWatchService
import com.kazuto.standby.spotify.SpotifyAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Spotify サインインの結果メッセージ(設定画面に出す) */
    private val spotifyMessage = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen(spotifyMessage)
                }
            }
        }
        handleSpotifyRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirect(intent)
    }

    /** ブラウザでの Spotify サインインから staaaaandby://spotify-callback で戻ってきたとき */
    private fun handleSpotifyRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "staaaaandby") return
        intent.data = null
        lifecycleScope.launch {
            val result = SpotifyAuth.handleRedirect(this@MainActivity, uri)
            spotifyMessage.value = result.fold(
                onSuccess = { getString(R.string.spotify_connected_msg) },
                onFailure = { it.message ?: it.toString() },
            )
        }
    }
}

@Composable
private fun SetupScreen(spotifyMessage: StateFlow<String?>) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun notificationAccessGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    var notifGranted by remember { mutableStateOf(notificationAccessGranted()) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var musicApps by remember { mutableStateOf(loadInstalledMusicApps(context)) }

    // 設定アプリから戻ってきたタイミングで状態を取り直す
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = notificationAccessGranted()
                overlayGranted = Settings.canDrawOverlays(context)
                musicApps = loadInstalledMusicApps(context)
                if (overlayGranted) {
                    // 権限が揃っていたら監視サービスを起動しておく
                    ChargingWatchService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.setup_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SetupStepCard(
            step = "1",
            title = stringResource(R.string.step_notification_title),
            description = stringResource(R.string.step_notification_desc),
            done = notifGranted,
            buttonLabel = stringResource(R.string.step_notification_button),
            onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        )

        SetupStepCard(
            step = "2",
            title = stringResource(R.string.step_overlay_title),
            description = stringResource(R.string.step_overlay_desc),
            done = overlayGranted,
            buttonLabel = stringResource(R.string.step_overlay_button),
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        )

        MusicAppsBatteryCard(
            step = "3",
            apps = musicApps,
            onOpenSettings = { pkg ->
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$pkg")
                    )
                )
            }
        )

        PreferencesCard()

        SpotifyCard(message = spotifyMessage)

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.setup_footer),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SetupStepCard(
    step: String,
    title: String,
    description: String,
    done: Boolean,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$step. $title",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (done) stringResource(R.string.status_done)
                    else stringResource(R.string.status_todo),
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Button(onClick = onClick) {
                Text(buttonLabel)
            }
        }
    }
}

/** 設定画面に並べる、インストール済み音楽アプリの状態 */
private data class MusicAppStatus(
    val packageName: String,
    val label: String,
    /** バッテリー最適化の対象外(「制限なし」)になっていれば true */
    val unrestricted: Boolean,
)

/**
 * 対応する音楽アプリのうち端末に入っているものを、
 * バッテリー最適化の除外状態つきで返す。
 */
private fun loadInstalledMusicApps(context: Context): List<MusicAppStatus> {
    val pm = context.packageManager
    val power = context.getSystemService(PowerManager::class.java)
    return MediaSessionWatcher.MUSIC_APP_PACKAGES.mapNotNull { pkg ->
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            ?: return@mapNotNull null
        MusicAppStatus(
            packageName = pkg,
            label = pm.getApplicationLabel(info).toString(),
            unrestricted = power.isIgnoringBatteryOptimizations(pkg),
        )
    }.sortedBy { it.label.lowercase() }
}

@Composable
private fun MusicAppsBatteryCard(
    step: String,
    apps: List<MusicAppStatus>,
    onOpenSettings: (packageName: String) -> Unit
) {
    val allDone = apps.isNotEmpty() && apps.all { it.unrestricted }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$step. ${stringResource(R.string.step_battery_title)}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (apps.isNotEmpty()) {
                    StatusLabel(done = allDone)
                }
            }
            Text(
                text = stringResource(R.string.step_battery_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(R.string.step_battery_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            apps.forEach { app ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = app.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        StatusLabel(done = app.unrestricted)
                    }
                    Button(onClick = { onOpenSettings(app.packageName) }) {
                        Text(stringResource(R.string.step_battery_button))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLabel(done: Boolean) {
    Text(
        text = if (done) stringResource(R.string.status_done)
        else stringResource(R.string.status_todo),
        color = if (done) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp
    )
}

/** 起動条件の好み: ケーブル充電でも起動するか / 縦向きでも起動するか */
@Composable
private fun PreferencesCard() {
    val context = LocalContext.current
    var triggerOnWired by remember { mutableStateOf(Prefs.triggerOnWired(context)) }
    var allowPortrait by remember { mutableStateOf(Prefs.allowPortrait(context)) }
    var lyricVideo by remember { mutableStateOf(Prefs.lyricVideo(context)) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.prefs_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            PreferenceSwitch(
                title = stringResource(R.string.pref_wired_title),
                description = stringResource(R.string.pref_wired_desc),
                checked = triggerOnWired,
                onCheckedChange = {
                    triggerOnWired = it
                    Prefs.setTriggerOnWired(context, it)
                }
            )
            PreferenceSwitch(
                title = stringResource(R.string.pref_portrait_title),
                description = stringResource(R.string.pref_portrait_desc),
                checked = allowPortrait,
                onCheckedChange = {
                    allowPortrait = it
                    Prefs.setAllowPortrait(context, it)
                }
            )
            PreferenceSwitch(
                title = stringResource(R.string.pref_lyric_title),
                description = stringResource(R.string.pref_lyric_desc),
                checked = lyricVideo,
                onCheckedChange = {
                    lyricVideo = it
                    Prefs.setLyricVideo(context, it)
                }
            )
        }
    }
}

/**
 * Spotify Web API との連携(任意)。自分で作った Spotify アプリの Client ID を保存し、
 * ブラウザでサインインする。戻り先は MainActivity の intent-filter。
 */
@Composable
private fun SpotifyCard(message: StateFlow<String?>) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var clientId by remember { mutableStateOf(Prefs.spotifyClientId(context) ?: "") }
    var connected by remember { mutableStateOf(SpotifyAuth.isConnected(context)) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val redirectMessage by message.collectAsState()

    // サインインから戻ってきたら接続状態を取り直す
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) connected = SpotifyAuth.isConnected(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(redirectMessage) {
        if (redirectMessage != null) {
            connected = SpotifyAuth.isConnected(context)
            localMessage = redirectMessage
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.spotify_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (connected) stringResource(R.string.spotify_connected)
                    else stringResource(R.string.spotify_not_connected),
                    color = if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
            Text(
                text = stringResource(R.string.spotify_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Text(
                text = stringResource(R.string.spotify_redirect_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Text(
                text = SpotifyAuth.REDIRECT_URI,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            )
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text(stringResource(R.string.spotify_client_id)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    val id = clientId.trim()
                    if (!Regex("[0-9a-fA-F]{32}").matches(id)) {
                        localMessage = context.getString(R.string.spotify_no_client)
                        return@Button
                    }
                    Prefs.setSpotifyClientId(context, id)
                    connected = SpotifyAuth.isConnected(context)
                    localMessage = if (SpotifyAuth.beginLogin(context)) null
                    else context.getString(R.string.spotify_open_failed)
                }) {
                    Text(stringResource(R.string.spotify_connect))
                }
                if (connected) {
                    TextButton(onClick = {
                        SpotifyAuth.disconnect(context)
                        connected = false
                        localMessage = null
                    }) {
                        Text(stringResource(R.string.spotify_disconnect))
                    }
                }
            }
            localMessage?.let {
                Text(text = it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun PreferenceSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
