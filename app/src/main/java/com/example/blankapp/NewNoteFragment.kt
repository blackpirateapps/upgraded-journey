package com.example.blankapp

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.example.blankapp.data.PendingPost
import com.example.blankapp.data.QueueStore
import com.example.blankapp.databinding.FragmentNewNoteBinding
import com.example.blankapp.sync.SyncWorker
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class NewNoteFragment : Fragment() {

    private var _binding: FragmentNewNoteBinding? = null
    private val binding get() = _binding!!

    private var currentMode = "Markdown"
    private var userBody = ""
    private var dateCreated: ZonedDateTime? = null
    private var dateLastMod: ZonedDateTime? = null
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    private var selectedImageUri: Uri? = null
    private var selectedImageName: String? = null

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
        setupDebugPanel()
        loadSavedPassword()
        setupSavePasswordCheckbox()
    }

    private fun setupModeDropdown() {
        val modes = resources.getStringArray(R.array.mode_items)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, modes)
        binding.modeAutoComplete.setAdapter(adapter)
        binding.modeAutoComplete.setText(modes[0], false)
        binding.modeAutoComplete.setOnItemClickListener { _, _, position, _ ->
            currentMode = modes[position]
            syncEditorToMode()
        }
    }

    private fun setupBodyWatcher() {
        binding.editBody.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isProgrammaticChange) return
                val now = ZonedDateTime.now()
                if (dateCreated == null && !s.isNullOrEmpty()) dateCreated = now
                if (!s.isNullOrEmpty()) dateLastMod = now
                userBody = extractBody(s.toString())
                if (currentMode == "Plain Text") {
                    val rebuilt = buildFullBlock()
                    if (rebuilt != s.toString()) {
                        isProgrammaticChange = true
                        val sel = binding.editBody.selectionStart.coerceAtMost(rebuilt.length)
                        binding.editBody.setText(rebuilt)
                        binding.editBody.setSelection(sel)
                        isProgrammaticChange = false
                    }
                }
            }
        })
    }

    private fun setupTagListeners() {
        val chipGroup = binding.chipGroupTags
        for (i in 0 until chipGroup.childCount) {
            (chipGroup.getChildAt(i) as? Chip)?.setOnCheckedChangeListener { _, _ ->
                if (currentMode == "Plain Text") syncEditorToMode()
            }
        }
        binding.editCustomTags.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (currentMode == "Plain Text") syncEditorToMode()
            }
        })
    }

    private fun getSelectedTags(): List<String> {
        val tags = mutableListOf<String>()
        val chipGroup = binding.chipGroupTags
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            if (chip?.isChecked == true) tags.add(chip.text.toString())
        }
        val custom = binding.editCustomTags.text.toString().trim()
        if (custom.isNotEmpty()) {
            custom.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tags.add(it) }
        }
        return tags
    }

    private fun buildFrontmatter(): String {
        val title = binding.editTitle.text.toString().trim()
        val tags = getSelectedTags()
        val tagArray = tags.joinToString(", ") { "\"$it\"" }
        val dateStr = dateCreated?.format(isoFormatter) ?: ""
        val lastModStr = dateLastMod?.format(isoFormatter) ?: ""
        return buildString {
            appendLine("---")
            appendLine("title: \"$title\"")
            appendLine("tags: [$tagArray]")
            appendLine("date: \"$dateStr\"")
            appendLine("lastmod: \"$lastModStr\"")
            appendLine("---")
        }
    }

    private fun buildFullBlock() = buildFrontmatter() + "\n" + userBody

    private fun extractBody(text: String): String {
        if (!text.startsWith("---")) return text
        val afterFirst = text.removePrefix("---").trimStart('\n', '\r')
        val closingIndex = afterFirst.indexOf("\n---")
        if (closingIndex < 0) return text
        return afterFirst.substring(closingIndex + 4).trimStart('\n', '\r')
    }

    private fun syncEditorToMode() {
        isProgrammaticChange = true
        binding.editBody.setText(if (currentMode == "Plain Text") buildFullBlock() else userBody)
        binding.editBody.setSelection(binding.editBody.text?.length ?: 0)
        isProgrammaticChange = false
    }

    private fun setupUploadButton() {
        binding.btnUploadImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
    }

    private fun handleImageSelected(uri: Uri) {
        val fileName = getFileName(uri) ?: "image.jpg"
        selectedImageUri = uri
        selectedImageName = fileName
        binding.textFileName.text = fileName
        val shortcode = binding.editShortcode.text.toString().replace("IMAGE_NAME", fileName)
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

    private fun readImageAsBase64(uri: Uri): String {
        val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return ""
        val mimeType = requireContext().contentResolver.getType(uri) ?: "image/jpeg"
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:$mimeType;base64,$encoded"
    }

    private fun setupDebugPanel() {
        binding.btnClearLog.setOnClickListener {
            binding.textDebugLog.text = ""
            setStatus("Idle", StatusColor.NEUTRAL)
        }
    }

    private enum class StatusColor { NEUTRAL, SENDING, SUCCESS, ERROR }

    private fun setStatus(text: String, color: StatusColor) {
        binding.chipStatus.text = text
        val (bg, fg) = when (color) {
            StatusColor.NEUTRAL -> Pair(null, null)
            StatusColor.SENDING -> Pair(Color.parseColor("#FF8F00"), Color.WHITE)
            StatusColor.SUCCESS -> Pair(Color.parseColor("#2E7D32"), Color.WHITE)
            StatusColor.ERROR   -> Pair(Color.parseColor("#B71C1C"), Color.WHITE)
        }
        if (bg != null) {
            binding.chipStatus.chipBackgroundColor = android.content.res.ColorStateList.valueOf(bg)
            binding.chipStatus.setTextColor(fg!!)
        } else {
            binding.chipStatus.chipBackgroundColor = null
            binding.chipStatus.setTextColor(binding.textDebugLog.currentTextColor)
        }
    }

    private fun appendDebug(msg: String) {
        val current = binding.textDebugLog.text.toString()
        binding.textDebugLog.text = if (current.isEmpty()) msg else "$current\n$msg"
    }

    private fun setupSaveButton() {
        binding.btnSaveNote.setOnClickListener {
            val password = binding.editPassword.text.toString()
            if (password.isBlank()) {
                appendDebug("✗ Password is required")
                binding.cardDebugLog.visibility = View.VISIBLE
                setStatus("Error", StatusColor.ERROR)
                return@setOnClickListener
            }
            if (binding.checkSavePassword.isChecked) savePassword(password)
            binding.cardDebugLog.visibility = View.VISIBLE
            binding.textDebugLog.text = ""
            setStatus("Sending…", StatusColor.SENDING)
            binding.btnSaveNote.isEnabled = false

            val title = binding.editTitle.text.toString().trim()
            val tags = getSelectedTags()
            val content = userBody
            val imagePath = binding.editImagePath.text.toString().trim()
            val shortcodeTemplate = binding.editShortcode.text.toString().trim()
            val imgUri = selectedImageUri
            val imgName = selectedImageName

            viewLifecycleOwner.lifecycleScope.launch {
                val imageData = if (imgUri != null) {
                    withContext(Dispatchers.IO) { readImageAsBase64(imgUri) }
                } else null

                val result = withContext(Dispatchers.IO) {
                    ApiClient.quickPost(
                        password = password, title = title, content = content, tags = tags,
                        imageData = imageData, imageName = imgName, imagePath = imagePath,
                        shortcodeTemplate = shortcodeTemplate,
                        onDebug = { msg -> activity?.runOnUiThread { appendDebug(msg) } }
                    )
                }

                if (result.success) {
                    binding.btnSaveNote.isEnabled = true
                    setStatus("Success ✓", StatusColor.SUCCESS)
                    result.path?.let { appendDebug("\n→ Published at:\n  $it") }
                } else {
                    appendDebug("\n✗ Failed or Offline: ${result.message}")
                    appendDebug("Adding to Sync Queue…")
                    
                    withContext(Dispatchers.IO) {
                        val store = QueueStore.getInstance(requireContext())
                        store.insert(PendingPost(
                            title = title, content = content, tags = tags.joinToString(","),
                            imageData = imageData, imageName = imgName, imagePath = imagePath,
                            shortcodeTemplate = shortcodeTemplate
                        ))
                    }
                    
                    scheduleSync()
                    binding.btnSaveNote.isEnabled = true
                    setStatus("Queued", StatusColor.NEUTRAL)
                    appendDebug("✓ Added to queue. Will sync in background.")
                }
            }
        }
    }

    private fun scheduleSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
            
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
            
        WorkManager.getInstance(requireContext()).enqueueUniqueWork(
            "sync_posts",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncRequest
        )
    }

    private fun loadSavedPassword() {
        val prefs = prefs()
        if (prefs.getBoolean("password_saved", false)) {
            binding.editPassword.setText(prefs.getString("saved_password", ""))
            binding.checkSavePassword.isChecked = true
        }
    }

    private fun savePassword(password: String) {
        prefs().edit().putString("saved_password", password).putBoolean("password_saved", true).apply()
    }

    private fun setupSavePasswordCheckbox() {
        binding.checkSavePassword.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                prefs().edit().remove("saved_password").putBoolean("password_saved", false).apply()
            }
        }
    }

    private fun prefs() = requireContext().getSharedPreferences("microblog_prefs", Context.MODE_PRIVATE)

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
