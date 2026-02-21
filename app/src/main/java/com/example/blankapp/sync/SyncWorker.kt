package com.example.blankapp.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.blankapp.ApiClient
import com.example.blankapp.data.QueueStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = QueueStore.getInstance(applicationContext)
        
        val prefs = applicationContext.getSharedPreferences("microblog_prefs", Context.MODE_PRIVATE)
        val password = prefs.getString("saved_password", null) ?: return@withContext Result.failure()

        var nextPost = store.getNext()
        while (nextPost != null) {
            val tagsList = if (nextPost.tags.isEmpty()) emptyList() else nextPost.tags.split(",")

            val postResult = ApiClient.quickPost(
                password = password,
                title = nextPost.title,
                content = nextPost.content,
                tags = tagsList,
                imageData = nextPost.imageData,
                imageName = nextPost.imageName,
                imagePath = nextPost.imagePath,
                shortcodeTemplate = nextPost.shortcodeTemplate
            )

            if (postResult.success) {
                store.delete(nextPost)
                nextPost = store.getNext()
            } else {
                return@withContext Result.retry()
            }
        }

        Result.success()
    }
}
