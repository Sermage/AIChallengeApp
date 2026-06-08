package com.example.aichallengeapp.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.aichallengeapp.data.AgentStep
import com.example.aichallengeapp.data.StepType

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
    var inputText by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

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

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(messages, key = { it.id }) { message ->
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                        items(sessions, key = { it.id }) { session ->
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

@Composable
private fun MessageBubble(message: ChatMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
    ) {
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

        if (!message.isUser && message.totalTokens > 0) {
            Text(
                text = "${message.totalTokens} токенов  |  ${message.elapsedMs} мс",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp),
            )
        }
    }
}

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
