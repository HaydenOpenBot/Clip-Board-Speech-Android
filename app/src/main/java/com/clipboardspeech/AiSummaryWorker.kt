package com.clipboardspeech

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clipboardspeech.data.AppDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiSummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ENTRY_ID = "entry_id"
        const val KEY_TEXT = "text"
        const val UNIQUE_WORK_NAME = "ai_summary_job"
        private const val MAX_ATTEMPTS = 3
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val entryId = inputData.getLong(KEY_ENTRY_ID, -1L)
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()

        val prefs = applicationContext.getSharedPreferences(
            AiConfigFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val configJson = prefs.getString(AiConfigFragment.KEY_AI_CONFIG, null)
            ?: return Result.failure()

        val config = try { JSONObject(configJson) } catch (_: Exception) { return Result.failure() }
        val apiKey = config.optString("apiKey").ifEmpty { return Result.failure() }
        val url = config.optString("url").trimEnd('/')
        val model = config.optString("model").ifEmpty { "gpt-4o-mini" }
        val systemPrompt = config.optString("systemPrompt").ifEmpty { "You are a helpful assistant." }

        val anthropic = url.contains("/anthropic/", ignoreCase = true) ||
                url.contains("anthropic.com", ignoreCase = true)
        val endpoint = if (anthropic) "$url/messages" else "$url/chat/completions"

        val body = if (anthropic) {
            JSONObject().apply {
                put("model", model)
                put("max_tokens", 1024)
                put("system", systemPrompt)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Summarize the following text concisely:\n\n$text")
                    })
                })
            }.toString()
        } else {
            JSONObject().apply {
                put("model", model)
                put("max_tokens", 512)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Summarize the following text concisely:\n\n$text")
                    })
                })
            }.toString()
        }

        return try {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .apply { if (anthropic) addHeader("anthropic-version", "2023-06-01") }
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
            }

            val result = JSONObject(responseBody)
            val content = if (anthropic) {
                val contentArr = result.getJSONArray("content")
                (0 until contentArr.length())
                    .mapNotNull { i ->
                        val obj = contentArr.getJSONObject(i)
                        if (obj.optString("type") == "text") obj.optString("text") else null
                    }
                    .firstOrNull()?.trim() ?: throw Exception("No text content in response")
            } else {
                result.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }

            if (entryId != -1L) {
                AppDatabase.get(applicationContext).historyDao().updateAiSummary(entryId, content)
            }

            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }
}
