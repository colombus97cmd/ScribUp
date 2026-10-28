package com.scribup.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scribup.app.MainViewModel
import com.scribup.app.data.Song
import com.scribup.app.lyrics.LyricsParser
import com.scribup.app.ui.theme.Dim
import com.scribup.app.ui.theme.Accent
import com.scribup.app.ui.theme.Accent2
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

private val SNIPPETS = listOf(
    "[Couplet]" to "\n[Couplet]\n",
    "[Refrain]" to "\n[Refrain]\n",
    "[Pont]" to "\n[Pont]\n",
    "( ad-lib )" to " ()",
    "@mesure" to "@",
    "~2 mesures" to " ~2",
)

@Composable
fun EditorTab(vm: MainViewModel, song: Song) {
    var field by remember(song.id) { mutableStateOf(TextFieldValue(song.lyrics)) }
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }

    // Texte modifié ailleurs (ex. « Ajouter au texte » depuis Claude)
    LaunchedEffect(song.lyrics) {
        if (song.lyrics != field.text) field = TextFieldValue(song.lyrics, TextRange(song.lyrics.length))
    }

    val parsed = remember(song.lyrics, song.barsPerLine) { LyricsParser.parse(song.lyrics, song.barsPerLine) }
    val cursorLine = field.text.take(field.selection.start).count { it == '\n' }
    val lineInfo = parsed.lines.lastOrNull { it.sourceLine <= cursorLine }?.takeIf { it.sourceLine == cursorLine }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { infoOpen = !infoOpen }) {
                Icon(if (infoOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                Text("Infos du morceau")
            }
            Text(
                listOfNotNull(
                    "${song.bpm} BPM",
                    song.key.takeIf { it.isNotBlank() },
                    "${parsed.lines.size} lignes",
                ).joinToString(" · "),
                color = Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { help = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "Syntaxe") }
        }

        if (infoOpen) SongInfo(vm, song)

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SNIPPETS.forEach { (label, snippet) ->
                AssistChip(onClick = {
                    val newText = field.text.replaceRange(field.selection.start, field.selection.end, snippet)
                    val caret = field.selection.start + snippet.length - if (snippet == " ()") 1 else 0
                    field = TextFieldValue(newText, TextRange(caret))
                    vm.update { it.copy(lyrics = newText) }
                }, label = { Text(label) })
            }
        }

        Text(
            lineInfo?.let { "Ligne : mesure ${it.bar} · ≈${it.syllables} syllabes" + (it.section?.let { s -> " · $s" } ?: "") }
                ?: " ",
            color = Dim, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        TextField(
            value = field,
            onValueChange = { v ->
                field = v
                if (v.text != song.lyrics) vm.update { it.copy(lyrics = v.text) }
            },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 24.sp),
            placeholder = { Text("Écris tes paroles…") },
            visualTransformation = LyricsHighlighter,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }

    if (help) {
        AlertDialog(
            onDismissRequest = { help = false },
            confirmButton = { TextButton(onClick = { help = false }) { Text("OK") } },
            title = { Text("Syntaxe ScribUp") },
            text = {
                Text(
                    """
                    [Refrain]  début de section
                    [Couplet 2 @25]  section qui commence mesure 25
                    @33 ma ligne  la ligne commence mesure 33
                    ma ligne ~2  la ligne dure 2 mesures (~0.5 = demi-mesure)
                    je monte (yeah)  entre parenthèses = ad-lib
                    (skrrt)  ligne d'ad-lib rattachée à la ligne d'avant
                    // note  commentaire ignoré

                    Par défaut chaque ligne dure ${formatBars(song.barsPerLine)} mesure(s) (réglable dans « Infos du morceau »).
                    """.trimIndent(),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
            },
        )
    }
}

@Composable
private fun SongInfo(vm: MainViewModel, song: Song) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = song.title, onValueChange = { v -> vm.update { it.copy(title = v) } },
            label = { Text("Titre") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var bpmText by remember(song.id) { mutableStateOf(song.bpm.toString()) }
            OutlinedTextField(
                value = bpmText,
                onValueChange = { v -> bpmText = v; v.toIntOrNull()?.takeIf { it in 30..300 }?.let { b -> vm.update { it.copy(bpm = b) } } },
                label = { Text("BPM") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = song.key, onValueChange = { v -> vm.update { it.copy(key = v) } },
                label = { Text("Tonalité") }, singleLine = true, modifier = Modifier.weight(1.3f),
                placeholder = { Text("ex. La mineur") },
            )
            var barsText by remember(song.id) { mutableStateOf(formatBars(song.barsPerLine)) }
            OutlinedTextField(
                value = barsText,
                onValueChange = { v ->
                    barsText = v
                    v.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 16 }?.let { b -> vm.update { it.copy(barsPerLine = b) } }
                },
                label = { Text("Mes./ligne") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
        OutlinedTextField(
            value = song.languages, onValueChange = { v -> vm.update { it.copy(languages = v) } },
            label = { Text("Langues (ex. Français, Anglais, Espagnol)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = song.notes, onValueChange = { v -> vm.update { it.copy(notes = v) } },
            label = { Text("Thème, ambiance, références (pour Claude)") }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
        )
    }
}

fun formatBars(bars: Double): String = if (bars % 1.0 == 0.0) bars.toInt().toString() else bars.toString()

/** Colore la syntaxe ScribUp sans modifier le texte : sections, ad-libs, @mesure, ~durée, commentaires. */
private object LyricsHighlighter : VisualTransformation {
    private val section = SpanStyle(color = Accent2, fontWeight = FontWeight.Bold)
    private val adlib = SpanStyle(color = Accent, fontStyle = FontStyle.Italic)
    private val marker = SpanStyle(color = Accent2)
    private val comment = SpanStyle(color = Dim, fontStyle = FontStyle.Italic)
    private val markerRegex = Regex("""^@\s*\d+(?:[.,]\d+)?|\s~\s*\d+(?:[.,]\d+)?$""")

    override fun filter(text: AnnotatedString): TransformedText {
        val styled = buildAnnotatedString {
            append(text.text)
            var offset = 0
            for (line in text.text.split('\n')) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("[") && trimmed.endsWith("]") -> addStyle(section, offset, offset + line.length)
                    trimmed.startsWith("//") -> addStyle(comment, offset, offset + line.length)
                    else -> {
                        var depth = 0
                        var start = 0
                        line.forEachIndexed { i, c ->
                            if (c == '(') { if (depth == 0) start = i; depth++ }
                            if (c == ')' && depth > 0) { depth--; if (depth == 0) addStyle(adlib, offset + start, offset + i + 1) }
                        }
                        if (depth > 0) addStyle(adlib, offset + start, offset + line.length)
                        markerRegex.findAll(line).forEach { addStyle(marker, offset + it.range.first, offset + it.range.last + 1) }
                    }
                }
                offset += line.length + 1
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
