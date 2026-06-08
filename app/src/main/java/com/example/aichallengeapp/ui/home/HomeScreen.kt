package com.example.aichallengeapp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onNavigateToChat: () -> Unit,
    onNavigateToFourApproaches: () -> Unit,
    onNavigateToAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "AI Challenge App",
            style = MaterialTheme.typography.headlineMedium,
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onNavigateToChat,
            modifier = Modifier.fillMaxWidth(0.65f),
        ) {
            Text("Чат GigaChat")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNavigateToFourApproaches,
            modifier = Modifier.fillMaxWidth(0.65f),
        ) {
            Text("Четыре подхода")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onNavigateToAgent,
            modifier = Modifier.fillMaxWidth(0.65f),
        ) {
            Text("Агент с историей")
        }
    }
}
