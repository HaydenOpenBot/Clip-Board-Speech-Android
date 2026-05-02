package com.clipboardspeech

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.clipboardspeech.databinding.FragmentAiConfigBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class AiConfigFragment : Fragment() {

    private var _binding: FragmentAiConfigBinding? = null
    private val binding get() = _binding!!

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val PREFS_NAME = "cbs_prefs"
        const val KEY_AI_CONFIG = "ai_config"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAiConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadConfig()

        binding.etApiKey.doAfterTextChanged { text ->
            binding.btnClearAiConfig.visibility =
                if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            hideTestResult()
        }
        binding.etApiUrl.doAfterTextChanged { hideTestResult() }
        binding.etModel.doAfterTextChanged { hideTestResult() }

        binding.btnSaveAiConfig.setOnClickListener {
            saveConfig()
            binding.btnSaveAiConfig.text = "Saved \u2713"
            binding.btnSaveAiConfig.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(android.R.color.holo_green_dark)
                )
            Handler(Looper.getMainLooper()).postDelayed({
                if (_binding != null) {
                    binding.btnSaveAiConfig.text = "Save Configuration"
                    binding.btnSaveAiConfig.backgroundTintList =
                        requireContext().getColorStateList(
                            com.google.android.material.R.color.m3_button_background_color_selector
                        )
                }
            }, 2000)
        }

        binding.btnTestConnection.setOnClickListener {
            testConnection()
        }

        binding.btnClearAiConfig.setOnClickListener {
            requireContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_AI_CONFIG).apply()
            binding.etApiKey.setText("")
            binding.etApiUrl.setText("")
            binding.etModel.setText("")
            binding.etSystemPrompt.setText("")
            binding.btnClearAiConfig.visibility = View.GONE
            hideTestResult()
        }
    }

    // ── Test Connection ───────────────────────────────────────────────────────

    private fun testConnection() {
        val apiKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val url    = binding.etApiUrl.text?.toString()?.trim() ?: ""
        val model  = binding.etModel.text?.toString()?.trim() ?: ""

        // Client-side validation first — clear and specific errors
        if (apiKey.isEmpty()) {
            showTestResult(success = false, icon = "⚠️",
                msg = "API Key is required. Enter your key above before testing.")
            return
        }
        if (url.isEmpty()) {
            showTestResult(success = false, icon = "⚠️",
                msg = "API Base URL is required. Enter the endpoint URL above before testing.")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showTestResult(success = false, icon = "⚠️",
                msg = "Invalid URL format. The URL must start with https:// (e.g. https://api.openai.com/v1).")
            return
        }
        try { URL(url) } catch (_: MalformedURLException) {
            showTestResult(success = false, icon = "⚠️",
                msg = "Malformed URL. Check for typos — the URL should look like https://api.openai.com/v1.")
            return
        }
        if (model.isEmpty()) {
            showTestResult(success = false, icon = "⚠️",
                msg = "Model name is required. Enter the model identifier above (e.g. gpt-4o-mini).")
            return
        }

        // Show loading state
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = "Testing…"
        hideTestResult()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runTest(apiKey, url, model) }
            if (_binding == null) return@launch
            binding.btnTestConnection.isEnabled = true
            binding.btnTestConnection.text = "Test Connection"
            showTestResult(result.success, result.icon, result.message)
        }
    }

    data class TestResult(val success: Boolean, val icon: String, val message: String)

    /** True when the base URL targets an Anthropic-format endpoint (e.g. MiniMax /anthropic/v1). */
    private fun isAnthropicUrl(url: String) =
        url.contains("/anthropic/", ignoreCase = true) ||
        url.contains("anthropic.com", ignoreCase = true)

    private fun runTest(apiKey: String, url: String, model: String): TestResult {
        val anthropic = isAnthropicUrl(url)
        val endpoint  = url.trimEnd('/') + if (anthropic) "/messages" else "/chat/completions"

        val body = if (anthropic) {
            JSONObject().apply {
                put("model", model)
                put("max_tokens", 100)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Reply with only a random number between 1 and 100. No extra text.")
                    })
                })
            }.toString()
        } else {
            JSONObject().apply {
                put("model", model)
                put("max_tokens", 20)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Reply with only a random number between 1 and 100. No extra text.")
                    })
                })
            }.toString()
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .apply { if (anthropic) addHeader("anthropic-version", "2023-06-01") }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            when (code) {
                200, 201 -> {
                    // Parse the body to verify the key AND model are genuinely accepted.
                    // Some providers embed errors inside a 200 response body.
                    try {
                        val json = JSONObject(responseBody)
                        when {
                            json.has("error") -> {
                                // Provider returned 200 but with an error payload
                                val detail = parseErrorMessage(responseBody)
                                if (detail.contains("model", ignoreCase = true) ||
                                    detail.contains("not found", ignoreCase = true) ||
                                    detail.contains("does not exist", ignoreCase = true)) {
                                    TestResult(false, "❌",
                                        "Model not accepted. The model \"$model\" was rejected by this provider. Check the model name.")
                                } else if (detail.contains("auth", ignoreCase = true) ||
                                           detail.contains("key", ignoreCase = true) ||
                                           detail.contains("invalid", ignoreCase = true)) {
                                    TestResult(false, "🔑",
                                        "Authentication failed. The API responded with an error: $detail")
                                } else {
                                    TestResult(false, "❌",
                                        "API returned an error: $detail")
                                }
                            }
                            // ── Anthropic format: content[] array with type=text blocks ──
                            json.optJSONArray("content") != null -> {
                                val contentArr = json.getJSONArray("content")
                                val reply = (0 until contentArr.length())
                                    .mapNotNull { i ->
                                        val obj = contentArr.getJSONObject(i)
                                        if (obj.optString("type") == "text") obj.optString("text") else null
                                    }
                                    .firstOrNull()?.trim() ?: ""
                                val respondedModel = json.optString("model").ifEmpty { model }
                                val usage = json.optJSONObject("usage")
                                val inputTokens      = usage?.optInt("input_tokens",  -1) ?: -1
                                val outputTokens     = usage?.optInt("output_tokens", -1) ?: -1
                                val totalTokens      = if (inputTokens >= 0 && outputTokens >= 0) inputTokens + outputTokens else -1

                                val details = buildString {
                                    append("All verified! Your configuration is working correctly.\n\n")
                                    append("Model:  $respondedModel\n")
                                    if (reply.isNotEmpty()) append("Reply:  \"$reply\"\n")
                                    if (totalTokens >= 0) append("Tokens:  $inputTokens input + $outputTokens output = $totalTokens total")
                                }
                                TestResult(true, "✅", details.trim())
                            }

                            // ── OpenAI format: choices[] array ──
                            json.optJSONArray("choices") != null &&
                            json.optJSONArray("choices")!!.length() > 0 -> {
                                val reply = json.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .optJSONObject("message")
                                    ?.optString("content")?.trim() ?: ""
                                val respondedModel = json.optString("model").ifEmpty { model }
                                val usage = json.optJSONObject("usage")
                                val promptTokens     = usage?.optInt("prompt_tokens",     -1) ?: -1
                                val completionTokens = usage?.optInt("completion_tokens", -1) ?: -1
                                val totalTokens      = usage?.optInt("total_tokens",      -1) ?: -1

                                val details = buildString {
                                    append("All verified! Your configuration is working correctly.\n\n")
                                    append("Model:  $respondedModel\n")
                                    if (reply.isNotEmpty()) append("Reply:  \"$reply\"\n")
                                    if (totalTokens >= 0) append("Tokens:  $promptTokens prompt + $completionTokens completion = $totalTokens total")
                                }
                                TestResult(true, "✅", details.trim())
                            }
                            else -> {
                                // 200 but no choices — model may have returned empty output
                                TestResult(false, "⚠️",
                                    "API responded (200) but returned no output. The model \"$model\" may be invalid or unavailable on this endpoint. Check the model name.")
                            }
                        }
                    } catch (e: Exception) {
                        // Body is not JSON — still treat HTTP 200 as success
                        TestResult(true, "✅",
                            "Connection successful! The API responded correctly. Your configuration is ready to use.")
                    }
                }

                400 -> {
                    // Some providers return 400 for unknown model but auth is still valid
                    val detail = parseErrorMessage(responseBody)
                    if (detail.contains("model", ignoreCase = true)) {
                        TestResult(false, "❌",
                            "Model not found (400). The model \"$model\" was not recognised by this provider. Check the model name.")
                    } else {
                        TestResult(false, "❌",
                            "Bad request (400). The server rejected the request. $detail")
                    }
                }

                401 -> TestResult(false, "🔑",
                    "Authentication failed (401). Your API Key is invalid or expired. Double-check the key and try again.")

                403 -> TestResult(false, "🚫",
                    "Access forbidden (403). Your API Key does not have permission to use this endpoint or model.")

                404 -> TestResult(false, "🔍",
                    "Endpoint not found (404). The API Base URL may be incorrect. Make sure it ends with /v1 or the correct path for your provider.")

                422 -> TestResult(false, "⚠️",
                    "Unprocessable request (422). The model name or request format may not be supported by this provider.")

                429 -> TestResult(false, "⏳",
                    "Rate limit exceeded (429). Too many requests. Wait a moment and try again, or check your usage quota.")

                in 500..599 -> TestResult(false, "🔥",
                    "Server error ($code). The AI provider is experiencing issues on their end. Try again in a few minutes.")

                else -> TestResult(false, "❓",
                    "Unexpected response ($code). ${parseErrorMessage(responseBody)}")
            }

        } catch (e: UnknownHostException) {
            TestResult(false, "🌐",
                "Cannot reach the server. Check that the API Base URL is correct and that your device has an internet connection.")
        } catch (e: SocketTimeoutException) {
            TestResult(false, "⏱️",
                "Connection timed out. The server did not respond within 30 seconds. It may be down or the URL may be wrong.")
        } catch (e: SSLException) {
            TestResult(false, "🔒",
                "SSL/TLS error. The server's security certificate could not be verified. Make sure the URL uses https:// and is correct.")
        } catch (e: MalformedURLException) {
            TestResult(false, "⚠️",
                "Malformed URL: ${e.message}. Check the API Base URL for typos.")
        } catch (e: Exception) {
            TestResult(false, "❌",
                "Connection failed: ${e.message ?: "Unknown error"}.")
        }
    }

    private fun parseErrorMessage(body: String): String {
        return try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotEmpty() } ?: body.take(120)
        } catch (_: Exception) {
            body.take(120)
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showTestResult(success: Boolean, icon: String, msg: String) {
        val card = binding.cardTestResult
        val ctx = requireContext()

        if (success) {
            card.setBackgroundColor(Color.parseColor("#E6F4EA"))  // light green
            binding.tvTestResultMsg.setTextColor(Color.parseColor("#1E6B3C"))
        } else {
            card.setBackgroundColor(Color.parseColor("#FCE8E6"))  // light red
            binding.tvTestResultMsg.setTextColor(Color.parseColor("#B3261E"))
        }
        // Rounded background via drawable
        card.background = ctx.getDrawable(
            if (success) R.drawable.bg_test_success else R.drawable.bg_test_error
        )

        binding.tvTestResultIcon.text = icon
        binding.tvTestResultMsg.text = msg
        card.visibility = View.VISIBLE
    }

    private fun hideTestResult() {
        _binding?.cardTestResult?.visibility = View.GONE
    }

    // ── Config persistence ────────────────────────────────────────────────────

    private fun loadConfig() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_AI_CONFIG, null) ?: return
        try {
            val obj = JSONObject(json)
            binding.etApiKey.setText(obj.optString("apiKey"))
            binding.etApiUrl.setText(obj.optString("url"))
            binding.etModel.setText(obj.optString("model"))
            binding.etSystemPrompt.setText(obj.optString("systemPrompt"))
            binding.btnClearAiConfig.visibility =
                if (obj.optString("apiKey").isNotEmpty()) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    private fun saveConfig() {
        val obj = JSONObject().apply {
            put("apiKey", binding.etApiKey.text?.toString() ?: "")
            put("url", binding.etApiUrl.text?.toString() ?: "")
            put("model", binding.etModel.text?.toString() ?: "")
            put("systemPrompt", binding.etSystemPrompt.text?.toString() ?: "")
        }
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AI_CONFIG, obj.toString()).apply()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
