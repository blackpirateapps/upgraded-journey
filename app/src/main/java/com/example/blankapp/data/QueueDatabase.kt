package com.example.blankapp.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PendingPost(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val content: String,
    val tags: String,
    val imageData: String?,
    val imageName: String?,
    val imagePath: String,
    val shortcodeTemplate: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("content", content)
        put("tags", tags)
        put("imageData", imageData ?: "")
        put("imageName", imageName ?: "")
        put("imagePath", imagePath)
        put("shortcodeTemplate", shortcodeTemplate)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject) = PendingPost(
            id = json.optLong("id", System.currentTimeMillis()),
            title = json.optString("title", ""),
            content = json.optString("content", ""),
            tags = json.optString("tags", ""),
            imageData = json.optString("imageData", "").ifEmpty { null },
            imageName = json.optString("imageName", "").ifEmpty { null },
            imagePath = json.optString("imagePath", "assets/img"),
            shortcodeTemplate = json.optString("shortcodeTemplate", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis())
        )
    }
}

/**
 * Lightweight queue backed by SharedPreferences + JSON.
 * No Room, no kapt, no annotation processing needed.
 */
class QueueStore(context: Context) {

    private val prefs = context.getSharedPreferences("sync_queue", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(loadAll())

    fun getAll(): Flow<List<PendingPost>> = _items.asStateFlow()

    fun getAllSync(): List<PendingPost> = _items.value

    fun insert(post: PendingPost) {
        val list = loadAll().toMutableList()
        list.add(post)
        save(list)
        _items.value = list
    }

    fun delete(post: PendingPost) {
        val list = loadAll().toMutableList()
        list.removeAll { it.id == post.id }
        save(list)
        _items.value = list
    }

    fun getNext(): PendingPost? = loadAll().firstOrNull()

    private fun loadAll(): List<PendingPost> {
        val raw = prefs.getString("queue", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { PendingPost.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(list: List<PendingPost>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("queue", arr.toString()).apply()
    }

    companion object {
        @Volatile private var INSTANCE: QueueStore? = null
        fun getInstance(context: Context): QueueStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: QueueStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
