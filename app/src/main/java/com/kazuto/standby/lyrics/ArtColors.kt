package com.kazuto.standby.lyrics

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * ジャケットの色から、JIZURA の配色(scheme)を2組作る。
 * 1組目は暗い地に明るい文字、2組目は明るい地に暗い文字(JIZURA は曲中で交互に使う)。
 * 文字の読みやすさを優先し、地と文字・アクセントのコントラストを WCAG の比率で保証する。
 */
object ArtColors {

    /** 本文(fg)と地のコントラスト。WCAG AAA 相当 */
    private const val FG_CONTRAST = 7.0
    /** アクセント(見出し・装飾の文字にも使われる)と地のコントラスト。WCAG AA 相当 */
    private const val ACCENT_CONTRAST = 4.5

    /** JIZURA の scheme 2組を JSON 配列で。ジャケットが単色すぎて色が取れなければ null */
    fun schemesFor(art: Bitmap): JSONArray? {
        val p = Palette.from(art).maximumColorCount(24).generate()
        val swatches = listOfNotNull(p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch,
            p.mutedSwatch, p.lightMutedSwatch, p.darkMutedSwatch, p.dominantSwatch)
        if (swatches.isEmpty()) return null
        val dominant = p.dominantSwatch?.rgb ?: swatches.first().rgb
        val vibrant = p.vibrantSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: p.darkVibrantSwatch?.rgb ?: dominant
        val vibrant2 = listOfNotNull(p.lightVibrantSwatch?.rgb, p.darkVibrantSwatch?.rgb, p.mutedSwatch?.rgb, p.lightMutedSwatch?.rgb)
            .firstOrNull { hueDistance(it, vibrant) > 25f } ?: shiftHue(vibrant, 40f)

        // 暗い地: 暗めの色があればそれ、無ければ主色を暗くする
        val darkBg = withLightness(p.darkMutedSwatch?.rgb ?: p.darkVibrantSwatch?.rgb ?: dominant, max = 0.16f)
        val dark = scheme(
            bg = darkBg,
            fg = ensureContrast(0xFFF5F3F0.toInt(), darkBg, FG_CONTRAST),
            accent = ensureContrast(vibrant, darkBg, ACCENT_CONTRAST),
            accent2 = ensureContrast(vibrant2, darkBg, ACCENT_CONTRAST),
            swap = false,
        )
        // 明るい地: 明るめの色があればそれ、無ければ主色を明るくする
        val lightBg = withLightness(p.lightMutedSwatch?.rgb ?: p.lightVibrantSwatch?.rgb ?: dominant, min = 0.86f)
        val light = scheme(
            bg = lightBg,
            fg = ensureContrast(0xFF101014.toInt(), lightBg, FG_CONTRAST),
            accent = ensureContrast(p.darkVibrantSwatch?.rgb ?: vibrant, lightBg, ACCENT_CONTRAST),
            accent2 = ensureContrast(vibrant2, lightBg, ACCENT_CONTRAST),
            swap = true,
        )
        return JSONArray().put(dark).put(light)
    }

    private fun scheme(bg: Int, fg: Int, accent: Int, accent2: Int, swap: Boolean): JSONObject =
        JSONObject()
            .put("bg", hex(bg))
            .put("fg", hex(fg))
            .put("sub", hex(ColorUtils.blendARGB(fg, bg, 0.3f)))
            .put("accent", hex(accent))
            .put("accent2", hex(accent2))
            .put("ink", hex(fg))
            .put("dim", hex(ColorUtils.blendARGB(bg, fg, 0.12f)))
            .put("ghostA", hex(accent))
            .put("ghostB", hex(accent2))
            .put("grad", JSONArray().put(hex(accent)).put(hex(accent2)))
            .put("swap", swap)

    /** 地に対してコントラストが足りるまで、色相を保ったまま明るさを動かす */
    private fun ensureContrast(color: Int, bg: Int, target: Double): Int {
        if (ColorUtils.calculateContrast(opaque(color), opaque(bg)) >= target) return opaque(color)
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(opaque(color), it) }
        val bgLum = ColorUtils.calculateLuminance(opaque(bg))
        val step = if (bgLum < 0.5) 0.04f else -0.04f   // 暗い地なら明るく、明るい地なら暗く
        var best = opaque(color)
        for (i in 1..24) {
            hsl[2] = (hsl[2] + step).coerceIn(0f, 1f)
            // 極端に明るく/暗くしても足りないときは彩度も落として純白/純黒に寄せる
            if (hsl[2] <= 0.02f || hsl[2] >= 0.98f) hsl[1] = max(0f, hsl[1] - 0.2f)
            best = ColorUtils.HSLToColor(hsl)
            if (ColorUtils.calculateContrast(best, opaque(bg)) >= target) return best
        }
        return best
    }

    private fun withLightness(color: Int, min: Float = 0f, max: Float = 1f): Int {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(opaque(color), it) }
        hsl[2] = hsl[2].coerceIn(min, max)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun shiftHue(color: Int, degrees: Float): Int {
        val hsl = FloatArray(3).also { ColorUtils.colorToHSL(opaque(color), it) }
        hsl[0] = (hsl[0] + degrees + 360f) % 360f
        hsl[1] = max(hsl[1], 0.5f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun hueDistance(a: Int, b: Int): Float {
        val ha = FloatArray(3).also { ColorUtils.colorToHSL(a, it) }[0]
        val hb = FloatArray(3).also { ColorUtils.colorToHSL(b, it) }[0]
        val d = kotlin.math.abs(ha - hb)
        return min(d, 360f - d)
    }

    private fun opaque(c: Int) = c or (0xFF shl 24)
    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
}
