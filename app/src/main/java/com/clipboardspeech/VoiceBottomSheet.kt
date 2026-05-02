package com.clipboardspeech

import android.os.Bundle
import android.speech.tts.Voice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clipboardspeech.databinding.FragmentVoiceSheetBinding
import com.clipboardspeech.databinding.ItemVoiceBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class VoiceBottomSheet : BottomSheetDialogFragment() {

    interface VoiceSelectionListener {
        fun onVoiceSelected(voice: Voice, index: Int)
    }

    private var _binding: FragmentVoiceSheetBinding? = null
    private val binding get() = _binding!!

    private var voices: List<Voice> = emptyList()
    private var selectedIndex: Int = -1
    private var listener: VoiceSelectionListener? = null

    companion object {
        private const val ARG_SELECTED_INDEX = "selected_index"
        private const val ARG_DATA_TAG = "data_tag"

        // Keyed by UUID tag so Voice (non-Parcelable) and listener survive rotation
        private val pendingData = mutableMapOf<String, Pair<List<Voice>, VoiceSelectionListener>>()

        fun newInstance(
            voices: List<Voice>,
            selectedIndex: Int,
            listener: VoiceSelectionListener
        ): VoiceBottomSheet {
            val tag = java.util.UUID.randomUUID().toString()
            pendingData[tag] = Pair(voices, listener)
            return VoiceBottomSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_SELECTED_INDEX, selectedIndex)
                    putString(ARG_DATA_TAG, tag)
                }
            }
        }
    }

    override fun getTheme(): Int = R.style.Theme_ClipBoardSpeech_BottomSheetDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tag = arguments?.getString(ARG_DATA_TAG) ?: return
        val data = pendingData[tag] ?: return
        voices = data.first
        listener = data.second
        selectedIndex = arguments?.getInt(ARG_SELECTED_INDEX, -1) ?: -1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVoiceSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = VoiceAdapter(voices, selectedIndex) { voice, index ->
            listener?.onVoiceSelected(voice, index)
            dismiss()
        }

        binding.rvVoices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvVoices.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // Remove from map to avoid memory leak (D-005)
        arguments?.getString(ARG_DATA_TAG)?.let { pendingData.remove(it) }
    }

    // ---- inner adapter ----

    private class VoiceAdapter(
        private val voices: List<Voice>,
        private val selectedIndex: Int,
        private val onSelected: (Voice, Int) -> Unit
    ) : RecyclerView.Adapter<VoiceAdapter.VoiceViewHolder>() {

        inner class VoiceViewHolder(val binding: ItemVoiceBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
            val binding = ItemVoiceBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VoiceViewHolder(binding)
        }

        override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
            val voice = voices[position]
            val b = holder.binding

            b.tvVoiceItemName.text = voice.name
            b.tvVoiceLocale.text = voice.locale.displayName
            b.tvVoiceNetwork.text = if (voice.isNetworkConnectionRequired)
                b.root.context.getString(R.string.label_network)
            else
                b.root.context.getString(R.string.label_on_device)

            val isSelected = position == selectedIndex
            b.ivCheck.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            b.voiceItemRoot.setBackgroundResource(
                if (isSelected) R.drawable.bg_voice_row_selected else 0
            )

            b.voiceItemRoot.setOnClickListener {
                onSelected(voice, position)
            }
        }

        override fun getItemCount(): Int = voices.size
    }
}
