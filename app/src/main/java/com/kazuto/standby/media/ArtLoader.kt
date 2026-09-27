package com.kazuto.standby.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** https のアートワークを Bitmap に読む。失敗したら null。 */
object ArtLoader {
    suspend fun load(url: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
