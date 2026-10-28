package com.scribup.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribup.app.MainViewModel
import com.scribup.app.ai.Prompts
import com.scribup.app.data.Song
import com.scribup.app.lyrics.LyricsParser
import com.scribup.app.ui.theme.Accent
import com.scribup.app.ui.theme.Bad
import com.scribup.app.ui.theme.Dim
import com.scribup.app.ui.theme.Surface1
import com.scribup.app.ui.theme.Surface2

@Composable
fun AssistantTab(vm: MainViewModel, song: Song, initialPrompt: String?, onPromptConsumed: () -> Unit) {
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val error by vm.assistantError.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bridge by vm.bridge.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val busy = streaming != null

    LaunchedEffect(initialPrompt) {
        if (initialPrompt != null) {
            vm.ask(initialPrompt)
            onPromptConsumed()
        }
    }
    val count = song.chat.size + if (busy) 1 else 0
    LaunchedEffect(count, streaming?.length) {
        if (count > 0) listState.scrollToItem(count - 1)
    }

    val quickContext = remember(song.lyrics, song.languages, song.bpm, song.key, bridge.audio.key) {
        val lastLine = LyricsParser.parse(song.lyrics, song.barsPerLine).lines.lastOrNull()
            ?.segments?.filterNot { it.adlib }?.joinToString("") { it.text }?.trim().orEmpty()
        Prompts.QuickContext(lastLine, song.languages, song.bpm, song.key.ifBlank { bridge.audio.key.orEmpty() })
    }

    Column(Modifier.fillMaxSize()) {
        if (settings.apiKey.isBlank()) {
            Text(
                "Ajoute ta clé API Claude dans les réglages (⚙) pour utiliser l'assistant.",
                color = Bad, modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (song.chat.isEmpty() && !busy) {
                item {
                    Text(
                        "Demande ce que tu veux : rimes, traduction, ad-libs, flow, accords, topline, arrangement…\n" +
                            "Claude voit tes paroles, le BPM, la tonalité et la grille d'accords captée dans FL.",
                        color = Dim,
                    )
                }
            }
            items(song.chat) { msg ->
                ChatBubble(
                    text = msg.text,
                    fromUser = msg.role == "user",
                    onCopy = { clipboard.setText(AnnotatedString(msg.text)) },
                    onInsert = if (msg.role == "assistant") ({ vm.appendToLyrics(msg.text) }) else null,
                )
            }
            if (busy) {
                item { ChatBubble(text = streaming.orEmpty().ifEmpty { "…" }, fromUser = false, onCopy = null, onInsert = null) }
            }
        }

        error?.let { Text(it, color = Bad, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Prompts.QUICK_ACTIONS.forEach { action ->
                AssistChip(onClick = { vm.ask(action.prompt(quickContext)) }, label = { Text(action.label) }, enabled = !busy)
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { confirmClear = true }, enabled = song.chat.isNotEmpty() && !busy) {
                Icon(Icons.Default.DeleteSweep, "Effacer la conversation", tint = Dim)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Demande à Claude…") },
                maxLines = 5,
            )
            if (busy) {
                IconButton(onClick = vm::cancelAsk) { Icon(Icons.Default.Stop, "Arrêter") }
            } else {
                IconButton(onClick = { vm.ask(input); input = "" }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Envoyer", tint = if (input.isNotBlank()) Accent else Dim)
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer la conversation ?") },
            text = { Text("Les paroles ne sont pas touchées.") },
            confirmButton = { TextButton(onClick = { vm.clearChat(); confirmClear = false }) { Text("Effacer") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ChatBubble(text: String, fromUser: Boolean, onCopy: (() -> Unit)?, onInsert: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .background(if (fromUser) Accent.copy(alpha = 0.18f) else Surface1, RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            SelectionContainer {
                Text(if (fromUser) AnnotatedString(text) else simpleMarkdown(text), style = MaterialTheme.typography.bodyMedium)
            }
            if (onCopy != null || onInsert != null) {
                Row(Modifier.background(Surface2.copy(alpha = 0f)), horizontalArrangement = Arrangement.End) {
                    onCopy?.let { IconButton(onClick = it) { Icon(Icons.Default.ContentCopy, "Copier", tint = Dim) } }
                    onInsert?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Ajouter au texte", tint = Dim) } }
                }
            }
        }
    }
}
