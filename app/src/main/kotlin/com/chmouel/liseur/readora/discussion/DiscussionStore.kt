package com.chmouel.liseur.readora.discussion

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One discussion thread per marked passage (Readora S3).
 *
 * The anchor is an `annotations` row: a highlight or a passage note.
 * One thread per anchor is the product's promise — re-opening a marked
 * passage continues the same conversation — which the UNIQUE index
 * enforces at the storage layer; a second open of the same anchor finds
 * the first thread rather than founding a twin.
 */
@Entity(
    tableName = "discussion_threads",
    indices = [
        Index(value = ["annotation_id"], unique = true),
        Index(value = ["book_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = com.chmouel.liseur.data.db.BookAnnotation::class,
            parentColumns = ["id"],
            childColumns = ["annotation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DiscussionThread(
    @PrimaryKey val id: String,
    /** The book the anchored passage belongs to, for the notes centre. */
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "annotation_id") val annotationId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * One turn in a thread. User turns carry what was sent; assistant
 * turns carry what came back and the model that said it, as a snapshot
 * of the config at answer time — later edits to a model config must
 * never rewrite history.
 */
@Entity(
    tableName = "discussion_messages",
    indices = [Index(value = ["thread_id"]), Index(value = ["thread_id", "created_at"])],
    foreignKeys = [
        ForeignKey(
            entity = DiscussionThread::class,
            parentColumns = ["id"],
            childColumns = ["thread_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DiscussionMessage(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "thread_id") val threadId: String,
    /** "user" or "assistant". */
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    /**
     * SENDING while the request is out, SUCCESS once answered, FAILED
     * when it could not be sent or answered. A FAILED turn keeps the
     * reader's words so a retry costs nothing.
     */
    @ColumnInfo(name = "status") val status: String,
    /** The config chosen for this turn; null for the reader's own words. */
    @ColumnInfo(name = "model_config_id") val modelConfigId: String?,
    /**
     * The model id as it was named when the answer arrived, so renaming
     * or re-pointing a config leaves past answers correctly attributed.
     */
    @ColumnInfo(name = "model_id_snapshot") val modelIdSnapshot: String?,
    /**
     * What the context builder actually sent with this turn: passage,
     * flanks, book and chapter names, history depth. JSON, kept so the
     * reader can see what the model was shown.
     */
    @ColumnInfo(name = "context_snapshot") val contextSnapshot: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val STATUS_SENDING = "SENDING"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILED = "FAILED"
    }
}

/** One configured OpenAI-compatible endpoint the reader may ask. */
@Entity(tableName = "model_configs")
data class ModelConfig(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "label") val label: String,
    /**
     * The API root, e.g. `https://api.example.com/v1`. Stored as given;
     * the gateway normalises and enforces the transport policy.
     */
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "model_id") val modelId: String,

    /** Readora: the key itself never lives in this table or any export. */
    @ColumnInfo(name = "key_alias") val keyAlias: String? = null,
    @ColumnInfo(name = "is_default") val isDefault: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface DiscussionThreadDao {
    @Query("SELECT * FROM discussion_threads WHERE annotation_id = :annotationId LIMIT 1")
    suspend fun forAnnotation(annotationId: String): DiscussionThread?

    /** The thread an annotation anchors, live; null until the first ask founds it. */
    @Query("SELECT * FROM discussion_threads WHERE annotation_id = :annotationId LIMIT 1")
    fun observeForAnnotation(annotationId: String): Flow<DiscussionThread?>

    @Query("SELECT * FROM discussion_threads WHERE book_id = :bookId")
    fun observeForBook(bookId: String): Flow<List<DiscussionThread>>

    @Upsert
    suspend fun upsert(thread: DiscussionThread)

    /**
     * The thread for an annotation, created if absent. Room's insert
     * with IGNORE makes a race between two first-opens collapse into
     * one row; the second opener then reads the winner.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(thread: DiscussionThread): Long

    @Query("DELETE FROM discussion_threads WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface DiscussionMessageDao {
    @Query("SELECT * FROM discussion_messages WHERE thread_id = :threadId ORDER BY created_at")
    fun observeThread(threadId: String): Flow<List<DiscussionMessage>>

    /** The turns of the thread an annotation anchors, live. */
    @Query(
        "SELECT m.* FROM discussion_messages m JOIN discussion_threads t " +
            "ON m.thread_id = t.id WHERE t.annotation_id = :annotationId ORDER BY m.created_at",
    )
    fun observeForAnnotation(annotationId: String): Flow<List<DiscussionMessage>>

    @Query("SELECT * FROM discussion_messages WHERE thread_id = :threadId ORDER BY created_at")
    suspend fun forThread(threadId: String): List<DiscussionMessage>

    @Upsert
    suspend fun upsert(message: DiscussionMessage)

    @Query("UPDATE discussion_messages SET status = :status, content = :content WHERE id = :id")
    suspend fun settle(id: String, status: String, content: String)
}

@Dao
interface ModelConfigDao {
    @Query("SELECT * FROM model_configs ORDER BY created_at")
    fun observeAll(): Flow<List<ModelConfig>>

    @Query("SELECT * FROM model_configs ORDER BY created_at")
    suspend fun all(): List<ModelConfig>

    @Query("SELECT * FROM model_configs WHERE id = :id")
    suspend fun byId(id: String): ModelConfig?

    @Query("SELECT * FROM model_configs WHERE is_default LIMIT 1")
    suspend fun defaultConfig(): ModelConfig?

    @Query("SELECT * FROM model_configs WHERE is_default LIMIT 1")
    fun observeDefault(): Flow<ModelConfig?>

    @Upsert
    suspend fun upsert(config: ModelConfig)

    @Query("DELETE FROM model_configs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE model_configs SET is_default = (id = :id)")
    suspend fun setDefault(id: String)

}
