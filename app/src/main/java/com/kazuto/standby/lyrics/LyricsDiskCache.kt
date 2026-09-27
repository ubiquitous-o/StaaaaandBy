package com.kazuto.standby.lyrics

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 同期歌詞のディスクキャッシュ。アプリのキャッシュ領域(`cacheDir/lyrics/`)に
 * 1曲1ファイルで置く。端末の「キャッシュを削除」で消える。
 *
 * 見つからなかった曲も覚える(LRCLIB に歌詞が後から投稿されることがあるので期限つき)。
 * 件数が上限を超えたら古いものから消す。読んだファイルは更新日時を触って新しくする(LRU)。
 */
class LyricsDiskCache(context: Context) {

    companion object {
        private const val TAG = "StaaaaandBy"
        private const val MAX_FILES = 300
        /** 「歌詞なし」の記録を信じる期間 */
        private const val NONE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }

    /** キャッシュの内容。hit == null は「探したが無かった」 */
    class Entry(val durationS: Int, val lrc: String?)

    private val dir = File(context.cacheDir, "lyrics").also { it.mkdirs() }

    fun get(key: String): Entry? {
        val f = file(key)
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText())
            if (o.optBoolean("none")) {
                if (System.currentTimeMillis() - o.optLong("at") > NONE_TTL_MS) {
                    f.delete()
                    return null
                }
                Entry(0, null)
            } else {
                Entry(o.optInt("durationS"), o.getString("lrc"))
            }
        }.onSuccess { f.setLastModified(System.currentTimeMillis()) }
            .onFailure { f.delete() }
            .getOrNull()
    }

    fun put(key: String, durationS: Int, lrc: String?) {
        val o = if (lrc == null) {
            JSONObject().put("none", true).put("at", System.currentTimeMillis())
        } else {
            JSONObject().put("durationS", durationS).put("lrc", lrc)
        }
        runCatching { file(key).writeText(o.toString()) }
            .onFailure { Log.w(TAG, "lyrics cache: write failed: ${it.message}") }
        prune()
    }

    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }
}
