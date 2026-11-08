package com.chmouel.liseur.readora.discussion

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Opens, continues and persists a marked passage's discussion thread
 * (Readora S3).
 *
 * Every send goes through [ask]: the only door that produces a network
 * request, opened solely by the reader pressing send in the sheet. The
 * user turn is written before the request and marked FAILED with its
 * words kept when the request does not come back, so nothing the reader
 * typed is ever lost to a failure.
 */
class DiscussionRepository(
    private val threads: DiscussionThreadDao,
    private val messages: DiscussionMessageDao,
    private val configs: ModelConfigDao,
    private val keys: ConfigKeyStore,
    private val gateway: AiModelGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val sending = Mutex()

    /** The thread an annotation anchors, created on first open. */
    suspend fun threadFor(bookId: String, annotationId: String): DiscussionThread {
        threads.forAnnotation(annotationId)?.let { return it }
        val thread = DiscussionThread(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            annotationId = annotationId,
            createdAt = now(),
            updatedAt = now(),
        )
        // A race between two first-opens collapses here: the loser's
        // insert is ignored by the unique annotation index and it reads
        // the winner's row instead.
        threads.insertIgnoring(thread)
        return threads.forAnnotation(annotationId) ?: thread
    }

    fun observeMessages(threadId: String): Flow<List<DiscussionMessage>> =
        messages.observeThread(threadId)

    /**
     * The turns of whatever thread an annotation anchors, live across
     * the founding of that thread: the anchor id is all the sheet
     * holds before the first ask, and the thread it names changes from
     * none to one the moment the first turn is sent.
     */
    fun observeMessagesFor(annotationId: String): Flow<List<DiscussionMessage>> =
        messages.observeForAnnotation(annotationId)

    /**
     * Sends one turn and stores both sides of it.
     *
     * @param buildContext given the history so far, the context the
     * question travels with; only built once a send is truly happening,
     * so merely opening a sheet or typing reads nothing.
     */
    suspend fun ask(
        bookId: String,
        annotationId: String,
        question: String,
        configId: String?,
        buildContext: suspend (List<Pair<String, String>>) -> DiscussionContext,
    ): Result<Unit> = sending.withLock {
        val config = configId?.let { configs.byId(it) } ?: configs.defaultConfig()
            ?: return Result.failure(DiscussionSendException.NoModelConfigured())

        val thread = threadFor(bookId, annotationId)
        val history = messages.forThread(thread.id)
            .filter { it.status == DiscussionMessage.STATUS_SUCCESS }
            .map { it.role to it.content }

        val context = buildContext(history)
        val askedAt = now()
        val userTurn = DiscussionMessage(
            id = UUID.randomUUID().toString(),
            threadId = thread.id,
            role = DiscussionMessage.ROLE_USER,
            content = question,
            status = DiscussionMessage.STATUS_SENDING,
            modelConfigId = config.id,
            modelIdSnapshot = config.modelId,
            contextSnapshot = context.snapshotJson(),
            createdAt = askedAt,
        )
        messages.upsert(userTurn)

        val reply = gateway.send(
            config = config,
            key = keys.keyFor(config.id),
            question = question,
            composedContext = context.compose(question),
            history = history,
        )

        val settled = reply.mapCatching { content ->
            withContext(NonCancellable) {
                messages.settle(
                    userTurn.id,
                    DiscussionMessage.STATUS_SUCCESS,
                    question,
                )
            }
            messages.upsert(
                DiscussionMessage(
                    id = UUID.randomUUID().toString(),
                    threadId = thread.id,
                    role = DiscussionMessage.ROLE_ASSISTANT,
                    content = content,
                    status = DiscussionMessage.STATUS_SUCCESS,
                    modelConfigId = config.id,
                    modelIdSnapshot = config.modelId,
                    contextSnapshot = context.snapshotJson(),
                    createdAt = now(),
                ),
            )
            threads.upsert(thread.copy(updatedAt = now()))
        }.recoverCatching { error ->
            // The question keeps its words and its failure, so a retry
            // is one tap and nothing typed is lost. NonCancellable: the
            // sheet may already be closed when a late failure lands.
            withContext(NonCancellable) {
                messages.settle(userTurn.id, DiscussionMessage.STATUS_FAILED, question)
            }
            throw error
        }
        return settled.map { }
    }

    /** Configs for the switcher, newest-chosen first by label. */
    fun observeConfigs(): Flow<List<ModelConfig>> = configs.observeAll()

    suspend fun saveConfig(
        label: String,
        baseUrl: String,
        modelId: String,
        key: String?,
        makeDefault: Boolean,
        configId: String? = null,
    ): ModelConfig {
        val id = configId ?: UUID.randomUUID().toString()
        val existing = configId?.let { configs.byId(it) }
        val config = ModelConfig(
            id = id,
            label = label.ifBlank { modelId },
            baseUrl = baseUrl,
            modelId = modelId,
            keyAlias = existing?.keyAlias ?: key?.let { "已保存" },
            isDefault = existing?.isDefault ?: makeDefault || configs.all().isEmpty(),
            createdAt = existing?.createdAt ?: now(),
        )
        configs.upsert(config)
        if (makeDefault) configs.setDefault(id)
        key?.takeIf { it.isNotBlank() }?.let { keys.put(id, it) }
        return config
    }

    suspend fun deleteConfig(id: String) {
        keys.clear(id)
        configs.deleteById(id)
    }

    suspend fun defaultConfig(): ModelConfig? = configs.defaultConfig()
}
