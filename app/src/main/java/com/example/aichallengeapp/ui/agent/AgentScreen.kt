package com.example.aichallengeapp.ui.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.aichallengeapp.data.AgentStep
import com.example.aichallengeapp.data.StepType

private const val CONTEXT_WINDOW_LIMIT = 32768

@Composable
fun AgentScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedIndex by viewModel.selectedAgentIndex.collectAsState()
    val savedSessions by viewModel.savedSessions.collectAsState()
    val maxTokens by viewModel.maxTokens.collectAsState()
    val pendingAttachments by viewModel.pendingAttachments.collectAsState()
    val isUploading by viewModel.isUploading.collectAsState()
    var inputText by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.addAttachment(it) } }

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.addAttachment(it) } }

    // Накопленные токены из последнего ответа агента
    val totalDialogTokens = messages.lastOrNull { !it.isUser }?.historyTokens ?: 0

    LaunchedEffect(messages.size, isLoading) {
        val targetIndex = messages.size + if (isLoading) 1 else 0
        if (targetIndex > 0) listState.animateScrollToItem(targetIndex - 1)
    }

    if (showHistory) {
        HistoryDialog(
            sessions = savedSessions,
            onDismiss = { showHistory = false },
            onLoadSession = { session ->
                viewModel.loadSession(session)
                showHistory = false
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        AgentHeader(
            agentNames = viewModel.agentNames,
            selectedIndex = selectedIndex,
            onAgentSelected = { viewModel.selectAgent(it) },
            onBack = onBack,
            onClear = { viewModel.clearHistory() },
            onShowHistory = { showHistory = true },
            hasMessages = messages.isNotEmpty(),
            isLoading = isLoading,
        )

        HorizontalDivider()

        // Прогресс-бар контекстного окна
        if (totalDialogTokens > 0) {
            ContextWindowBar(totalTokens = totalDialogTokens)
            HorizontalDivider()
        }

        // Выбор max_tokens
        MaxTokensSelector(
            maxTokens = maxTokens,
            onMaxTokensChanged = { viewModel.setMaxTokens(it) },
        )

        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            itemsIndexed(messages, key = { _, it -> it.id }) { _, message ->
                MessageBubble(message = message)
            }
            if (isLoading) {
                item {
                    ThinkingIndicator(agentName = viewModel.agentNames[selectedIndex])
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }

        HorizontalDivider()

        // Чипы ожидающих вложений
        if (pendingAttachments.isNotEmpty() || isUploading) {
            AttachmentChips(
                attachments = pendingAttachments,
                isUploading = isUploading,
                onRemove = { viewModel.removeAttachment(it) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Кнопка-скрепка с выпадающим меню
            var showAttachMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { showAttachMenu = true },
                    enabled = !isLoading && !isUploading,
                    modifier = Modifier.size(48.dp),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("📎", style = MaterialTheme.typography.titleMedium)
                }
                DropdownMenu(
                    expanded = showAttachMenu,
                    onDismissRequest = { showAttachMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Фото / изображение") },
                        onClick = {
                            imagePicker.launch("image/*")
                            showAttachMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("PDF-документ") },
                        onClick = {
                            pdfPicker.launch("application/pdf")
                            showAttachMenu = false
                        },
                    )
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("Сообщение агенту…") },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 4,
                enabled = !isLoading,
            )
            Button(
                onClick = {
                    viewModel.send(inputText)
                    inputText = ""
                },
                enabled = !isLoading && inputText.isNotBlank(),
            ) {
                Text("Отправить")
            }
        }
    }
}

// ── Заголовок ─────────────────────────────────────────────────────────────────

@Composable
private fun AgentHeader(
    agentNames: List<String>,
    selectedIndex: Int,
    onAgentSelected: (Int) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onShowHistory: () -> Unit,
    hasMessages: Boolean,
    isLoading: Boolean,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onBack) { Text("←") }

        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { if (!isLoading) dropdownExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
            ) {
                Text("Агент: ${agentNames[selectedIndex]}")
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
            ) {
                agentNames.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onAgentSelected(index)
                            dropdownExpanded = false
                        },
                    )
                }
            }
        }

        OutlinedButton(onClick = onShowHistory, enabled = !isLoading) {
            Text("История")
        }

        if (hasMessages) {
            OutlinedButton(onClick = onClear, enabled = !isLoading) {
                Text("Очистить")
            }
        }
    }
}

// ── Прогресс-бар контекстного окна ────────────────────────────────────────────

@Composable
private fun ContextWindowBar(
    totalTokens: Int,
    contextLimit: Int = CONTEXT_WINDOW_LIMIT,
) {
    val fraction = (totalTokens.toFloat() / contextLimit).coerceIn(0f, 1f)
    val percent = (fraction * 100).toInt()
    val barColor = when {
        fraction > 0.8f -> MaterialTheme.colorScheme.error
        fraction > 0.5f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Контекст диалога: $totalTokens / $contextLimit tok",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = barColor,
            )
        }
        Spacer(Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = barColor,
        )
    }
}

// ── Выбор max_tokens ───────────────────────────────────────────────────────────

@Composable
private fun MaxTokensSelector(
    maxTokens: Int?,
    onMaxTokensChanged: (Int?) -> Unit,
) {
    val options: List<Pair<Int?, String>> = listOf(
        50 to "50",
        150 to "150",
        500 to "500",
        null to "∞",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "max_tokens:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 2.dp),
        )
        options.forEach { (value, label) ->
            val selected = maxTokens == value
            if (selected) {
                Button(
                    onClick = { onMaxTokensChanged(value) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            } else {
                OutlinedButton(
                    onClick = { onMaxTokensChanged(value) },
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

// ── Диалог истории ─────────────────────────────────────────────────────────────

@Composable
private fun HistoryDialog(
    sessions: List<ChatSession>,
    onDismiss: () -> Unit,
    onLoadSession: (ChatSession) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Text(
                    text = "История сессий",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(8.dp))

                if (sessions.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Нет сохранённых сессий",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(sessions, key = { _, it -> it.id }) { _, session ->
                            SessionItem(
                                session = session,
                                onClick = { onLoadSession(session) },
                            )
                            HorizontalDivider()
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Закрыть")
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.agentName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${session.messageCount} сообщ.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = session.displayDate,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (session.preview.isNotBlank()) {
            Text(
                text = session.preview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Чипы ожидающих вложений (над полем ввода) ─────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentChips(
    attachments: List<PendingAttachment>,
    isUploading: Boolean,
    onRemove: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { att ->
            InputChip(
                selected = false,
                onClick = { onRemove(att.fileId) },
                label = {
                    Text(
                        text = att.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = { Text("✕", style = MaterialTheme.typography.labelSmall) },
            )
        }
        if (isUploading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

// ── Пузырь сообщения ───────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
    ) {
        // Чипы вложений (только для сообщений с файлами)
        if (message.attachmentNames.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                message.attachmentNames.forEach { name ->
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (message.isUser)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isUser) 16.dp else 4.dp,
                        bottomEnd = if (message.isUser) 4.dp else 16.dp,
                    ),
                )
                .padding(12.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (!message.isUser && message.steps.any { it.type == StepType.THINK }) {
            val thinkStep = message.steps.first { it.type == StepType.THINK }
            ThinkingCard(step = thinkStep)
        }

        // Ответ без контекста для сравнения
        if (!message.isUser && message.noContextAnswer != null) {
            NoContextCard(
                answer = message.noContextAnswer,
                tokens = message.noContextTokens,
            )
        }

        if (!message.isUser && message.totalTokens > 0) {
            Column(
                modifier = Modifier.padding(top = 2.dp, start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = "Запрос: ${message.requestTokens} tok  |  Ответ: ${message.completionTokens} tok",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "История диалога: ${message.historyTokens} tok  |  Итого: ${message.totalTokens} tok",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${message.elapsedMs} мс",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Карточки внутри пузыря ─────────────────────────────────────────────────────

@Composable
private fun ThinkingCard(step: AgentStep) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .padding(top = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (expanded) "Скрыть рассуждение ▲" else "Показать рассуждение ▼",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = step.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun NoContextCard(answer: String, tokens: Int) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .widthIn(max = 300.dp)
            .padding(top = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (expanded) "Без контекста ▲" else "Без контекста ▼",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    Text(
                        text = answer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$tokens tok",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

// ── Индикатор загрузки ─────────────────────────────────────────────────────────

@Composable
private fun ThinkingIndicator(agentName: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = "$agentName думает…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
