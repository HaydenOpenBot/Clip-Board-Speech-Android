package com.clipboardspeech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Cloud TTS via MiniMax T2A v2 (/v1/t2a_v2). Each chunk is synthesized to an mp3 in cacheDir
 * and played with MediaPlayer; upcoming groups are prefetched while the current one plays.
 * All callbacks are delivered on the main thread.
 */
class MiniMaxTts(private val context: Context) {

    data class Config(
        val enabled: Boolean,
        val apiKey: String,
        val host: String,
        val model: String,
        val voiceId: String
    )

    companion object {
        const val KEY_MINIMAX_CONFIG = "minimax_tts_config"
        const val DEFAULT_HOST = "https://api.minimax.io"
        const val DEFAULT_MODEL = "speech-02-turbo"
        const val DEFAULT_VOICE = "female-shaonv"

        fun loadConfig(context: Context): Config {
            val json = context.getSharedPreferences(AiConfigFragment.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_MINIMAX_CONFIG, null)
            val obj = try { JSONObject(json ?: "{}") } catch (_: Exception) { JSONObject() }
            return Config(
                enabled = obj.optBoolean("enabled", false),
                apiKey = obj.optString("apiKey"),
                host = obj.optString("host").ifBlank { DEFAULT_HOST },
                model = obj.optString("model").ifBlank { DEFAULT_MODEL },
                voiceId = obj.optString("voiceId").ifBlank { DEFAULT_VOICE }
            )
        }

        fun saveConfig(context: Context, config: Config) {
            val obj = JSONObject().apply {
                put("enabled", config.enabled)
                put("apiKey", config.apiKey)
                put("host", config.host)
                put("model", config.model)
                put("voiceId", config.voiceId)
            }
            context.getSharedPreferences(AiConfigFragment.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_MINIMAX_CONFIG, obj.toString()).apply()
        }

        /** Returns the active config, or null when MiniMax is off or has no key. */
        fun activeConfig(context: Context): Config? =
            loadConfig(context).takeIf { it.enabled && it.apiKey.isNotBlank() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ponytail: unbounded per-session cache, cleared by clearCache() on each new read-through
    private val cache = ConcurrentHashMap<String, Deferred<File>>()
    private var player: MediaPlayer? = null
    private var playJob: Job? = null
    @Volatile private var generation = 0

    /**
     * Play [text]. [next] upcoming groups are prefetched so playback doesn't stall between
     * chunks. [rate] 0.5..2.0 maps 1:1 to MiniMax speed; [pitch] 0.5..2.0 maps to -12..12.
     */
    fun speak(
        config: Config, text: String, next: List<String>, rate: Float, pitch: Float,
        onDone: () -> Unit, onError: (String) -> Unit
    ) {
        stop()
        val gen = generation
        val audio = fetch(config, text, rate, pitch)
        next.forEach { fetch(config, it, rate, pitch) }

        playJob = scope.launch {
            val file = try {
                audio.await()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (gen == generation) onError(e.message ?: "MiniMax 語音合成失敗")
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (gen != generation) return@withContext
                try {
                    player = MediaPlayer().apply {
                        setWakeMode(context, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(file.path)
                        setOnCompletionListener {
                            it.release()
                            if (player === it) player = null
                            if (gen == generation) onDone()
                        }
                        setOnErrorListener { mp, what, _ ->
                            mp.release()
                            if (player === mp) player = null
                            if (gen == generation) onError("MiniMax 音訊播放失敗 ($what)")
                            true
                        }
                        prepare()
                        start()
                    }
                } catch (e: Exception) {
                    onError("MiniMax 音訊播放失敗：${e.message}")
                }
            }
        }
    }

    /** Stop playback. Pending callbacks from the stopped chunk are dropped. Cache is kept. */
    fun stop() {
        generation++
        playJob?.cancel()
        playJob = null
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }

    fun clearCache() {
        cache.clear()
        context.cacheDir.listFiles { f -> f.name.startsWith("minimax_") }?.forEach { it.delete() }
    }

    fun release() {
        stop()
        scope.cancel()
        clearCache()
    }

    private fun fetch(config: Config, text: String, rate: Float, pitch: Float): Deferred<File> {
        val key = listOf(config.host, config.model, config.voiceId, rate, pitch, text).joinToString("|")
        return cache.getOrPut(key) {
            scope.async {
                try {
                    synthesize(config, text, rate, pitch, key.hashCode())
                } catch (e: Exception) {
                    cache.remove(key)   // let a retry hit the network again
                    throw e
                }
            }
        }
    }

    private fun synthesize(config: Config, text: String, rate: Float, pitch: Float, id: Int): File {
        val body = JSONObject().apply {
            put("model", config.model)
            put("text", text)
            put("stream", false)
            put("language_boost", "auto")
            put("voice_setting", JSONObject().apply {
                put("voice_id", config.voiceId)
                put("speed", rate.coerceIn(0.5f, 2.0f).toDouble())
                put("vol", 1.0)
                put("pitch", ((pitch - 1f) * 12f).roundToInt().coerceIn(-12, 12))
            })
            put("audio_setting", JSONObject().apply {
                put("sample_rate", 32000)
                put("bitrate", 128000)
                put("format", "mp3")
                put("channel", 1)
            })
        }.toString()

        val request = Request.Builder()
            .url(config.host.trimEnd('/') + "/v1/t2a_v2")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IOException("MiniMax HTTP ${response.code}")
            val json = JSONObject(raw)
            val base = json.optJSONObject("base_resp")
            val status = base?.optInt("status_code", 0) ?: 0
            if (status != 0) throw IOException("MiniMax 錯誤 $status：${base?.optString("status_msg")}")
            val hex = json.optJSONObject("data")?.optString("audio").orEmpty()
            if (hex.isEmpty()) throw IOException("MiniMax 未回傳音訊")
            val file = File(context.cacheDir, "minimax_$id.mp3")
            file.writeBytes(hexToBytes(hex))
            return file
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
