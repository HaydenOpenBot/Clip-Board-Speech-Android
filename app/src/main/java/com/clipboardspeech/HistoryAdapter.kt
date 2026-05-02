package com.clipboardspeech

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.clipboardspeech.data.AppDatabase
import com.clipboardspeech.data.HistoryEntry
import com.clipboardspeech.databinding.ItemHistoryBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onSelectionChanged: ((count: Int) -> Unit)? = null
) : ListAdapter<HistoryEntry, HistoryAdapter.HistoryViewHolder>(DIFF_CALLBACK) {

    var isSelectionMode = false
        private set

    private val selectedIds = mutableSetOf<Long>()

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<HistoryEntry>() {
            override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry) =
                oldItem == newItem
        }

        private val DATE_FORMAT = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    }

    inner class HistoryViewHolder(val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root)

    fun getSelectedEntries(): List<HistoryEntry> =
        currentList.filter { it.id in selectedIds }

    fun exitSelectionMode() {
        isSelectionMode = false
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val entry = getItem(position)
        val ctx = holder.binding.root.context
        val b = holder.binding

        b.tvHistoryName.text = entry.name
        b.tvHistoryPreview.text = entry.preview
        b.tvHistoryMeta.text = "${entry.charCount} chars · ${DATE_FORMAT.format(Date(entry.savedAt))}"

        val isSelected = entry.id in selectedIds

        // Background highlight + icon swap for selection state
        if (isSelected) {
            b.root.setBackgroundColor(ContextCompat.getColor(ctx, R.color.primaryContainer))
            b.ivDocIcon.setImageResource(R.drawable.ic_check)
        } else {
            b.root.setBackgroundColor(Color.TRANSPARENT)
            b.ivDocIcon.setImageResource(R.drawable.ic_summary)
        }

        // Hide action buttons in selection mode
        val btnVisibility = if (isSelectionMode) View.GONE else View.VISIBLE
        b.btnHistorySpeak.visibility = btnVisibility
        b.btnHistoryMore.visibility = btnVisibility
        b.btnHistoryDelete.visibility = btnVisibility

        // Long-press enters selection mode
        b.root.setOnLongClickListener {
            if (!isSelectionMode) {
                isSelectionMode = true
                selectedIds.add(entry.id)
                notifyDataSetChanged()
                onSelectionChanged?.invoke(selectedIds.size)
            }
            true
        }

        // Tap: toggle selection if in mode; otherwise no-op (action buttons handle normal clicks)
        b.root.setOnClickListener {
            if (isSelectionMode) {
                if (entry.id in selectedIds) selectedIds.remove(entry.id)
                else selectedIds.add(entry.id)
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) notifyItemChanged(pos)
                onSelectionChanged?.invoke(selectedIds.size)
            }
        }

        if (!isSelectionMode) {
            b.btnHistorySpeak.setOnClickListener {
                val intent = Intent(ctx, MainActivity::class.java).apply {
                    putExtra("LOAD_ID", entry.id)
                }
                ctx.startActivity(intent)
            }
            b.btnHistoryMore.setOnClickListener { showRenameDialog(ctx, entry) }
            b.btnHistoryDelete.setOnClickListener {
                lifecycleScope.launch {
                    AppDatabase.get(ctx).historyDao().delete(entry)
                }
            }
        }
    }

    private fun showRenameDialog(ctx: Context, entry: HistoryEntry) {
        val editText = EditText(ctx).apply {
            setText(entry.name)
            setSelection(entry.name.length)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val container = FrameLayout(ctx).apply {
            addView(editText)
            setPadding(pad, pad / 2, pad, 0)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Rename")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != entry.name) {
                    lifecycleScope.launch {
                        AppDatabase.get(ctx).historyDao().rename(entry.id, newName)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
