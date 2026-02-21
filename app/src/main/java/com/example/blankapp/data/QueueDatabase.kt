package com.example.blankapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "pending_posts")
data class PendingPost(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val tags: String, // Comma-separated or JSON array string
    val imageData: String?,
    val imageName: String?,
    val imagePath: String,
    val shortcodeTemplate: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface PendingPostDao {
    @Query("SELECT * FROM pending_posts ORDER BY timestamp ASC")
    fun getAll(): Flow<List<PendingPost>>

    @Insert
    suspend fun insert(post: PendingPost)

    @Delete
    suspend fun delete(post: PendingPost)

    @Query("SELECT * FROM pending_posts LIMIT 1")
    suspend fun getNext(): PendingPost?
}

@Database(entities = [PendingPost], version = 1)
abstract class QueueDatabase : RoomDatabase() {
    abstract fun pendingPostDao(): PendingPostDao

    companion object {
        @Volatile
        private var INSTANCE: QueueDatabase? = null

        fun getDatabase(context: android.content.Context): QueueDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    QueueDatabase::class.java,
                    "queue_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
