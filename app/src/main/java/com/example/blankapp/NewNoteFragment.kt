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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class NewNoteFragment : Fragment() {

    private var _binding: FragmentNewNoteBinding? = null
    private val binding get() = _binding!!

    // "Markdown" = user sees only body; "Plain Text" / Raw = user sees full frontmatter block
    private var currentMode = "Markdown"

    // The raw user-written body (never includes frontmatter)
    private var userBody = ""

    // Timestamp set once on the very first keystroke in the body field
    private var dateCreated: ZonedDateTime? = null

    // Updated on every body keystroke
    private var dateLastMod: ZonedDateTime? = null

    private val isoFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    // Flag to avoid recursive TextWatcher loops when we programmatically update the field
    private var isProgrammaticChange = false

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNewNoteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupModeDropdown()
        setupBodyWatcher()
        setupTagListeners()
        setupUploadButton()
        setupSaveButton()
        loadSavedPassword()
        setupSavePasswordCheckbox()
    }

    // ─── Mode dropdown ───────────────────────────────────────────────────────

    private fun setupModeDropdown() {
        val modes = resources.getStringArray(R.array.mode_items) // ["Markdown", "Plain Text"]
        val adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_dropdown_item_1line, modes
        )
        binding.modeAutoComplete.setAdapter(adapter)
        binding.modeAutoComplete.setText(modes[0], false)

        binding.modeAutoComplete.setOnItemClickListener { _, _, position, _ ->
            currentMode = modes[position]
            syncEditorToMode()
        }
    }

    // ─── Body text watcher ───────────────────────────────────────────────────

    private fun setupBodyWatcher() {
        binding.editBody.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isProgrammaticChange) return

                val now = ZonedDateTime.now()
                // First keystroke → set date
                if (dateCreated == null && !s.isNullOrEmpty()) {
                    dateCreated = now
                }
                // Every keystroke → update lastmod
                if (!s.isNullOrEmpty()) {
                    dateLastMod = now
                }

                // Extract body from what's visible
                userBody = extractBody(s.toString())
                // In raw mode, keep the full block in sync without re‑rendering (would loop)
                if (currentMode == "Plain Text") {
                    // Rebuild frontmatter+body and set, guarding the watcher
                    val rebuilt = buildFullBlock()
                    if (rebuilt != s.toString()) {
                        isProgrammaticChange = true
                        val selStart = binding.editBody.selectionStart
                        binding.editBody.setText(rebuilt)
                        // Try to restore cursor reasonably
                        binding.editBody.setSelection(
                            selStart.coerceAtMost(rebuilt.length)
                        )
                        isProgrammaticChange = false
                    }
                }
            }
        })
    }

    // ─── Tag listeners ───────────────────────────────────────────────────────

    private fun setupTagListeners() {
        val chipGroup = binding.chipGroupTags
        for (i in 0 until chipGroup.childCount) {
            (chipGroup.getChildAt(i) as? Chip)?.setOnCheckedChangeListener { _, _ ->
                onMetaChanged()
            }
        }
        binding.editCustomTags.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { onMetaChanged() }
        })
    }

    /** Called whenever title / tags change so Raw mode stays up to date */
    private fun onMetaChanged() {
        if (currentMode == "Plain Text") {
            syncEditorToMode()
        }
    }

    // ─── Frontmatter helpers ─────────────────────────────────────────────────

    private fun getSelectedTags(): List<String> {
        val tags = mutableListOf<String>()
        val chipGroup = binding.chipGroupTags
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            if (chip?.isChecked == true) tags.add(chip.text.toString())
        }
        val custom = binding.editCustomTags.text.toString().trim()
        val placeholder = getString(R.string.hint_custom_tags)
        if (custom.isNotEmpty() && custom != placeholder) {
            custom.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tags.add(it) }
        }
        return tags
    }

    private fun buildFrontmatter(): String {
        val title = binding.editTitle.text.toString().trim()
        val tags = getSelectedTags()
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("title: \"$title\"")
        val tagArray = tags.joinToString(", ") { "\"$it\"" }
        sb.appendLine("tags: [$tagArray]")
        val dateStr = dateCreated?.format(isoFormatter) ?: ""
        sb.appendLine("date: \"$dateStr\"")
        val lastModStr = dateLastMod?.format(isoFormatter) ?: ""
        sb.appendLine("lastmod: \"$lastModStr\"")
        sb.appendLine("---")
        return sb.toString()
    }

    private fun buildFullBlock(): String {
        return buildFrontmatter() + "\n" + userBody
    }

    /** Strip the frontmatter block (if present) and return just the body */
    private fun extractBody(text: String): String {
        if (!text.startsWith("---")) return text
        val afterFirst = text.removePrefix("---").trimStart('\n', '\r')
        val closingIndex = afterFirst.indexOf("\n---")
        if (closingIndex < 0) return text
        return afterFirst.substring(closingIndex + 4) // skip "\n---"
            .trimStart('\n', '\r')
    }

    // ─── Sync editor to mode ─────────────────────────────────────────────────

    private fun syncEditorToMode() {
        isProgrammaticChange = true
        when (currentMode) {
            "Markdown" -> {
                // Show only the body content
                binding.editBody.setText(userBody)
            }
            "Plain Text" -> {
                // Show frontmatter + body
                binding.editBody.setText(buildFullBlock())
            }
        }
        binding.editBody.setSelection(binding.editBody.text?.length ?: 0)
        isProgrammaticChange = false
    }

    // ─── Image picker ────────────────────────────────────────────────────────

    private fun setupUploadButton() {
        binding.btnUploadImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
    }

    private fun handleImageSelected(uri: Uri) {
        val fileName = getFileName(uri) ?: "image.jpg"
        binding.textFileName.text = fileName

        val shortcodeTemplate = binding.editShortcode.text.toString()
        val shortcode = shortcodeTemplate.replace("IMAGE_NAME", fileName)

        // Append shortcode as a new line in the user body
        userBody = if (userBody.isEmpty()) shortcode else "$userBody\n$shortcode"

        syncEditorToMode()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        }
        return result ?: uri.path?.substringAfterLast('/')
    }

    // ─── Save ────────────────────────────────────────────────────────────────

    private fun setupSaveButton() {
        binding.btnSaveNote.setOnClickListener {
            if (binding.checkSavePassword.isChecked) {
                savePassword(binding.editPassword.text.toString())
            }
            Snackbar.make(
                binding.root,
                "Save Note — backend not connected yet",
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    // ─── Password persistence ────────────────────────────────────────────────

    private fun loadSavedPassword() {
        val prefs = prefs()
        if (prefs.getBoolean("password_saved", false)) {
            binding.editPassword.setText(prefs.getString("saved_password", ""))
            binding.checkSavePassword.isChecked = true
        }
    }

    private fun savePassword(password: String) {
        prefs().edit().putString("saved_password", password).putBoolean("password_saved", true)
            .apply()
    }

    private fun setupSavePasswordCheckbox() {
        binding.checkSavePassword.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                prefs().edit().remove("saved_password").putBoolean("password_saved", false).apply()
            }
        }
    }

    private fun prefs() =
        requireContext().getSharedPreferences("microblog_prefs", Context.MODE_PRIVATE)

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
