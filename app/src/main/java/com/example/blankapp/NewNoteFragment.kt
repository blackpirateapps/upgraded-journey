package com.example.blankapp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.example.blankapp.databinding.FragmentNewNoteBinding
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar

class NewNoteFragment : Fragment() {

    private var _binding: FragmentNewNoteBinding? = null
    private val binding get() = _binding!!

    private var currentMode = "Markdown"
    private var userContent = ""
    private var selectedImageName: String? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }

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
        setupFrontmatterListeners()
        loadSavedPassword()
        setupSavePasswordCheckbox()
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

        binding.modeAutoComplete.setOnItemClickListener { _, _, position, _ ->
            currentMode = modes[position]
            updateBodyForMode()
        }
    }

    private fun setupFrontmatterListeners() {
        // Listen to title changes
        binding.editTitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (currentMode == "Plain Text") {
                    updateBodyForMode()
                }
            }
        })

        // Listen to custom tags changes
        binding.editCustomTags.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (currentMode == "Plain Text") {
                    updateBodyForMode()
                }
            }
        })

        // Listen to chip changes
        val chipIds = listOf(
            binding.chipWebsite, binding.chipQuotes, binding.chipArticles,
            binding.chipBookmarks, binding.chipStudy
        )
        chipIds.forEach { chip ->
            chip.setOnCheckedChangeListener { _, _ ->
                if (currentMode == "Plain Text") {
                    updateBodyForMode()
                }
            }
        }
    }

    private fun getSelectedTags(): List<String> {
        val tags = mutableListOf<String>()
        val chipGroup = binding.chipGroupTags
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            if (chip?.isChecked == true) {
                tags.add(chip.text.toString())
            }
        }
        // Add custom tags
        val customTags = binding.editCustomTags.text.toString().trim()
        if (customTags.isNotEmpty() && customTags != getString(R.string.hint_custom_tags)) {
            customTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                tags.add(it)
            }
        }
        return tags
    }

    private fun buildFrontmatter(): String {
        val title = binding.editTitle.text.toString().trim()
        val tags = getSelectedTags()

        val sb = StringBuilder()
        sb.appendLine("---")
        if (title.isNotEmpty()) {
            sb.appendLine("title: \"$title\"")
        }
        if (tags.isNotEmpty()) {
            sb.appendLine("tags:")
            tags.forEach { sb.appendLine("  - $it") }
        }
        sb.appendLine("---")
        return sb.toString()
    }

    private fun updateBodyForMode() {
        when (currentMode) {
            "Plain Text" -> {
                // Save user content without frontmatter
                val currentText = binding.editBody.text.toString()
                if (!currentText.startsWith("---")) {
                    userContent = currentText
                } else {
                    // Extract content after second ---
                    val parts = currentText.split("---", limit = 3)
                    userContent = if (parts.size >= 3) parts[2].trimStart('\n') else ""
                }
                // Show frontmatter + content
                val frontmatter = buildFrontmatter()
                val fullText = frontmatter + "\n" + userContent
                binding.editBody.removeTextChangedListener(bodyWatcher)
                binding.editBody.setText(fullText)
                binding.editBody.addTextChangedListener(bodyWatcher)
            }
            "Markdown" -> {
                // Save current state if switching from Plain Text
                val currentText = binding.editBody.text.toString()
                if (currentText.startsWith("---")) {
                    val parts = currentText.split("---", limit = 3)
                    userContent = if (parts.size >= 3) parts[2].trimStart('\n') else ""
                } else {
                    userContent = currentText
                }
                // Show only user content
                binding.editBody.removeTextChangedListener(bodyWatcher)
                binding.editBody.setText(userContent)
                binding.editBody.addTextChangedListener(bodyWatcher)
            }
        }
    }

    private val bodyWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (currentMode == "Markdown") {
                userContent = s.toString()
            }
        }
    }

    private fun setupUploadButton() {
        binding.btnUploadImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
    }

    private fun handleImageSelected(uri: Uri) {
        val fileName = getFileName(uri) ?: "image.jpg"
        selectedImageName = fileName
        binding.textFileName.text = fileName

        // Build the shortcode from the template
        val shortcodeTemplate = binding.editShortcode.text.toString()
        val shortcode = shortcodeTemplate.replace("IMAGE_NAME", fileName)

        // Insert shortcode into body
        val currentText = if (currentMode == "Markdown") {
            userContent
        } else {
            val bodyText = binding.editBody.text.toString()
            if (bodyText.startsWith("---")) {
                val parts = bodyText.split("---", limit = 3)
                if (parts.size >= 3) parts[2].trimStart('\n') else ""
            } else {
                bodyText
            }
        }

        userContent = if (currentText.isEmpty()) {
            shortcode
        } else {
            "$currentText\n$shortcode"
        }

        updateBodyForMode()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.substringAfterLast('/')
        }
        return result
    }

    private fun setupSaveButton() {
        binding.btnSaveNote.setOnClickListener {
            // Save password if checkbox is checked
            if (binding.checkSavePassword.isChecked) {
                savePassword(binding.editPassword.text.toString())
            }

            Snackbar.make(
                binding.root,
                "Save Note clicked — backend not connected yet",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun loadSavedPassword() {
        val prefs = requireContext().getSharedPreferences("microblog_prefs", Context.MODE_PRIVATE)
        val savedPassword = prefs.getString("saved_password", null)
        val passwordSaved = prefs.getBoolean("password_saved", false)
        if (passwordSaved && savedPassword != null) {
            binding.editPassword.setText(savedPassword)
            binding.checkSavePassword.isChecked = true
        }
    }

    private fun savePassword(password: String) {
        val prefs = requireContext().getSharedPreferences("microblog_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("saved_password", password)
            .putBoolean("password_saved", true)
            .apply()
    }

    private fun setupSavePasswordCheckbox() {
        binding.checkSavePassword.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                // Clear saved password when unchecked
                val prefs = requireContext().getSharedPreferences("microblog_prefs", Context.MODE_PRIVATE)
                prefs.edit()
                    .remove("saved_password")
                    .putBoolean("password_saved", false)
                    .apply()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
