package com.clipboardspeech

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.clipboardspeech.data.AppDatabase
import com.clipboardspeech.data.HistoryEntry
import com.clipboardspeech.databinding.FragmentHistoryBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: HistoryAdapter

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            adapter.exitSelectionMode()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val dao = AppDatabase.get(requireContext()).historyDao()

        adapter = HistoryAdapter(lifecycleScope) { count -> updateBottomBar(count) }
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter

        dao.getAll().observe(viewLifecycleOwner) { entries ->
            adapter.submitList(entries)
            val isEmpty = entries.isEmpty()
            binding.layoutEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
            binding.rvHistory.visibility = if (isEmpty) View.GONE else View.VISIBLE
            binding.btnClearAll.visibility = if (isEmpty || adapter.isSelectionMode) View.GONE else View.VISIBLE
        }

        binding.btnClearAll.setOnClickListener {
            lifecycleScope.launch { dao.deleteAll() }
        }

        binding.btnExportAiSummary.setOnClickListener {
            exportAiSummaries(adapter.getSelectedEntries())
        }
    }

    private fun updateBottomBar(count: Int) {
        val inSelection = adapter.isSelectionMode
        binding.layoutBottomActionBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.tvSelectionCount.text = "$count selected"
        backCallback.isEnabled = inSelection
        // Hide "Clear all" while in selection mode
        binding.btnClearAll.visibility = if (inSelection) View.GONE else {
            if (adapter.currentList.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun exportAiSummaries(entries: List<HistoryEntry>) {
        if (entries.isEmpty()) return

        val dateFmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("# AI Summary Export")
        sb.appendLine("Exported: ${dateFmt.format(Date())}")
        sb.appendLine()

        for (entry in entries) {
            sb.appendLine("---")
            sb.appendLine("## ${entry.name}")
            sb.appendLine("*${dateFmt.format(Date(entry.savedAt))} · ${entry.charCount} chars*")
            sb.appendLine()
            if (entry.aiSummary != null) {
                sb.appendLine(entry.aiSummary)
            } else {
                sb.appendLine("*(No AI summary available for this entry)*")
            }
            sb.appendLine()
        }

        val filename = "ai-summary-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.md"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = requireContext().contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    requireContext().contentResolver.openOutputStream(uri)?.use {
                        it.write(sb.toString().toByteArray())
                    }
                    Snackbar.make(binding.root, "Exported to Downloads/$filename", Snackbar.LENGTH_LONG).show()
                } else {
                    Snackbar.make(binding.root, "Export failed", Snackbar.LENGTH_SHORT).show()
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { it.write(sb.toString().toByteArray()) }
                Snackbar.make(binding.root, "Exported to ${file.absolutePath}", Snackbar.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Export failed: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }

        adapter.exitSelectionMode()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
