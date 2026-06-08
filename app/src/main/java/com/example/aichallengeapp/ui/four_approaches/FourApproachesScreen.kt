package com.example.aichallengeapp.ui.four_approaches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

data class ApproachOption(
    val title: String,
    val description: String,
)

private val APPROACH_OPTIONS = listOf(
    ApproachOption(
        title = "1. Прямой ответ",
        description = "Задача отправляется без дополнительных инструкций",
    ),
    ApproachOption(
        title = "2. Пошаговое решение",
        description = "К задаче добавлена инструкция «решай пошагово»",
    ),
    ApproachOption(
        title = "3. Мета-промпт",
        description = "Сначала модель составляет оптимальный промпт, затем решает по нему",
    ),
    ApproachOption(
        title = "4. Группа экспертов",
        description = "Аналитик, Логик и Критик дают своё решение",
    ),
)

@Composable
fun FourApproachesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FourApproachesViewModel = hiltViewModel(),
) {
    val task by viewModel.task.collectAsState()
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val result by viewModel.result.collectAsState()
    val isSolving = result is ResultUiState.Loading

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Четыре подхода к задаче",
            style = MaterialTheme.typography.headlineSmall,
        )

        OutlinedTextField(
            value = task,
            onValueChange = { viewModel.updateTask(it) },
            label = { Text("Задача") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            enabled = !isSolving,
        )

        ApproachDropdown(
            options = APPROACH_OPTIONS,
            selectedIndex = selectedIndex,
            onSelected = { viewModel.selectApproach(it) },
            enabled = !isSolving,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f),
            ) {
                Text("Назад")
            }
            OutlinedButton(
                onClick = { viewModel.reset() },
                enabled = !isSolving && result !is ResultUiState.Idle,
                modifier = Modifier.weight(1f),
            ) {
                Text("Сбросить")
            }
            Button(
                onClick = { viewModel.solve() },
                enabled = !isSolving && task.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isSolving) "Решаю..." else "Решить")
            }
        }

        ResultArea(result = result)
    }
}

@Composable
private fun ApproachDropdown(
    options: List<ApproachOption>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options[selectedIndex]

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { if (enabled) expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Text(selected.title)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = option.title,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ResultArea(result: ResultUiState) {
    when (result) {
        is ResultUiState.Idle -> Unit
        is ResultUiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is ResultUiState.Success -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Ответ:",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = result.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        is ResultUiState.Error -> {
            Text(
                text = "Ошибка: ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
