package com.scribup.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribup.app.MainViewModel
import com.scribup.app.bridge.LinkStatus
import com.scribup.app.data.MODELS
import com.scribup.app.ui.theme.Dim

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bridge by vm.bridge.state.collectAsStateWithLifecycle()
    val found by vm.discovery.found.collectAsStateWithLifecycle()
    val searching by vm.discovery.searching.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        vm.discovery.start()
        onDispose { vm.discovery.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard("Claude") {
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { v -> vm.updateSettings { it.copy(apiKey = v) } },
                    label = { Text("Clé API (sk-ant-…)") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Crée une clé sur console.anthropic.com. Elle reste sur ce téléphone, dans les données privées de l'app.",
                    style = MaterialTheme.typography.bodySmall, color = Dim,
                )
                Spacer(Modifier.height(8.dp))
                MODELS.forEach { model ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.updateSettings { it.copy(model = model.id) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.model == model.id, onClick = { vm.updateSettings { it.copy(model = model.id) } })
                        Text(model.label)
                    }
                }
                OutlinedTextField(
                    value = settings.defaultLanguages,
                    onValueChange = { v -> vm.updateSettings { it.copy(defaultLanguages = v) } },
                    label = { Text("Langues par défaut des nouveaux morceaux") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsCard("PC / FL Studio") {
                Text(
                    "Lance pc-bridge/scribup_bridge.py sur le PC : il affiche son IP et le PIN. Téléphone et PC doivent être sur le même Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall, color = Dim,
                )
                if (found.isNotEmpty() || searching) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searching) CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (found.isEmpty()) "Recherche du PC sur le Wi-Fi…" else "PC trouvés :", color = Dim)
                    }
                    found.forEach { pc ->
                        OutlinedButton(onClick = { vm.useFoundBridge(pc) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${pc.name} — ${pc.host}:${pc.port}")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = settings.bridgeHost,
                        onValueChange = { v -> vm.updateSettings { it.copy(bridgeHost = v) } },
                        label = { Text("IP du PC") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = settings.bridgePort.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { p -> vm.updateSettings { it.copy(bridgePort = p) } } },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = settings.bridgePin,
                    onValueChange = { v -> vm.updateSettings { it.copy(bridgePin = v) } },
                    label = { Text("PIN affiché par le pont") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (bridge.status == LinkStatus.OFF || bridge.status == LinkStatus.ERROR) {
                        Button(onClick = vm::connectBridge) { Text("Connecter") }
                    } else {
                        OutlinedButton(onClick = vm::disconnectBridge) { Text("Déconnecter") }
                    }
                    ConnectionBadge(bridge)
                }
                if (bridge.message.isNotBlank()) {
                    val detail = when {
                        bridge.live -> "Connecté à ${bridge.pcName} · FL Studio détecté"
                        bridge.status == LinkStatus.CONNECTED -> "Connecté à ${bridge.pcName} · FL Studio pas encore détecté (script MIDI ?)"
                        else -> bridge.message
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Dim)
                }
            }

            SettingsCard("Live") {
                LabeledSwitch("Notes en Do Ré Mi", settings.latinNotes) { v -> vm.updateSettings { it.copy(latinNotes = v) } }
                Text("Pré-roll quand je touche une ligne : ${settings.preRollBars} mesure(s)")
                Slider(
                    value = settings.preRollBars.toFloat(),
                    onValueChange = { v -> vm.updateSettings { it.copy(preRollBars = v.toInt()) } },
                    valueRange = 0f..4f, steps = 3,
                )
                Text("Taille du texte en live : ${settings.liveFontSize}")
                Slider(
                    value = settings.liveFontSize.toFloat(),
                    onValueChange = { v -> vm.updateSettings { it.copy(liveFontSize = v.toInt()) } },
                    valueRange = 18f..44f,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
