package com.chmouel.liseur.readora.discussion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The discussion sheet over the page (Readora S3).
 *
 * Two expansions, settled by drag or tap: half (the passage and the
 * turns) and full (the turns and the keyboard). The reader's book
 * stays underneath; nothing here moves the page.
 */
@Composable
fun DiscussionSheet(
    passage: String,
    canSend: Boolean,
    messages: List<DiscussionMessage>,
    configs: List<ModelConfig>,
    activeConfigId: String?,
    sending: Boolean,
    lastError: String?,
    onAsk: (String, String?) -> Unit,
    onConfigChosen: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var listAtBottom by remember { mutableStateOf(true) }
    val list = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (listAtBottom && messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding()) {
        // The passage this thread hangs off, always in view.
        Card(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text(
                passage,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            items(messages, key = { it.id }) { turn ->
                Turn(turn)
            }
        }

        lastError?.let { error ->
            Text(
                error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        // Model switcher: chips, the active one filled.
        if (configs.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                configs.take(4).forEach { config ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (config.id == activeConfigId) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        onClick = { onConfigChosen(config.id) },
                    ) {
                        Text(
                            config.label,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        } else {
            TextButton(onClick = onOpenSettings) {
                Text("先配置一个模型")
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text("问点什么…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = !sending,
            )
            TextButton(
                onClick = {
                    val text = question.trim()
                    if (text.isNotEmpty()) {
                        onAsk(text, activeConfigId)
                        question = ""
                    }
                },
                enabled = !sending && canSend && question.isNotBlank(),
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.padding(4.dp).align(Alignment.CenterVertically))
                } else {
                    Text("发送")
                }
            }
        }
    }
}

@Composable
private fun Turn(turn: DiscussionMessage) {
    val mine = turn.role == DiscussionMessage.ROLE_USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (mine) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth(if (mine) 0.85f else 0.95f)
            .padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(turn.content, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (turn.status == DiscussionMessage.STATUS_SENDING) {
                    Text("发送中…", style = MaterialTheme.typography.labelSmall)
                } else if (turn.status == DiscussionMessage.STATUS_FAILED) {
                    Text(
                        "未发出 · 重试时重新发送",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                turn.modelIdSnapshot?.let { model ->
                    Text(model, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    }
}
