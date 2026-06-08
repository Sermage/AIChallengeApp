package com.example.aichallengeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.aichallengeapp.ui.agent.AgentScreen
import com.example.aichallengeapp.ui.chat.ChatScreen
import com.example.aichallengeapp.ui.four_approaches.FourApproachesScreen
import com.example.aichallengeapp.ui.home.HomeScreen
import com.example.aichallengeapp.ui.theme.AIChallengeAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIChallengeAppTheme {
                var currentScreen by remember { mutableStateOf("home") }
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (currentScreen) {
                        "chat" -> ChatScreen(
                            onBack = { currentScreen = "home" },
                            modifier = Modifier.padding(innerPadding),
                        )
                        "fourApproaches" -> FourApproachesScreen(
                            onBack = { currentScreen = "home" },
                            modifier = Modifier.padding(innerPadding),
                        )
                        "agent" -> AgentScreen(
                            onBack = { currentScreen = "home" },
                            modifier = Modifier.padding(innerPadding),
                        )

                        else -> HomeScreen(
                            onNavigateToChat = { currentScreen = "chat" },
                            onNavigateToFourApproaches = { currentScreen = "fourApproaches" },
                            onNavigateToAgent = { currentScreen = "agent" },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }
}
