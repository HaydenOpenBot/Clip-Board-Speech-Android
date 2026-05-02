package com.clipboardspeech

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.clipboardspeech.databinding.FragmentSummarySheetBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar

class SummaryBottomSheet : BottomSheetDialogFragment() {

    interface OnUseTtsListener {
        fun onUseTts(text: String)
    }

    private var _binding: FragmentSummarySheetBinding? = null
    private val binding get() = _binding!!

    var onUseTtsListener: OnUseTtsListener? = null

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_CONTENT = "content"
        private const val ARG_LOADING = "loading"

        fun newInstance(title: String, content: String = "", loading: Boolean = false): SummaryBottomSheet {
            return SummaryBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_CONTENT, content)
                    putBoolean(ARG_LOADING, loading)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSummarySheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = arguments?.getString(ARG_TITLE) ?: "Summary"
        val content = arguments?.getString(ARG_CONTENT) ?: ""
        val loading = arguments?.getBoolean(ARG_LOADING) ?: false

        binding.tvSummaryTitle.text = title

        if (loading) {
            binding.progressSummary.visibility = View.VISIBLE
            binding.scrollSummary.visibility = View.GONE
            binding.btnUseTts.isEnabled = false
            binding.btnCopySummary.isEnabled = false
        } else {
            binding.progressSummary.visibility = View.GONE
            binding.scrollSummary.visibility = View.VISIBLE
            binding.tvSummaryContent.text = content
        }

        binding.btnUseTts.setOnClickListener {
            onUseTtsListener?.onUseTts(binding.tvSummaryContent.text.toString())
            dismiss()
        }

        binding.btnCopySummary.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Summary", binding.tvSummaryContent.text))
            Snackbar.make(binding.root, "Copied to clipboard", Snackbar.LENGTH_SHORT).show()
        }
    }

    fun updateContent(content: String) {
        if (_binding == null) return
        binding.progressSummary.visibility = View.GONE
        binding.scrollSummary.visibility = View.VISIBLE
        binding.tvSummaryContent.text = content
        binding.btnUseTts.isEnabled = true
        binding.btnCopySummary.isEnabled = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
