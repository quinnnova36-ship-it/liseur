package com.chmouel.liseur.readora.discussion

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chmouel.liseur.container
import kotlinx.coroutines.launch

/**
 * Where the reader names the models they may ask (Readora S3).
 *
 * The API key is written once, sealed into the app's private storage by
 * the Keystore cipher, and never shown or exported again; this screen
 * only ever echoes the label, address and model id.
 */
@Composable
fun ModelConfigScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { context.container.discussions }
    val configs by repository.observeConfigs().collectAsStateWithLifecycle(initialValue = emptyList())

    var label by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Text("模型配置", style = MaterialTheme.typography.titleLarge)
        Text(
            "密钥只保存在本机加密存储，不会出现在导出或日志中。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        OutlinedTextField(
            value = label, onValueChange = { label = it },
            label = { Text("名称（如：我的 GPT）") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("Base URL（https://…，裸主机名默认 https 加 /v1）") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = modelId, onValueChange = { modelId = it },
            label = { Text("模型 ID（如 gpt-4o-mini）") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("API Key（可选，保存在本机）") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            Button(
                onClick = {
                    if (baseUrl.isBlank() || modelId.isBlank()) {
                        error = "Base URL 和模型 ID 不能为空"
                        return@Button
                    }
                    error = null
                    scope.launch {
                        repository.saveConfig(
                            label = label,
                            baseUrl = baseUrl,
                            modelId = modelId,
                            key = apiKey.takeIf { it.isNotBlank() },
                            makeDefault = true,
                        )
                        apiKey = ""
                        label = ""
                        baseUrl = ""
                        modelId = ""
                    }
                },
            ) {
                Text("保存")
            }
            TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) {
                Text("返回")
            }
        }

        Text("已保存的配置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn {
            items(configs, key = { c -> c.id }) { config ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        RadioButton(
                            selected = config.isDefault,
                            onClick = { scope.launch { repository.saveConfig(
                                label = config.label, baseUrl = config.baseUrl,
                                modelId = config.modelId, key = null,
                                makeDefault = true, configId = config.id,
                            ) } },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(config.label, style = MaterialTheme.typography.titleSmall)
                            Text("${config.baseUrl} · ${config.modelId}", style = MaterialTheme.typography.bodySmall)
                            config.keyAlias?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                        TextButton(onClick = { scope.launch { repository.deleteConfig(config.id) } }) {
                            Text("删除")
                        }
                    }
                }
            }
        }
    }
}
