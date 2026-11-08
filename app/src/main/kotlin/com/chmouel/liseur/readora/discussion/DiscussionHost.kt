package com.chmouel.liseur.readora.discussion

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.chmouel.liseur.container
import com.chmouel.liseur.data.db.BookAnnotation
import com.chmouel.liseur.reader.ActiveSelection
import com.chmouel.liseur.reader.ReaderAnnotationActions
import com.chmouel.liseur.reader.annotations.HighlightTint
import com.chmouel.liseur.reader.annotations.locator
import com.chmouel.liseur.ui.LiseurModalBottomSheet
import kotlinx.coroutines.launch

/**
 * The bridge between a selected passage and its discussion thread
 * (Readora S3).
 *
 * A passage the reader discusses but has not marked is marked first,
 * in the default colour, so the thread has an anchor that survives.
 * The sheet observes the turns through the anchor itself — the thread
 * row appears when the first ask founds it — so history shows the
 * moment it exists and never depends on a thread id the sheet never
 * held.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscussionHost(
    active: ActiveSelection,
    bookId: String,
    bookTitle: String,
    annotations: List<BookAnnotation>,
    onAnnotationAction: ReaderAnnotationActions,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { context.container.discussions }

    // The anchor this discussion hangs off: the tapped mark, or one
    // founded for it in the default colour.
    var anchorId by remember { mutableStateOf<String?>(active.existing?.id) }
    LaunchedEffect(active) {
        if (anchorId == null) {
            onAnnotationAction.highlight(active.locator, HighlightTint.DEFAULT, active.existing?.id)
        }
    }
    // The founded mark lands through the annotation flow; the merge
    // rule's own identity finds it: same file, same passage.
    LaunchedEffect(annotations, active) {
        if (anchorId == null && active.existing == null) {
            val passage = active.text.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
            val href = active.locator.href.toString()
            annotations
                .firstOrNull { mark ->
                    mark.kind != "BOOKMARK" && mark.kind != "BOOK_NOTE" &&
                        mark.text == passage &&
                        mark.locator()?.href?.toString() == href
                }
                ?.let { anchorId = it.id }
        }
    }

    val anchorNow = anchorId
    val messagesFlow = remember(anchorNow) {
        anchorNow?.let { repository.observeMessagesFor(it) }
            ?: kotlinx.coroutines.flow.flowOf(emptyList<DiscussionMessage>())
    }
    val messages by messagesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val configs by repository.observeConfigs().collectAsStateWithLifecycle(initialValue = emptyList())
    var activeConfigId by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    val chosenConfigId = activeConfigId ?: configs.firstOrNull { it.isDefault }?.id

    LiseurModalBottomSheet(onDismissRequest = onDismiss) {
        DiscussionSheet(
            passage = active.text,
            canSend = anchorNow != null,
            messages = messages,
            configs = configs,
            activeConfigId = chosenConfigId,
            sending = sending,
            lastError = lastError,
            onAsk = { question, configId ->
                val anchor = anchorId
                if (anchor != null) {
                    scope.launch {
                        sending = true
                        lastError = null
                        val result = repository.ask(
                            bookId = bookId,
                            annotationId = anchor,
                            question = question,
                            configId = configId,
                            buildContext = { history ->
                                ContextBuilder.build(
                                    bookTitle = bookTitle,
                                    chapterTitle = active.locator.title,
                                    selection = active.locator,
                                    // The walk of the book's own content is
                                    // not wired yet; the builder degrades to
                                    // the passage alone and says so in the
                                    // snapshot — plainly, not as a failure.
                                    paragraphs = emptyList(),
                                    history = history,
                                )
                            },
                        )
                        sending = false
                        lastError = result.exceptionOrNull()?.message
                    }
                }
            },
            onConfigChosen = { activeConfigId = it },
            onDismiss = onDismiss,
            onOpenSettings = onDismiss,
        )
    }
}
