package com.kazuto.standby.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.kazuto.standby.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify Web API のサインイン(Authorization Code + PKCE、クライアントシークレット無し)と
 * トークン管理。jizura-sync の web/spotify.js と同じ流れを Android に置き換えたもの。
 *
 * ユーザーが自分で作った Spotify アプリの Client ID を設定画面に貼り、
 * ブラウザで同意すると [REDIRECT_URI] に戻ってきて MainActivity が [handleRedirect] を呼ぶ。
 * トークンは Prefs に保存し、accounts.spotify.com / api.spotify.com 以外には送らない。
 */
object SpotifyAuth {
    private const val TAG = "StaaaaandBy"
    private const val ACCOUNTS = "https://accounts.spotify.com"
    private const val API = "https://api.spotify.com/v1"
    private const val SCOPES = "user-read-playback-state user-modify-playback-state"

    /** Spotify アプリ側に登録してもらう Redirect URI(カスタムスキーム)。Manifest の intent-filter と一致させる */
    const val REDIRECT_URI = "staaaaandby://spotify-callback"

    enum class Kind { AUTH, NOT_REGISTERED, NO_DEVICE, RATE_LIMITED, TRANSIENT, COMMAND }

    class SpotifyException(val kind: Kind, message: String, val retryAfterS: Int = 0) : Exception(message)

    class Response(val status: Int, val body: String, val retryAfterS: Int)

    private val refreshMutex = Mutex()

    fun isConnected(context: Context): Boolean = Prefs.spotifyTokens(context)?.refreshToken != null

    /** ブラウザで Spotify の同意画面を開く。Client ID 未設定なら false。 */
    fun beginLogin(context: Context): Boolean {
        val clientId = Prefs.spotifyClientId(context)?.takeIf { it.isNotBlank() } ?: return false
        val verifier = randomString(64)
        val state = randomString(16)
        Prefs.setSpotifyPkce(context, verifier, state)
        val challenge = base64Url(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        val url = "$ACCOUNTS/authorize?" + form(
            "response_type" to "code",
            "client_id" to clientId,
            "scope" to SCOPES,
            "redirect_uri" to REDIRECT_URI,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "state" to state,
        )
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
    }

    /** Spotify から戻ってきた URI を処理し、コードをトークンに交換する。 */
    suspend fun handleRedirect(context: Context, uri: Uri): Result<Unit> = runCatching {
        val pkce = Prefs.spotifyPkce(context)
        Prefs.clearSpotifyPkce(context)
        uri.getQueryParameter("error")?.let {
            throw SpotifyException(Kind.AUTH, "Spotify sign-in was declined ($it)")
        }
        val code = uri.getQueryParameter("code")
            ?: throw SpotifyException(Kind.AUTH, "No authorization code in the redirect")
        if (pkce == null || uri.getQueryParameter("state") != pkce.second) {
            throw SpotifyException(Kind.AUTH, "Spotify sign-in did not match this app — try again")
        }
        tokenRequest(
            context,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to pkce.first,
        )
    }

    /** トークンを忘れる。Spotify 側の許可は spotify.com/account/apps で外せる。 */
    fun disconnect(context: Context) = Prefs.clearSpotifyTokens(context)

    /** 有効なアクセストークン。期限が近ければ更新する。 */
    suspend fun accessToken(context: Context, force: Boolean = false): String {
        val t = Prefs.spotifyTokens(context)
            ?: throw SpotifyException(Kind.AUTH, "Not connected to Spotify")
        if (!force && t.accessToken != null && t.expiresAt - System.currentTimeMillis() > 60_000) {
            return t.accessToken
        }
        refreshMutex.withLock {
            val again = Prefs.spotifyTokens(context)
                ?: throw SpotifyException(Kind.AUTH, "Not connected to Spotify")
            if (!force && again.accessToken != null &&
                again.expiresAt - System.currentTimeMillis() > 60_000
            ) {
                return again.accessToken
            }
            tokenRequest(context, "grant_type" to "refresh_token", "refresh_token" to again.refreshToken)
        }
        return Prefs.spotifyTokens(context)?.accessToken
            ?: throw SpotifyException(Kind.AUTH, "Token refresh returned nothing")
    }

    private suspend fun tokenRequest(context: Context, vararg params: Pair<String, String>) {
        val clientId = Prefs.spotifyClientId(context)
            ?: throw SpotifyException(Kind.AUTH, "No Client ID")
        val res = http("POST", "$ACCOUNTS/api/token", form("client_id" to clientId, *params), null)
        val body = runCatching { JSONObject(res.body) }.getOrNull() ?: JSONObject()
        if (res.status !in 200..299) {
            // 400 invalid_grant = リフレッシュトークンが死んだ(取り消し / Client ID 変更): 入り直し
            if (res.status == 400) disconnect(context)
            val desc = body.optString("error_description").ifEmpty { body.optString("error") }
                .ifEmpty { res.status.toString() }
            throw SpotifyException(
                if (res.status == 400) Kind.AUTH else Kind.TRANSIENT,
                "Spotify token request failed: $desc"
            )
        }
        val prev = Prefs.spotifyTokens(context)
        Prefs.setSpotifyTokens(
            context,
            accessToken = body.getString("access_token"),
            // 更新でリフレッシュトークンが回らないこともある
            refreshToken = body.optString("refresh_token").ifEmpty { prev?.refreshToken ?: "" },
            expiresAt = System.currentTimeMillis() + body.optLong("expires_in", 3600) * 1000,
        )
    }

    /**
     * Web API を1回叩く。401 はトークンを更新して1回だけやり直す。
     * 2xx 以外は [SpotifyException] を投げる。
     */
    suspend fun api(context: Context, method: String, path: String, retried: Boolean = false): Response {
        val res = try {
            http(method, "$API$path", null, accessToken(context))
        } catch (e: SpotifyException) {
            throw e
        } catch (e: IOException) {
            throw SpotifyException(Kind.TRANSIENT, "Spotify unreachable (${e.message})")
        }
        if (res.status == 401 && !retried) {
            accessToken(context, force = true)
            return api(context, method, path, retried = true)
        }
        if (res.status in 200..299) return res
        val message = runCatching { JSONObject(res.body).getJSONObject("error").getString("message") }
            .getOrDefault(res.body)
        throw when {
            res.status == 401 -> SpotifyException(Kind.AUTH, "Spotify rejected the session: $message")
            // 開発モードのアプリは User Management に無いアカウントに全部 403 を返す
            res.status == 403 && message.contains("not registered", ignoreCase = true) ->
                SpotifyException(Kind.NOT_REGISTERED, "This Spotify account is not on the app's User Management list")
            res.status == 429 -> SpotifyException(Kind.RATE_LIMITED, "Spotify rate limit", res.retryAfterS)
            res.status >= 500 -> SpotifyException(Kind.TRANSIENT, "Spotify ${res.status}")
            else -> SpotifyException(Kind.COMMAND, "Spotify ${res.status}: $message")
        }
    }

    private suspend fun http(method: String, url: String, formBody: String?, bearer: String?): Response =
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
                if (formBody != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.use { it.write(formBody.toByteArray()) }
                } else if (method == "PUT" || method == "POST") {
                    // 本文なしの PUT/POST(play/pause/next)。Content-Length: 0 を明示する
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(0)
                    conn.outputStream.close()
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                val retry = conn.getHeaderField("Retry-After")?.toIntOrNull() ?: 0
                Response(status, body, retry)
            } finally {
                conn.disconnect()
            }
        }

    private fun form(vararg params: Pair<String, String>): String =
        params.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    private fun randomString(n: Int): String {
        val bytes = ByteArray(n).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes).take(n)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    @Suppress("unused")
    private fun log(msg: String) = Log.i(TAG, "spotify: $msg")
}
