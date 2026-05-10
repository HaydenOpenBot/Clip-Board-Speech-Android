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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipboardspeech.data.AppDatabase
import com.clipboardspeech.data.ProcessNoteEntry
import com.clipboardspeech.databinding.FragmentProcessNoteConfigBinding
import com.clipboardspeech.databinding.ItemProcessNoteEntryBinding
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
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class ProcessNoteConfigFragment : Fragment() {

    private var _binding: FragmentProcessNoteConfigBinding? = null
    private val binding get() = _binding!!

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        const val KEY_PROCESS_NOTE_CONFIG = "process_note_config"
        const val KEY_ENDPOINT_URL = "endpointUrl"
        const val DEFAULT_ENDPOINT = "https://claude-pm.gynlhmc.com/api/process-note"

        fun loadEndpointUrl(context: Context): String {
            val prefs = context.getSharedPreferences(AiConfigFragment.PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_PROCESS_NOTE_CONFIG, null) ?: return DEFAULT_ENDPOINT
            return try {
                JSONObject(json).optString(KEY_ENDPOINT_URL).ifEmpty { DEFAULT_ENDPOINT }
            } catch (_: Exception) { DEFAULT_ENDPOINT }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProcessNoteConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadConfig()
        setupLog()

        binding.etEndpointUrl.doAfterTextChanged { hideTestResult() }

        binding.btnSaveProcessNoteConfig.setOnClickListener {
            saveConfig()
            binding.btnSaveProcessNoteConfig.text = "已儲存 ✓"
            binding.btnSaveProcessNoteConfig.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(android.R.color.holo_green_dark)
                )
            Handler(Looper.getMainLooper()).postDelayed({
                if (_binding != null) {
                    binding.btnSaveProcessNoteConfig.text = "儲存設定"
                    binding.btnSaveProcessNoteConfig.backgroundTintList =
                        requireContext().getColorStateList(
                            com.google.android.material.R.color.m3_button_background_color_selector
                        )
                }
            }, 2000)
        }

        binding.btnTestProcessNoteConnection.setOnClickListener {
            testConnection()
        }

        binding.btnClearProcessNoteConfig.setOnClickListener {
            requireContext()
                .getSharedPreferences(AiConfigFragment.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_PROCESS_NOTE_CONFIG).apply()
            binding.etEndpointUrl.setText("")
            binding.btnClearProcessNoteConfig.visibility = View.GONE
            hideTestResult()
        }
    }

    private fun setupLog() {
        val adapter = ProcessNoteLogAdapter()
        binding.rvProcessNoteLog.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProcessNoteLog.adapter = adapter

        AppDatabase.get(requireContext()).processNoteDao().getAll()
            .observe(viewLifecycleOwner) { entries ->
                if (entries.isEmpty()) {
                    binding.tvLogEmpty.visibility = View.VISIBLE
                    binding.rvProcessNoteLog.visibility = View.GONE
                } else {
                    binding.tvLogEmpty.visibility = View.GONE
                    binding.rvProcessNoteLog.visibility = View.VISIBLE
                    adapter.submitList(entries)
                }
            }
    }

    private fun testConnection() {
        val url = binding.etEndpointUrl.text?.toString()?.trim() ?: ""

        if (url.isEmpty()) {
            showTestResult(false, "⚠️", "請輸入 Endpoint URL。")
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showTestResult(false, "⚠️", "URL 格式錯誤，必須以 https:// 開頭。")
            return
        }

        binding.btnTestProcessNoteConnection.isEnabled = false
        binding.btnTestProcessNoteConnection.text = "測試中…（最長 90 秒）"
        hideTestResult()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runTest(url) }
            if (_binding == null) return@launch
            binding.btnTestProcessNoteConnection.isEnabled = true
            binding.btnTestProcessNoteConnection.text = "測試連線"
            showTestResult(result.success, result.icon, result.message)
        }
    }

    data class TestResult(val success: Boolean, val icon: String, val message: String)

    private fun runTest(url: String): TestResult {
        val body = JSONObject().apply { put("content", "test") }.toString()
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            when (code) {
                200, 201 -> {
                    val msg = try { JSONObject(responseBody).optString("message", "") } catch (_: Exception) { "" }
                    TestResult(true, "✅", "連線成功！伺服器正常回應。${if (msg.isNotEmpty()) "\n$msg" else ""}")
                }
                400, in 500..599 -> {
                    // Server responded — it's reachable and running.
                    // 500 typically means AI model couldn't process the test payload, not a connectivity issue.
                    TestResult(true, "✅", "連線成功！伺服器正常運作（HTTP $code，為測試內容的預期回應）。")
                }
                404 -> TestResult(false, "🔍", "找不到端點（404）。請確認 URL 路徑是否正確。")
                else -> TestResult(false, "❓", "未預期的回應（$code）。")
            }
        } catch (e: UnknownHostException) {
            TestResult(false, "🌐", "無法連線至伺服器。請確認 URL 與網路連線是否正確。")
        } catch (e: SocketTimeoutException) {
            TestResult(false, "⏱️", "連線逾時。伺服器未在時限內回應，請確認服務是否啟動。")
        } catch (e: SSLException) {
            TestResult(false, "🔒", "SSL/TLS 錯誤。請確認憑證是否有效。")
        } catch (e: MalformedURLException) {
            TestResult(false, "⚠️", "URL 格式錯誤：${e.message}")
        } catch (e: Exception) {
            TestResult(false, "❌", "連線失敗：${e.message ?: "未知錯誤"}")
        }
    }

    private fun showTestResult(success: Boolean, icon: String, msg: String) {
        val card = binding.cardProcessNoteTestResult
        val ctx = requireContext()
        card.background = ctx.getDrawable(
            if (success) R.drawable.bg_test_success else R.drawable.bg_test_error
        )
        binding.tvProcessNoteTestResultIcon.text = icon
        binding.tvProcessNoteTestResultMsg.text = msg
        binding.tvProcessNoteTestResultMsg.setTextColor(
            Color.parseColor(if (success) "#1E6B3C" else "#B3261E")
        )
        card.visibility = View.VISIBLE
    }

    private fun hideTestResult() {
        _binding?.cardProcessNoteTestResult?.visibility = View.GONE
    }

    private fun loadConfig() {
        val prefs = requireContext().getSharedPreferences(AiConfigFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PROCESS_NOTE_CONFIG, null)
        if (json != null) {
            try {
                val obj = JSONObject(json)
                val url = obj.optString(KEY_ENDPOINT_URL)
                binding.etEndpointUrl.setText(url)
                binding.btnClearProcessNoteConfig.visibility =
                    if (url.isNotEmpty()) View.VISIBLE else View.GONE
            } catch (_: Exception) {}
        }
    }

    private fun saveConfig() {
        val obj = JSONObject().apply {
            put(KEY_ENDPOINT_URL, binding.etEndpointUrl.text?.toString()?.trim() ?: "")
        }
        requireContext()
            .getSharedPreferences(AiConfigFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROCESS_NOTE_CONFIG, obj.toString()).apply()
        binding.btnClearProcessNoteConfig.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class ProcessNoteLogAdapter : RecyclerView.Adapter<ProcessNoteLogAdapter.VH>() {

    private var items: List<ProcessNoteEntry> = emptyList()
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun submitList(list: List<ProcessNoteEntry>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemProcessNoteEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemProcessNoteEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val b = holder.binding

        b.tvContentPreview.text = entry.contentPreview

        when (entry.status) {
            "pending" -> {
                b.tvStatusIcon.text = "⏳"
                b.tvServerMessage.visibility = View.GONE
            }
            "success" -> {
                b.tvStatusIcon.text = "✅"
                if (!entry.serverMessage.isNullOrEmpty()) {
                    b.tvServerMessage.visibility = View.VISIBLE
                    b.tvServerMessage.text = entry.serverMessage
                    b.tvServerMessage.setTextColor(Color.parseColor("#1E6B3C"))
                }
            }
            "failed" -> {
                b.tvStatusIcon.text = "❌"
                if (!entry.serverMessage.isNullOrEmpty()) {
                    b.tvServerMessage.visibility = View.VISIBLE
                    b.tvServerMessage.text = entry.serverMessage
                    b.tvServerMessage.setTextColor(Color.parseColor("#B3261E"))
                }
            }
        }

        val time = dateFmt.format(Date(entry.createdAt))
        b.tvTimestamp.text = if (entry.completedAt != null) {
            val elapsed = (entry.completedAt - entry.createdAt) / 1000
            "$time · ${elapsed}s"
        } else {
            time
        }
    }
}
