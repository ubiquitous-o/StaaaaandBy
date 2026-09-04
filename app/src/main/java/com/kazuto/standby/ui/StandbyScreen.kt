package com.kazuto.standby.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kazuto.standby.R
import com.kazuto.standby.media.MediaSessionWatcher
import com.kazuto.standby.media.NowPlaying
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ScreenBg = Color.Black
private val TextPrimary = Color(0xFFF2F3F5)
private val AccentGreen = Color(0xFF57D98A)

private val OverlayShadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 3f),
    blurRadius = 16f
)

// 曲情報用: 小さい文字でも輪郭が立つ、近くて濃い影
private val TrackShadow = Shadow(
    color = Color.Black.copy(alpha = 0.9f),
    offset = Offset(0f, 2f),
    blurRadius = 8f
)

// 時刻用: 等幅フォント。字幅が揃うので時刻が変わっても位置がブレない
// Fira Code は可変フォントなので wght=700 を指定して Bold として使う
@OptIn(ExperimentalTextApi::class)
private val TimeFontFamily = FontFamily(
    Font(
        R.font.fira_code,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    )
)

// 日付・バッテリー用: 時計と同じ Fira Code の Medium 相当
@OptIn(ExperimentalTextApi::class)
private val LabelFontFamily = FontFamily(
    Font(
        R.font.fira_code,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    )
)

/** 画面の向きごとのレイアウト値。縦画面は幅が狭いので少し詰める */
private class Layout(
    val timeSize: TextUnit,
    val dateSize: TextUnit,
    val dateSpacing: TextUnit,
    val trackStart: Dp,
    val trackBottom: Dp,
    val trackWidthFraction: Float,
)

private val LandscapeLayout = Layout(
    timeSize = 120.sp, dateSize = 24.sp, dateSpacing = 4.sp,
    trackStart = 36.dp, trackBottom = 30.dp, trackWidthFraction = 0.62f,
)
private val PortraitLayout = Layout(
    timeSize = 84.sp, dateSize = 20.sp, dateSpacing = 3.sp,
    trackStart = 24.dp, trackBottom = 40.dp, trackWidthFraction = 0.88f,
)

@Composable
fun StandbyScreen(mediaWatcher: MediaSessionWatcher, onDismiss: () -> Unit) {
    val now by rememberCurrentTime()
    val battery by rememberBatteryStatus()
    val nowPlaying by mediaWatcher.nowPlaying.collectAsState()
    // タップ処理の中から最新の値を読むため(pointerInput は再起動しない)
    val hasMusic by rememberUpdatedState(nowPlaying != null)
    val dismiss by rememberUpdatedState(onDismiss)
    val isPortrait =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val layout = if (isPortrait) PortraitLayout else LandscapeLayout

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBg)
            // 曲情報あり: 画面3分割タップ: 左=前の曲 / 中央=再生停止 / 右=次の曲
            // 曲情報なし(時計だけの黒画面): どこをタップしてもスタンバイを閉じて
            // 普通のロック画面に戻す。鏡切れ等で操作できない状態からの脱出口
            .pointerInput(mediaWatcher) {
                detectTapGestures { offset ->
                    if (!hasMusic) {
                        dismiss()
                        return@detectTapGestures
                    }
                    val third = size.width / 3f
                    when {
                        offset.x < third -> mediaWatcher.skipToPrevious()
                        offset.x > third * 2 -> mediaWatcher.skipToNext()
                        else -> mediaWatcher.playPause()
                    }
                }
            }
    ) {
        val playing = nowPlaying
        val art = playing?.albumArt
        if (art != null) {
            // 毎フレーム再生位置を計算してスリットを滑らかに動かす。
            // 一時停止中は値が変わらないので再描画も起きない。
            val progress by produceState(initialValue = 0f, playing) {
                while (true) {
                    withFrameMillis {
                        value = playing?.progressAt(SystemClock.elapsedRealtime()) ?: 0f
                    }
                }
            }
            SlitScanArtwork(
                art = art,
                progress = progress,
                modifier = Modifier.fillMaxSize()
            )
        }
        ClockOverlay(
            now = now,
            battery = battery,
            layout = layout,
            modifier = Modifier.align(Alignment.Center)
        )
        playing?.let { p ->
            TrackInfo(
                playing = p,
                layout = layout,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = layout.trackStart, bottom = layout.trackBottom)
            )
        }
    }
}

/** 左下のターミナル風「再生中」表示。長い文字列は領域内でマーキースクロールする。 */
@Composable
private fun TrackInfo(playing: NowPlaying, layout: Layout, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(layout.trackWidthFraction)) {
        MarqueeText(
            text = playing.title.uppercase(Locale.ENGLISH),
            style = TextStyle(
                color = TextPrimary,
                fontFamily = LabelFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 24.sp,
                letterSpacing = 1.sp,
                shadow = TrackShadow
            )
        )
        MarqueeText(
            text = playing.artist.uppercase(Locale.ENGLISH),
            style = TextStyle(
                color = TextPrimary,
                fontFamily = LabelFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                letterSpacing = 1.sp,
                shadow = TrackShadow
            )
        )
    }
}

/**
 * 影が切れないマーキー。basicMarquee は自分の枠でクリップするので、
 * 内側に余白を挟んで影のにじみぶんの逃げ場を作る。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarqueeText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            repeatDelayMillis = 2_000,
            initialDelayMillis = 2_000,
        )
    ) {
        Text(
            text = text,
            style = style,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}

/**
 * 横画面: 正方形のアートワークを高さいっぱいに描き、再生位置に対応する縦1列を
 * 横に引き伸ばして(スリットスキャン)、画面の余り幅を埋める。
 * 引き伸ばし幅 = 画面幅 - 画面高さ で、画面サイズに自動追従する。
 * 縦画面: 同じことを縦方向に行い、スリットは上から下へ移動する。
 */
@Composable
private fun SlitScanArtwork(art: Bitmap, progress: Float, modifier: Modifier = Modifier) {
    val image: ImageBitmap = remember(art) { art.asImageBitmap() }
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val srcW = image.width
        val srcH = image.height
        if (srcW <= 0 || srcH <= 0) return@Canvas

        // すべてfloatで計算し、クリップ+変換で描くことでサブピクセル単位で滑らかに動かす
        val srcWf = srcW.toFloat()
        val srcHf = srcH.toFloat()

        if (w <= h) {
            // 縦画面: アートを幅いっぱいに描き、再生位置の横1行を縦に引き伸ばして
            // 余った高さを埋める。スリットは上から下へ移動する
            val artDstH = w                          // 正方形アートの表示高さ(幅いっぱい)
            val slitDstH = h - artDstH               // スリットで埋める高さ
            val slitSrcY = progress * (srcHf - 1f)   // 引き伸ばす横1行の位置
            val topDstH = progress * artDstH         // スリットより上の表示高さ
            val sx = w / srcWf

            // スリットより上の部分: src[0..slitSrcY] → dst[0..topDstH]
            if (topDstH > 0.5f && slitSrcY > 0.5f) {
                clipRect(0f, 0f, w, topDstH) {
                    scale(scaleX = sx, scaleY = topDstH / slitSrcY, pivot = Offset.Zero) {
                        drawImage(image)
                    }
                }
            }
            // 再生位置の横1行を縦に引き伸ばす: src[slitSrcY..slitSrcY+1] → dst[topDstH..topDstH+slitDstH]
            if (slitDstH > 0.5f) {
                clipRect(0f, topDstH, w, topDstH + slitDstH) {
                    translate(top = topDstH - slitSrcY * slitDstH) {
                        scale(scaleX = sx, scaleY = slitDstH, pivot = Offset.Zero) {
                            drawImage(image)
                        }
                    }
                }
            }
            // スリットより下の部分: src[slitSrcY..srcH] → dst[topDstH+slitDstH..h]
            val bottomDstH = h - topDstH - slitDstH
            if (bottomDstH > 0.5f && srcHf - slitSrcY > 0.5f) {
                val sy = bottomDstH / (srcHf - slitSrcY)
                clipRect(0f, topDstH + slitDstH, w, h) {
                    translate(top = topDstH + slitDstH - slitSrcY * sy) {
                        scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                            drawImage(image)
                        }
                    }
                }
            }
            return@Canvas
        }

        val artDstW = h                          // 正方形アートの表示幅(高さいっぱい)
        val slitDstW = w - artDstW               // スリットで埋める幅
        val slitSrcX = progress * (srcWf - 1f)   // 引き伸ばす縦1列の位置
        val leftDstW = progress * artDstW        // スリットより左の表示幅
        val sy = h / srcHf

        // スリットより左の部分: src[0..slitSrcX] → dst[0..leftDstW]
        if (leftDstW > 0.5f && slitSrcX > 0.5f) {
            clipRect(0f, 0f, leftDstW, h) {
                scale(scaleX = leftDstW / slitSrcX, scaleY = sy, pivot = Offset.Zero) {
                    drawImage(image)
                }
            }
        }
        // 再生位置の縦1列を横に引き伸ばす: src[slitSrcX..slitSrcX+1] → dst[leftDstW..leftDstW+slitDstW]
        clipRect(leftDstW, 0f, leftDstW + slitDstW, h) {
            translate(left = leftDstW - slitSrcX * slitDstW) {
                scale(scaleX = slitDstW, scaleY = sy, pivot = Offset.Zero) {
                    drawImage(image)
                }
            }
        }
        // スリットより右の部分: src[slitSrcX..srcW] → dst[leftDstW+slitDstW..w]
        val rightDstW = w - leftDstW - slitDstW
        if (rightDstW > 0.5f && srcWf - slitSrcX > 0.5f) {
            val sx = rightDstW / (srcWf - slitSrcX)
            clipRect(leftDstW + slitDstW, 0f, w, h) {
                translate(left = leftDstW + slitDstW - slitSrcX * sx) {
                    scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
                        drawImage(image)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockOverlay(
    now: LocalDateTime,
    battery: BatteryStatus,
    layout: Layout,
    modifier: Modifier = Modifier
) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH) }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = now.format(timeFormatter),
            style = TextStyle(
                color = TextPrimary,
                fontFamily = TimeFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = layout.timeSize,
                shadow = OverlayShadow
            ),
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = now.format(dateFormatter).uppercase(Locale.ENGLISH),
            style = TextStyle(
                color = TextPrimary.copy(alpha = 0.85f),
                fontFamily = LabelFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = layout.dateSize,
                letterSpacing = layout.dateSpacing,
                shadow = OverlayShadow
            )
        )
        Text(
            text = if (battery.isCharging) "⚡ ${battery.level}%" else "${battery.level}%",
            style = TextStyle(
                color = if (battery.isCharging) AccentGreen else TextPrimary.copy(alpha = 0.7f),
                fontFamily = LabelFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                shadow = OverlayShadow
            ),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun rememberCurrentTime(): State<LocalDateTime> =
    produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            // 毎秒更新: 時計と、スリットの再生位置追従の両方に使う
            delay(1_000 - System.currentTimeMillis() % 1_000)
        }
    }

data class BatteryStatus(val level: Int, val isCharging: Boolean)

@Composable
private fun rememberBatteryStatus(): State<BatteryStatus> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val status = remember { mutableStateOf(BatteryStatus(level = 0, isCharging = true)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                if (level >= 0 && scale > 0) {
                    status.value = BatteryStatus(
                        level = level * 100 / scale,
                        isCharging = plugged != 0
                    )
                }
            }
        }
        // ACTION_BATTERY_CHANGED は sticky なので登録直後に現在値が届く
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return status
}
