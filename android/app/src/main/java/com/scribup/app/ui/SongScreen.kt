package com.scribup.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribup.app.MainViewModel
import com.scribup.app.ui.theme.Dim

private val TABS = listOf("Écrire", "Live", "Claude", "Accords")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongScreen(vm: MainViewModel, onBack: () -> Unit, onSettings: () -> Unit) {
    val song by vm.current.collectAsStateWithLifecycle()
    val bridge by vm.bridge.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pendingPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    val current = song ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                actions = {
                    ConnectionBadge(bridge)
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Réglages") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                TABS.forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, maxLines = 1, softWrap = false) },
                        unselectedContentColor = Dim,
                    )
                }
            }
            when (tab) {
                0 -> EditorTab(vm, current)
                1 -> LiveTab(vm, current)
                2 -> AssistantTab(vm, current, initialPrompt = pendingPrompt, onPromptConsumed = { pendingPrompt = null })
                else -> ChordsTab(vm, current, onAskClaude = { prompt -> pendingPrompt = prompt; tab = 2 })
            }
        }
    }
}
