package microblog.blackpiratex.com

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import microblog.blackpiratex.com.databinding.FragmentEditPostBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

class EditPostFragment : Fragment() {

    private var _binding: FragmentEditPostBinding? = null
    private val binding get() = _binding!!

    private var postPath: String? = null
    private var sha: String? = null

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
        val backendLastmod = Instant.now().toString()
        val updatedContent = updateFrontmatterLastmod(content, backendLastmod)
        val clientIsoDate = normalizeIsoDate(extractFrontmatterValue(content, "date"))

        setStatus("Saving…", StatusColor.SENDING)
        binding.btnSaveChanges.isEnabled = false
        binding.btnReload.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ApiClient.updatePost(
                    password = password,
                    path = path,
                    sha = currentSha,
                    content = updatedContent,
                    clientIsoDate = clientIsoDate,
                    lastmod = backendLastmod
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

    private fun normalizeIsoDate(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().trim('"', '\'')
        if (Regex("""^\\d{4}-\\d{2}-\\d{2}$""").matches(cleaned)) return cleaned + "T00:00:00Z"

        return runCatching { java.time.OffsetDateTime.parse(cleaned).toInstant().toString() }.getOrNull()
            ?: runCatching { java.time.ZonedDateTime.parse(cleaned).toInstant().toString() }.getOrNull()
            ?: runCatching { Instant.parse(cleaned).toString() }.getOrNull()
    }

    private fun extractFrontmatterValue(markdown: String, key: String): String? {
        val newline = if (markdown.contains("\r\n")) "\r\n" else "\n"
        val startMarker = "---$newline"
        if (!markdown.startsWith(startMarker) && !markdown.startsWith("---\n") && !markdown.startsWith("---\r\n")) {
            return null
        }

        val startLen = when {
            markdown.startsWith("---\r\n") -> 5
            markdown.startsWith("---\n") -> 4
            else -> startMarker.length
        }

        val endNeedle = newline + "---"
        val endIndex = markdown.indexOf(endNeedle, startLen)
        if (endIndex < 0) return null

        val fm = markdown.substring(startLen, endIndex)
        val line = fm.lineSequence().firstOrNull { it.trimStart().startsWith("$key:") } ?: return null
        return line.substringAfter(":").trim()
    }

    private fun updateFrontmatterLastmod(markdown: String, newIso: String): String {
        val newline = if (markdown.contains("\r\n")) "\r\n" else "\n"
        val startMarker = "---$newline"
        if (!markdown.startsWith(startMarker) && !markdown.startsWith("---\n") && !markdown.startsWith("---\r\n")) {
            return markdown
        }

        val startLen = when {
            markdown.startsWith("---\r\n") -> 5
            markdown.startsWith("---\n") -> 4
            else -> startMarker.length
        }

        val endNeedle = newline + "---"
        val endIndex = markdown.indexOf(endNeedle, startLen)
        if (endIndex < 0) return markdown

        val fm = markdown.substring(startLen, endIndex)
        val body = markdown.substring(endIndex + endNeedle.length) // includes whatever newline(s) follow

        val lines = fm.split(Regex("\r?\n")).toMutableList()
        var replaced = false
        for (i in lines.indices) {
            val trimmed = lines[i].trimStart()
            if (!trimmed.startsWith("lastmod:")) continue

            val existingValue = trimmed.substringAfter(":").trim()
            val newValue = when {
                existingValue.startsWith("\"") && existingValue.endsWith("\"") -> "\"$newIso\""
                existingValue.startsWith("'") && existingValue.endsWith("'") -> "'$newIso'"
                else -> newIso
            }

            val indent = lines[i].takeWhile { it == ' ' || it == '\t' }
            lines[i] = indent + "lastmod: " + newValue
            replaced = true
            break
        }

        if (!replaced) {
            // Add lastmod inside existing frontmatter (do not create new frontmatter).
            val insertAt = lines.indexOfFirst { it.trimStart().startsWith("date:") }
            if (insertAt >= 0) {
                lines.add(insertAt + 1, "lastmod: \"$newIso\"")
            } else {
                lines.add("lastmod: \"$newIso\"")
            }
        }

        val newFrontmatter = lines.joinToString(newline)
        return "---$newline" + newFrontmatter + newline + "---" + body
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
