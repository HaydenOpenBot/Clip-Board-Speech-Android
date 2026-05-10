package com.clipboardspeech

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.clipboardspeech.data.AppDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProcessNoteWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_CONTENT = "content"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_RECORD_ID = "record_id"
        const val UNIQUE_WORK_NAME = "process_note_job"
        private const val MAX_ATTEMPTS = 3
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val content = inputData.getString(KEY_CONTENT) ?: return Result.failure()
        val endpoint = inputData.getString(KEY_ENDPOINT) ?: return Result.failure()
        val recordId = inputData.getLong(KEY_RECORD_ID, -1L)

        val dao = AppDatabase.get(applicationContext).processNoteDao()

        return try {
            val body = JSONObject().apply { put("content", content) }.toString()
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = try { JSONObject(responseBody) } catch (_: Exception) { JSONObject() }
                val message = json.optString("message", "✅ 筆記已儲存")
                val inboxFile = json.optString("inboxFile", null)
                if (recordId != -1L) {
                    dao.updateResult(recordId, "success", message, inboxFile, System.currentTimeMillis())
                }
                Result.success()
            } else {
                // Non-retriable server error
                if (recordId != -1L) {
                    dao.updateResult(recordId, "failed", "伺服器錯誤 (${response.code})", null, System.currentTimeMillis())
                }
                Result.failure()
            }
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                if (recordId != -1L) {
                    dao.updateResult(recordId, "failed", "連線失敗：${e.message}", null, System.currentTimeMillis())
                }
                Result.failure()
            }
        }
    }
}
