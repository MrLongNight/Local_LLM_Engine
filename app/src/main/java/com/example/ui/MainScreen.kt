package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mydocanizer.llm.engine.EngineDiagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: DocAnizerViewModel = viewModel()
) {
    val currentTab: AppTab by viewModel.currentTab.collectAsState()
    val statusMessage: String? by viewModel.statusMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val diagnosticsExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri -> if (uri != null) viewModel.exportDiagnostics(uri) }
    )

    LaunchedEffect(currentTab) {
        EngineDiagnostics.info("ui", "TAB_CONTENT_RENDER_REQUESTED", "tab=$currentTab")
    }

    LaunchedEffect(statusMessage) {
        statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissStatus()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = when (currentTab) {
                            AppTab.CHAT -> "Local LLM Engine"
                            AppTab.IMPORT -> "Modell-Import"
                            AppTab.SETTINGS -> "Einstellungen"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    if (currentTab == AppTab.CHAT) {
                        IconButton(
                            onClick = { viewModel.restartConversation() },
                            modifier = Modifier.testTag("top_app_bar_reset_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Gespräch neu starten"
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            EngineDiagnostics.info("ui", "DIAGNOSTICS_EXPORT_REQUESTED_FROM_APP_BAR")
                            diagnosticsExportLauncher.launch("local-llm-engine-diagnostics.log")
                        },
                        modifier = Modifier.testTag("top_app_bar_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Diagnoseprotokoll exportieren"
                        )
                    }
                    IconButton(
                        onClick = { viewModel.selectTab(AppTab.SETTINGS) },
                        modifier = Modifier.testTag("top_app_bar_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Einstellungen"
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors()
            )
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.testTag("bottom_nav")) {
                NavigationBarItem(
                    selected = currentTab == AppTab.CHAT,
                    onClick = { viewModel.selectTab(AppTab.CHAT) },
                    icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") },
                    label = { Text("Chat") },
                    modifier = Modifier.testTag("nav_chat")
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.IMPORT,
                    onClick = { viewModel.selectTab(AppTab.IMPORT) },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = "Modell-Import") },
                    label = { Text("Modell-Import") },
                    modifier = Modifier.testTag("nav_import")
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.SETTINGS,
                    onClick = { viewModel.selectTab(AppTab.SETTINGS) },
                    icon = { Icon(Icons.Default.Tune, contentDescription = "Einstellungen") },
                    label = { Text("Einstellungen") },
                    modifier = Modifier.testTag("nav_settings")
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                AppTab.CHAT -> ChatScreen(viewModel = viewModel)
                AppTab.IMPORT -> ModelImportScreen(viewModel = viewModel)
                AppTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
