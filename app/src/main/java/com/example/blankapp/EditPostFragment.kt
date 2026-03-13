package com.example.blankapp

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.blankapp.databinding.FragmentEditPostBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class EditPostFragment : Fragment() {

    private var _binding: FragmentEditPostBinding? = null
    private val binding get() = _binding!!

    private var postPath: String? = null
    private var sha: String? = null

    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        postPath = arguments?.getString(ARG_POST_PATH)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEditPostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val path = postPath
        if (path.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing post path", Toast.LENGTH_SHORT).show()
            return
        }

        binding.textPostPath.text = path
        loadSavedPassword()
        setupSavePasswordCheckbox()

        binding.btnReload.setOnClickListener { fetchPost(path) }
        binding.btnSaveChanges.setOnClickListener { savePost(path) }

        fetchPost(path)
    }

    private fun fetchPost(path: String) {
        setStatus("Loading…", StatusColor.SENDING)
        binding.progressLoading.visibility = View.VISIBLE
        binding.btnSaveChanges.isEnabled = false
        binding.btnReload.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ApiClient.getPostContent(path) }
            binding.progressLoading.visibility = View.GONE
            binding.btnReload.isEnabled = true

            if (result.success && result.content != null && result.sha != null) {
                sha = result.sha
                binding.editBody.setText(result.content)
                binding.btnSaveChanges.isEnabled = true
                setStatus("Loaded", StatusColor.NEUTRAL)
            } else {
                setStatus("Error", StatusColor.ERROR)
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun savePost(path: String) {
        val currentSha = sha
        if (currentSha.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing SHA. Reload the post first.", Toast.LENGTH_SHORT).show()
            return
        }

        val password = binding.editPassword.text?.toString().orEmpty()
        if (password.isBlank()) {
            Toast.makeText(requireContext(), "Password is required", Toast.LENGTH_SHORT).show()
            setStatus("Error", StatusColor.ERROR)
            return
        }
        if (binding.checkSavePassword.isChecked) savePassword(password)

        val content = binding.editBody.text?.toString().orEmpty()
        val lastmod = ZonedDateTime.now().format(isoFormatter)
        val clientIsoDate = inferClientIsoDate(path, content)

        setStatus("Saving…", StatusColor.SENDING)
        binding.btnSaveChanges.isEnabled = false
        binding.btnReload.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.updatePost(
                    password = password,
                    path = path,
                    sha = currentSha,
                    content = content,
                    clientIsoDate = clientIsoDate,
                    lastmod = lastmod
                )
            }

            binding.btnReload.isEnabled = true
            binding.btnSaveChanges.isEnabled = true

            if (result.success) {
                setStatus("Saved ✓", StatusColor.SUCCESS)
                result.path?.let { binding.textPublishedUrl.text = it }
                // Refresh SHA after an update, so subsequent edits work.
                fetchPost(path)
            } else {
                setStatus("Error", StatusColor.ERROR)
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun inferClientIsoDate(path: String, content: String): String? {
        val fromFrontmatter = extractFrontmatterValue(content, "date")?.trim()?.trim('"', '\'')
        if (!fromFrontmatter.isNullOrBlank()) return fromFrontmatter

        val name = path.substringAfterLast('/')
        val match = Regex("""^(\d{4}-\d{2}-\d{2})-""").find(name) ?: return null
        return match.groupValues[1] + "T00:00:00Z"
    }

    private fun extractFrontmatterValue(markdown: String, key: String): String? {
        if (!markdown.startsWith("---")) return null
        val afterFirst = markdown.removePrefix("---").trimStart('\n', '\r')
        val closingIndex = afterFirst.indexOf("\n---")
        if (closingIndex < 0) return null
        val fm = afterFirst.substring(0, closingIndex)
        val line = fm.lineSequence().firstOrNull { it.trimStart().startsWith("$key:") } ?: return null
        return line.substringAfter(":").trim()
    }

    private enum class StatusColor { NEUTRAL, SENDING, SUCCESS, ERROR }

    private fun setStatus(text: String, color: StatusColor) {
        binding.chipStatus.text = text
        val (bg, fg) = when (color) {
            StatusColor.NEUTRAL -> Pair(null, null)
            StatusColor.SENDING -> Pair(Color.parseColor("#FF8F00"), Color.WHITE)
            StatusColor.SUCCESS -> Pair(Color.parseColor("#2E7D32"), Color.WHITE)
            StatusColor.ERROR -> Pair(Color.parseColor("#B71C1C"), Color.WHITE)
        }
        if (bg != null) {
            binding.chipStatus.chipBackgroundColor = android.content.res.ColorStateList.valueOf(bg)
            binding.chipStatus.setTextColor(fg!!)
        } else {
            binding.chipStatus.chipBackgroundColor = null
            binding.chipStatus.setTextColor(binding.textPublishedUrl.currentTextColor)
        }
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

    companion object {
        private const val ARG_POST_PATH = "postPath"
        fun argsForPath(path: String) = Bundle().apply { putString(ARG_POST_PATH, path) }
    }
}

