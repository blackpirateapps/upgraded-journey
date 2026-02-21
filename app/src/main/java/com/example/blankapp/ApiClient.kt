package com.example.blankapp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class PostResult(
    val success: Boolean,
    val message: String,
    val path: String? = null,
    val debugLog: String = ""
)

object ApiClient {

    private const val BASE_URL = "https://post-to-status.vercel.app"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Sends a post to POST /api/quick-post.
     *
     * @param password     The POST_PASSWORD value
     * @param title        Note title (optional)
     * @param content      Note body in Markdown
     * @param tags         List of tag strings
     * @param imageData    Base64 data URL string (optional)
     * @param imageName    Original image filename (required if imageData is set)
     * @param imagePath    Upload path in repo (default: assets/img)
     * @param shortcodeTemplate Template with IMAGE_NAME token
     * @param onDebug      Callback called incrementally with debug messages
     */
    suspend fun quickPost(
        password: String,
        title: String,
        content: String,
        tags: List<String>,
        imageData: String? = null,
        imageName: String? = null,
        imagePath: String = "assets/img",
        shortcodeTemplate: String = "{{< img src=\"/img/IMAGE_NAME\" >}}",
        onDebug: (String) -> Unit = {}
    ): PostResult {
        val log = StringBuilder()

        fun debug(msg: String) {
            log.appendLine(msg)
            onDebug(msg)
        }

        return try {
            val json = JSONObject().apply {
                put("password", password)
                put("content", content)
                if (title.isNotBlank()) put("title", title)
                if (tags.isNotEmpty()) put("tags", JSONArray(tags))
                if (!imageData.isNullOrBlank()) put("imageData", imageData)
                if (!imageName.isNullOrBlank()) put("imageName", imageName)
                put("imagePath", imagePath)
                put("shortcodeTemplate", shortcodeTemplate)
            }

            debug("→ POST $BASE_URL/api/quick-post")
            debug("  title: \"$title\"")
            debug("  content length: ${content.length} chars")
            debug("  tags: $tags")
            debug("  image: ${if (imageName != null) imageName else "none"}")
            debug("  imagePath: $imagePath")

            val requestBody = json.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$BASE_URL/api/quick-post")
                .post(requestBody)
                .build()

            debug("")
            debug("Sending request…")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val code = response.code

            debug("← HTTP $code")
            debug("  Response: $responseBody")

            if (response.isSuccessful) {
                val responseJson = runCatching { JSONObject(responseBody) }.getOrNull()
                val msg = responseJson?.optString("message", "Success") ?: "Success"
                val path = responseJson?.optString("path")
                debug("✓ $msg")
                PostResult(true, msg, path, log.toString())
            } else {
                val responseJson = runCatching { JSONObject(responseBody) }.getOrNull()
                val errMsg = responseJson?.optString("error", "HTTP $code") ?: "HTTP $code"
                debug("✗ Error: $errMsg")
                PostResult(false, errMsg, null, log.toString())
            }
        } catch (e: Exception) {
            debug("✗ Exception: ${e.javaClass.simpleName}: ${e.message}")
            PostResult(false, e.message ?: "Unknown error", null, log.toString())
        }
    }

    /**
     * Fetches all posts from GET /api/get-posts
     */
    suspend fun getPosts(): List<PostItem> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$BASE_URL/api/get-posts")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val json = JSONObject(body)
                val postsArray = json.getJSONArray("posts")
                val items = mutableListOf<PostItem>()
                for (i in 0 until postsArray.length()) {
                    val obj = postsArray.getJSONObject(i)
                    items.add(
                        PostItem(
                            name = obj.getString("name"),
                            path = obj.getString("path"),
                            url = obj.getString("url")
                        )
                    )
                }
                items
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

data class PostItem(val name: String, val path: String, val url: String)
