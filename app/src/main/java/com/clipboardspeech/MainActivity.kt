package com.clipboardspeech

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.clipboardspeech.data.AppDatabase
import com.clipboardspeech.data.HistoryEntry
import com.clipboardspeech.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var availableVoices: List<Voice> = emptyList()
    private var selectedVoiceIndex: Int = -1

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // State
    private enum class UiState { IDLE, SPEAKING, PAUSED }
    private var uiState = UiState.IDLE
    private var fullText: String = ""
    private var loadedEntryId: Long = -1L
    private var currentAiSummary: String? = null

    // Sentence-based pause/resume (coarse) + word-offset (fine, when engine fires onRangeStart)
    private var sentences: List<String> = emptyList()
    private var sentenceOffsets: List<Int> = emptyList()
    @Volatile private var currentSentenceIdx: Int = 0
    @Volatile private var pausedByUser: Boolean = false
    // Word-level resume: resumeOffsetInSentence = where the last speak() started inside the sentence
    // pauseWordOffsetInSentence = updated by onRangeStart; used as start point on next resume
    @Volatile private var resumeOffsetInSentence: Int = 0
    @Volatile private var pauseWordOffsetInSentence: Int = 0

    companion object {
        private const val SAVED_AI_SUMMARY_KEY = "saved_ai_summary"
    }

    // Speed/pitch: slider max=15, progress 0..15 maps to 0.5..2.0 (step 0.1)
    private var speechRate: Float = 1.4f
    private var pitchValue: Float = 1.0f

    private val utteranceId = "CBS_UTTERANCE"

    // D-006: runtime storage permission
    private val REQUEST_WRITE_STORAGE = 1001
    private var pendingSaveText: String? = null

    // OkHttp client for AI requests
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this, this)

        setupSliders()
        setupButtons()
        setupTextWatcher()
        updateRecallButtonState()   // restore recall button state from previous session

        // 3a: Load text from history if launched with LOAD_ID
        loadedEntryId = intent.getLongExtra("LOAD_ID", -1L)
        if (loadedEntryId != -1L) {
            lifecycleScope.launch {
                val entry = AppDatabase.get(this@MainActivity).historyDao().getById(loadedEntryId)
                if (entry != null) {
                    withContext(Dispatchers.Main) {
                        binding.etInput.setText(entry.fullText)
                        binding.etInput.setSelection(entry.fullText.length)
                        currentAiSummary = entry.aiSummary
                        updateRecallButtonState()
                    }
                }
            }
        }
    }

    // ---- TextToSpeech.OnInitListener ----

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            loadVoices()
            tts?.setOnUtteranceProgressListener(utteranceProgressListener)
        } else {
            showSnackbar(getString(R.string.msg_tts_unavailable))
        }
    }

    private fun loadVoices() {
        val voices = tts?.voices ?: return
        val onDevice = voices.filter { !it.isNetworkConnectionRequired }
        availableVoices = if (onDevice.isNotEmpty()) onDevice else voices.toList()

        // Auto-select default voice
        val defaultVoice = tts?.defaultVoice
        selectedVoiceIndex = if (defaultVoice != null) {
            val idx = availableVoices.indexOfFirst { it.name == defaultVoice.name }
            if (idx >= 0) idx else 0
        } else 0

        if (availableVoices.isNotEmpty()) {
            applySelectedVoice()
        }
    }

    private fun applySelectedVoice() {
        if (selectedVoiceIndex >= 0 && selectedVoiceIndex < availableVoices.size) {
            val voice = availableVoices[selectedVoiceIndex]
            tts?.voice = voice
            val displayName = "${voice.name} (${voice.locale.displayName})"
            binding.tvVoiceName.text = displayName
        }
    }

    // ---- Utterance progress (read-along word highlighting) ----

    private val utteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            // no-op
        }

        override fun onDone(utteranceId: String?) {
            if (pausedByUser) return  // user paused or stopped — do not chain
            val next = currentSentenceIdx + 1
            if (next < sentences.size) {
                speakSentence(next, 0)   // always start next sentence from beginning
            } else {
                runOnUiThread { onSpeechFinished() }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (pausedByUser) return
            runOnUiThread {
                showSnackbar(getString(R.string.msg_tts_error))
                onSpeechFinished()
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (pausedByUser) return
            runOnUiThread {
                showSnackbar(getString(R.string.msg_tts_error))
                onSpeechFinished()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            // start/end are relative to whatever text was passed to speak()
            // which starts at resumeOffsetInSentence inside the current sentence.
            // Convert to an absolute fullText offset for highlighting and for resume.
            pauseWordOffsetInSentence = resumeOffsetInSentence + start
            val base = sentenceOffsets.getOrElse(currentSentenceIdx) { 0 }
            runOnUiThread { highlightWord(base + pauseWordOffsetInSentence, base + resumeOffsetInSentence + end) }
        }
    }

    private fun highlightWord(start: Int, end: Int) {
        if (fullText.isEmpty()) return
        val safeStart = start.coerceIn(0, fullText.length)
        val safeEnd = end.coerceIn(safeStart, fullText.length)

        val spannable = SpannableString(fullText)
        val primaryColor = ContextCompat.getColor(this, R.color.primary)
        val whiteColor = ContextCompat.getColor(this, R.color.white)

        spannable.setSpan(
            BackgroundColorSpan(primaryColor),
            safeStart, safeEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            ForegroundColorSpan(whiteColor),
            safeStart, safeEnd,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.tvReadAlong.text = spannable
        // Scroll to keep highlighted word visible (D-010)
        val layout = binding.tvReadAlong.layout ?: return
        val line = layout.getLineForOffset(safeStart)
        val lineTop = layout.getLineTop(line)
        binding.tvReadAlong.scrollTo(0, lineTop)
    }

    // ---- UI Setup ----

    private fun setupSliders() {
        // Speed: progress 0..15 -> rate 0.5..2.0
        binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                speechRate = 0.5f + progress * 0.1f
                binding.tvSpeedValue.text = String.format("%.1f×", speechRate)
                if (ttsReady) tts?.setSpeechRate(speechRate)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        // Default progress=9 -> 1.4x
        binding.seekSpeed.progress = 9
        binding.tvSpeedValue.text = "1.4×"

        // Pitch: progress 0..15 -> pitch 0.5..2.0
        binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                pitchValue = 0.5f + progress * 0.1f
                binding.tvPitchValue.text = String.format("%.1f", pitchValue)
                if (ttsReady) tts?.setPitch(pitchValue)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.seekPitch.progress = 5
        binding.tvPitchValue.text = "1.0"
    }

    private fun setupButtons() {
        // 3b: Back button
        binding.btnBack.setOnClickListener { finish() }

        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnSpeak.setOnClickListener { startSpeaking() }
        binding.btnPauseResume.setOnClickListener { togglePauseResume() }
        binding.btnStop.setOnClickListener { stopSpeaking() }
        binding.btnSave.setOnClickListener {
            val text = binding.etInput.text?.toString() ?: ""
            if (text.isEmpty()) { showSnackbar(getString(R.string.msg_no_text)); return@setOnClickListener }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingSaveText = text
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_WRITE_STORAGE)
                return@setOnClickListener
            }
            performSave(text)
        }
        binding.btnClear.setOnClickListener { clearText() }
        binding.btnVoiceSelector.setOnClickListener { openVoiceSheet() }

        // AI Summary button
        binding.btnAiSummary.setOnClickListener {
            val text = binding.etInput.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) { showSnackbar(getString(R.string.msg_no_text)); return@setOnClickListener }
            performAiSummary(text)
        }

        // Summary panel actions
        binding.btnDismissSummary.setOnClickListener { hideSummaryPanel() }
        binding.btnSummaryUseTts.setOnClickListener {
            val summaryText = binding.tvSummaryContent.text?.toString() ?: return@setOnClickListener
            binding.etInput.setText(summaryText)
            hideSummaryPanel()
        }
        binding.btnSummaryCopy.setOnClickListener {
            val summaryText = binding.tvSummaryContent.text?.toString() ?: return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("summary", summaryText))
            showSnackbar("Copied to clipboard")
        }
        binding.btnRegenerateAiSummary.setOnClickListener {
            val text = binding.etInput.text?.toString()?.trim()?.ifEmpty { null } ?: fullText
            if (text.isNotEmpty()) performAiSummary(text)
        }
        binding.btnRecallAiSummary.setOnClickListener {
            if (currentAiSummary != null) {
                showSummaryPanel(title = "AI Summary", isAi = true)
                showSummaryContent(currentAiSummary!!, saveAsAiSummary = false)
            } else {
                showSnackbar("No saved AI summary yet. Generate one first.")
            }
        }
        updateRecallButtonState()
    }

    private fun setupTextWatcher() {
        binding.etInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val len = s?.length ?: 0
                binding.tvCharCount.text = getString(R.string.chars_count, len)
                val hasText = len > 0 && uiState == UiState.IDLE
                binding.btnSave.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.btnClear.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.rowSummaryButtons.visibility = if (hasText) View.VISIBLE else View.GONE
            }
        })
    }

    // ---- Clipboard ----

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isNotEmpty()) {
                binding.etInput.setText(text)
                binding.etInput.setSelection(text.length)
                showSnackbar(getString(R.string.msg_pasted))
            } else {
                showSnackbar(getString(R.string.msg_clipboard_empty))
            }
        } else {
            showSnackbar(getString(R.string.msg_clipboard_empty))
        }
    }

    // ---- TTS Actions ----

    private fun startSpeaking() {
        if (!ttsReady) {
            showSnackbar(getString(R.string.msg_tts_unavailable))
            return
        }
        val text = binding.etInput.text?.toString()?.trim() ?: ""
        if (text.isEmpty()) {
            showSnackbar(getString(R.string.msg_no_text))
            return
        }

        fullText = text
        binding.tvReadAlong.text = fullText

        tts?.setSpeechRate(speechRate)
        tts?.setPitch(pitchValue)

        val (segs, offs) = buildSentences(fullText)
        sentences = segs
        sentenceOffsets = offs
        currentSentenceIdx = 0
        pausedByUser = false
        resumeOffsetInSentence = 0
        pauseWordOffsetInSentence = 0

        requestAudioFocus()
        speakSentence(0, 0)
        setUiState(UiState.SPEAKING)
    }

    /**
     * Speak the sentence at [idx] starting from [wordOffset] characters into it.
     * Uses QUEUE_FLUSH so there is always exactly one pending utterance.
     * [wordOffset] is tracked via onRangeStart for word-level resume accuracy.
     */
    private fun speakSentence(idx: Int, wordOffset: Int = 0) {
        if (idx >= sentences.size) { runOnUiThread { onSpeechFinished() }; return }
        currentSentenceIdx = idx
        resumeOffsetInSentence = wordOffset
        pauseWordOffsetInSentence = wordOffset   // fallback if onRangeStart never fires
        val sentence = sentences[idx]
        val textToSpeak = if (wordOffset > 0 && wordOffset < sentence.length)
            sentence.substring(wordOffset) else sentence
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /**
     * Split text into speakable chunks small enough that pause/resume never loses more than
     * ~8 words of position even on engines that don't fire onRangeStart.
     *
     * Priority order:
     *  1. Sentence endings  (.  !  ?  …)  — each sentence recurses into lower levels
     *  2. Clause boundaries (, ; :)        — each clause recurses into lower levels
     *  3. Hard 8-word cap                  — ALWAYS applied, no minimum guard
     *
     * Every piece of text ends up in chunks of ≤ 8 words, so resume can never go back
     * more than ~3 seconds regardless of punctuation.
     */
    private fun buildSentences(text: String): Pair<List<String>, List<Int>> {
        val result = mutableListOf<Pair<String, Int>>()   // (chunk text, offset in fullText)

        fun chunk(seg: String, baseOffset: Int) {
            if (seg.isBlank()) return

            // ── Level 1: sentence boundaries ──────────────────────────────────────
            val sentParts = seg.split(Regex("(?<=[.!?。！？…])\\s+"))
            if (sentParts.size > 1) {
                var off = 0
                for (part in sentParts) {
                    val t = part.trim()
                    if (t.isBlank()) continue
                    val idx = seg.indexOf(t, off)
                    val absOff = baseOffset + (if (idx >= 0) idx else off)
                    chunk(t, absOff)   // always recurse — Level 3 will apply 8-word cap
                    off = if (idx >= 0) idx + t.length else off + t.length
                }
                return
            }

            // ── Level 2: clause boundaries (,  ;  :) ──────────────────────────────
            val clauseParts = seg.split(Regex("(?<=[,，;；:：])\\s+"))
            if (clauseParts.size > 1) {
                var off = 0
                for (part in clauseParts) {
                    val t = part.trim()
                    if (t.isBlank()) continue
                    val idx = seg.indexOf(t, off)
                    val absOff = baseOffset + (if (idx >= 0) idx else off)
                    chunk(t, absOff)   // always recurse — Level 3 will apply 8-word cap
                    off = if (idx >= 0) idx + t.length else off + t.length
                }
                return
            }

            // ── Level 3: 8-word cap — no minimum guard, always applied ────────────
            // Ensures resume never goes back more than ~8 words, even for short
            // texts with no punctuation at all.
            val words = seg.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return
            var charPos = 0
            var i = 0
            while (i < words.size) {
                val end = minOf(i + 8, words.size)
                val chunkStr = words.subList(i, end).joinToString(" ")
                val idx = seg.indexOf(chunkStr, charPos)
                result.add(chunkStr to (baseOffset + (if (idx >= 0) idx else charPos)))
                charPos = if (idx >= 0) idx + chunkStr.length else charPos + chunkStr.length
                i = end
            }
        }

        chunk(text.trim(), text.indexOf(text.trim()))
        if (result.isEmpty()) result.add(text to 0)
        return result.map { it.first } to result.map { it.second }
    }

    private fun togglePauseResume() {
        if (uiState == UiState.SPEAKING) {
            pausedByUser = true   // blocks onDone from chaining to next sentence
            tts?.stop()
            setUiState(UiState.PAUSED)
        } else if (uiState == UiState.PAUSED) {
            pausedByUser = false
            // Resume from the exact word where speech stopped (word-level if engine supports
            // onRangeStart, otherwise sentence-level fallback)
            speakSentence(currentSentenceIdx, pauseWordOffsetInSentence)
            setUiState(UiState.SPEAKING)
        }
    }

    private fun stopSpeaking() {
        pausedByUser = true   // blocks onDone from chaining after tts.stop()
        tts?.stop()
        abandonAudioFocus()
        setUiState(UiState.IDLE)
    }

    private fun onSpeechFinished() {
        abandonAudioFocus()
        setUiState(UiState.IDLE)
    }

    // ---- Audio Focus ----

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            audioFocusRequest = request
            audioManager?.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }
    }

    // ---- UI State Machine ----

    private fun setUiState(state: UiState) {
        uiState = state
        when (state) {
            UiState.IDLE -> {
                binding.etInput.visibility = View.VISIBLE
                binding.tvReadAlong.visibility = View.GONE
                binding.btnSpeak.visibility = View.VISIBLE
                binding.rowPauseStop.visibility = View.GONE
                val hasText = (binding.etInput.text?.length ?: 0) > 0
                binding.btnSave.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.btnClear.visibility = if (hasText) View.VISIBLE else View.GONE
                binding.rowSummaryButtons.visibility = if (hasText) View.VISIBLE else View.GONE
            }
            UiState.SPEAKING -> {
                binding.etInput.visibility = View.GONE
                binding.tvReadAlong.visibility = View.VISIBLE
                binding.btnSpeak.visibility = View.GONE
                binding.rowPauseStop.visibility = View.VISIBLE
                binding.btnSave.visibility = View.GONE
                binding.btnClear.visibility = View.GONE
                binding.rowSummaryButtons.visibility = View.GONE
                // Pause button styling
                binding.btnPauseResume.text = getString(R.string.btn_pause)
                binding.btnPauseResume.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(this, R.drawable.ic_pause), null, null, null
                )
                binding.btnPauseResume.setBackgroundResource(R.drawable.bg_pause_button)
                binding.btnPauseResume.setTextColor(ContextCompat.getColor(this, R.color.onSurface))
            }
            UiState.PAUSED -> {
                binding.etInput.visibility = View.GONE
                binding.tvReadAlong.visibility = View.VISIBLE
                binding.btnSpeak.visibility = View.GONE
                binding.rowPauseStop.visibility = View.VISIBLE
                binding.btnSave.visibility = View.GONE
                binding.btnClear.visibility = View.GONE
                binding.rowSummaryButtons.visibility = View.GONE
                // Resume button styling
                binding.btnPauseResume.text = getString(R.string.btn_resume)
                binding.btnPauseResume.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(this, R.drawable.ic_play), null, null, null
                )
                binding.btnPauseResume.setBackgroundResource(R.drawable.bg_resume_button)
                binding.btnPauseResume.setTextColor(ContextCompat.getColor(this, R.color.white))
            }
        }
    }

    // ---- Save to file + Room history ----

    private fun performSave(text: String) {
        // De-duplicate: if the same text content is already in history, skip the save
        // entirely so the user doesn't end up with multiple copies of identical content.
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).historyDao()
            val existing = withContext(Dispatchers.IO) { dao.findByText(text) }
            if (existing != null) {
                showSnackbar("Already saved as \"${existing.name}\"")
                return@launch
            }

            // No duplicate — proceed with the regular save (file + DB insert).
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val filename = "$timestamp.txt"

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, filename)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(text.toByteArray())
                        }
                        showSnackbar(getString(R.string.msg_saved, filename))
                    } else {
                        showSnackbar(getString(R.string.msg_save_failed))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    val file = File(dir, filename)
                    FileOutputStream(file).use { stream ->
                        stream.write(text.toByteArray())
                    }
                    showSnackbar(getString(R.string.msg_saved, filename))
                }
            } catch (e: Exception) {
                showSnackbar(getString(R.string.msg_save_failed))
            }

            dao.insert(
                HistoryEntry(
                    name = filename,
                    fullText = text,
                    preview = text.take(120),
                    charCount = text.length,
                    savedAt = System.currentTimeMillis(),
                    aiSummary = currentAiSummary
                )
            )
        }
    }

    // ---- Clear ----

    private fun clearText() {
        tts?.stop()
        abandonAudioFocus()
        binding.etInput.setText("")
        setUiState(UiState.IDLE)
        currentAiSummary = null
        loadedEntryId = -1L
        showSnackbar(getString(R.string.msg_cleared))
    }

    // ---- Voice selector ----

    private fun openVoiceSheet() {
        if (availableVoices.isEmpty()) {
            showSnackbar(getString(R.string.msg_tts_unavailable))
            return
        }
        val sheet = VoiceBottomSheet.newInstance(
            voices = availableVoices,
            selectedIndex = selectedVoiceIndex,
            listener = object : VoiceBottomSheet.VoiceSelectionListener {
                override fun onVoiceSelected(voice: Voice, index: Int) {
                    selectedVoiceIndex = index
                    tts?.voice = voice
                    val displayName = "${voice.name} (${voice.locale.displayName})"
                    binding.tvVoiceName.text = displayName
                }
            }
        )
        sheet.show(supportFragmentManager, "voice_sheet")
    }

    // ---- Quick Summary (3e) ----

    private fun quickSummary(text: String): String {
        val sentences = text.split(Regex("[.!?]+")).filter { it.trim().length > 10 }
        if (sentences.isEmpty()) return text.take(200)
        val words = text.lowercase().split(Regex("\\W+"))
        val freq = words.groupingBy { it }.eachCount()
        val keep = maxOf(1, minOf(3, (sentences.size * 0.3).toInt()))
        return sentences.mapIndexed { i, s ->
            val score = s.lowercase().split(Regex("\\W+")).sumOf { freq[it] ?: 0 }.toDouble() / maxOf(1, s.split(" ").size)
            Triple(i, s.trim(), score)
        }.sortedByDescending { it.third }.take(keep).sortedBy { it.first }.joinToString(" ") { it.second }
    }

    // ---- AI Summary (3f) ----

    private fun performAiSummary(text: String) {
        // Always open the panel first so errors are shown inline
        showSummaryPanel(title = "AI Summary", isAi = true)

        val prefs = getSharedPreferences(AiConfigFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val configJson = prefs.getString(AiConfigFragment.KEY_AI_CONFIG, null)
        if (configJson == null) {
            showSummaryError("AI is not configured. Go to the AI Config tab and enter your API Key, Base URL, and Model.")
            return
        }

        val config = try { JSONObject(configJson) } catch (e: Exception) {
            showSummaryError("AI configuration is invalid. Go to the AI Config tab and re-enter your settings.")
            return
        }

        val apiKey = config.optString("apiKey")
        val url = config.optString("url").trimEnd('/')
        val model = config.optString("model").ifEmpty { "gpt-4o-mini" }
        val systemPrompt = config.optString("systemPrompt").ifEmpty { "You are a helpful assistant." }

        if (apiKey.isEmpty() || url.isEmpty()) {
            showSummaryError("API Key and Base URL are required. Go to the AI Config tab to complete your setup.")
            return
        }

        showSummaryLoading(true)
        binding.btnAiSummary.isEnabled = false

        val anthropic = isAnthropicUrl(url)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val endpoint = if (anthropic) "$url/messages" else "$url/chat/completions"

                // Anthropic format: system is a top-level field, not a message
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

                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .apply { if (anthropic) addHeader("anthropic-version", "2023-06-01") }
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                val code = response.code

                if (!response.isSuccessful) {
                    val errMsg = parseAiError(code, responseBody)
                    withContext(Dispatchers.Main) {
                        showSummaryError(errMsg)
                        binding.btnAiSummary.isEnabled = true
                    }
                    return@launch
                }

                val result = JSONObject(responseBody)
                // Parse content from either Anthropic or OpenAI response format
                val content = if (anthropic) {
                    val contentArr = result.getJSONArray("content")
                    (0 until contentArr.length())
                        .mapNotNull { i ->
                            val obj = contentArr.getJSONObject(i)
                            if (obj.optString("type") == "text") obj.optString("text") else null
                        }
                        .firstOrNull()?.trim()
                        ?: throw Exception("No text content in response")
                } else {
                    result.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }

                withContext(Dispatchers.Main) {
                    showSummaryLoading(false)
                    showSummaryContent(content, saveAsAiSummary = true)
                    binding.btnAiSummary.isEnabled = true
                }
            } catch (e: java.net.UnknownHostException) {
                withContext(Dispatchers.Main) {
                    showSummaryError("Cannot reach the server. Check your API Base URL and internet connection.")
                    binding.btnAiSummary.isEnabled = true
                }
            } catch (e: java.net.SocketTimeoutException) {
                withContext(Dispatchers.Main) {
                    showSummaryError("Request timed out. The server did not respond in time. Please try again.")
                    binding.btnAiSummary.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showSummaryError("Request failed: ${e.message ?: "Unknown error"}.")
                    binding.btnAiSummary.isEnabled = true
                }
            }
        }
    }

    /** True when the base URL targets an Anthropic-format endpoint (e.g. MiniMax /anthropic/v1). */
    private fun isAnthropicUrl(url: String) =
        url.contains("/anthropic/", ignoreCase = true) ||
        url.contains("anthropic.com", ignoreCase = true)

    private fun parseAiError(code: Int, body: String): String {
        val detail = try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() } ?: body.take(120)
        } catch (_: Exception) { body.take(120) }
        return when (code) {
            401 -> "Authentication failed (401). Your API Key is invalid or expired."
            403 -> "Access forbidden (403). Your API Key does not have permission to use this model."
            404 -> "Endpoint not found (404). Check your API Base URL is correct."
            429 -> "Rate limit exceeded (429). Too many requests — please wait and try again."
            in 500..599 -> "Server error ($code). The AI provider is experiencing issues. Try again later."
            else -> "Error $code: $detail"
        }
    }

    // ---- Summary panel helpers ----

    private fun showSummaryPanel(title: String, isAi: Boolean) {
        binding.summaryPanelTitle.text = title
        if (isAi) {
            binding.summaryIconBox.background = resources.getDrawable(R.drawable.bg_ai_icon_box, theme)
            binding.summaryIcon.setImageResource(R.drawable.ic_ai)
            binding.summaryIcon.setColorFilter(android.graphics.Color.WHITE)
            binding.btnRegenerateAiSummary.visibility = View.VISIBLE
        } else {
            binding.summaryIconBox.background = resources.getDrawable(R.drawable.bg_summary_icon_box, theme)
            binding.summaryIcon.setImageResource(R.drawable.ic_summary)
            binding.summaryIcon.clearColorFilter()
            binding.btnRegenerateAiSummary.visibility = View.GONE
        }
        binding.summaryLoadingRow.visibility = View.GONE
        binding.scrollSummaryContent.visibility = View.GONE
        binding.summaryActionRow.visibility = View.GONE
        binding.summaryPanel.visibility = View.VISIBLE
    }

    private fun showSummaryLoading(loading: Boolean) {
        binding.summaryLoadingRow.visibility = if (loading) View.VISIBLE else View.GONE
        binding.scrollSummaryContent.visibility = if (loading) View.GONE else View.VISIBLE
        binding.summaryActionRow.visibility = if (loading) View.GONE else View.VISIBLE
    }

    private fun showSummaryContent(text: String, saveAsAiSummary: Boolean = false) {
        binding.tvSummaryContent.setTextColor(getColor(android.R.color.black))
        binding.tvSummaryContent.text = text
        binding.summaryLoadingRow.visibility = View.GONE
        binding.scrollSummaryContent.visibility = View.VISIBLE
        // Scroll back to top whenever new content is shown
        binding.scrollSummaryContent.scrollTo(0, 0)
        binding.summaryActionRow.visibility = View.VISIBLE

        if (saveAsAiSummary) {
            currentAiSummary = text
            getSharedPreferences(AiConfigFragment.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(SAVED_AI_SUMMARY_KEY, text).apply()
            updateRecallButtonState()
            persistAiSummaryToEntry(text)
        }
    }

    private fun persistAiSummaryToEntry(summary: String) {
        val currentText = binding.etInput.text.toString().trim()
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@MainActivity).historyDao()
            if (loadedEntryId != -1L) {
                withContext(Dispatchers.IO) { dao.updateAiSummary(loadedEntryId, summary) }
            } else {
                val entry = withContext(Dispatchers.IO) { dao.findByText(currentText) }
                if (entry != null) {
                    withContext(Dispatchers.IO) { dao.updateAiSummary(entry.id, summary) }
                }
            }
        }
    }

    private fun showSummaryError(message: String) {
        binding.summaryLoadingRow.visibility = View.GONE
        binding.tvSummaryContent.setTextColor(android.graphics.Color.parseColor("#B3261E"))
        binding.tvSummaryContent.text = message
        binding.scrollSummaryContent.visibility = View.VISIBLE
        binding.scrollSummaryContent.scrollTo(0, 0)
        binding.summaryActionRow.visibility = View.GONE   // no content to use or copy
        binding.summaryPanel.visibility = View.VISIBLE
    }

    private fun hideSummaryPanel() {
        binding.summaryPanel.visibility = View.GONE
    }

    private fun updateRecallButtonState() {
        val hasSaved = currentAiSummary != null
        binding.btnRecallAiSummary.isEnabled = hasSaved
        binding.btnRecallAiSummary.alpha = if (hasSaved) 1.0f else 0.4f
    }

    // ---- Helpers ----

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    // ---- Permissions ----

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            pendingSaveText?.let { performSave(it) }
        } else if (requestCode == REQUEST_WRITE_STORAGE) {
            Snackbar.make(binding.root, "Storage permission denied — cannot save", Snackbar.LENGTH_SHORT).show()
        }
        pendingSaveText = null
    }

    // ---- Lifecycle ----

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        abandonAudioFocus()
        super.onDestroy()
    }
}
