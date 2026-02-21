package com.example.blankapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.example.blankapp.databinding.FragmentNewNoteBinding
import com.google.android.material.snackbar.Snackbar

class NewNoteFragment : Fragment() {

    private var _binding: FragmentNewNoteBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNewNoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupModeDropdown()
        setupSaveButton()
        setupUploadButton()
    }

    private fun setupModeDropdown() {
        val modes = resources.getStringArray(R.array.mode_items)
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            modes
        )
        binding.modeAutoComplete.setAdapter(adapter)
        binding.modeAutoComplete.setText(modes[0], false)
    }

    private fun setupSaveButton() {
        binding.btnSaveNote.setOnClickListener {
            Snackbar.make(
                binding.root,
                "Save Note clicked — backend not connected yet",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupUploadButton() {
        binding.btnUploadImage.setOnClickListener {
            Snackbar.make(
                binding.root,
                "Upload Image — backend not connected yet",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
