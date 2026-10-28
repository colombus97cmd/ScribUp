package com.scribup.app.ui

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribup.app.MainViewModel
import com.scribup.app.data.Song
import com.scribup.app.lyrics.LyricsParser
import com.scribup.app.lyrics.MusicTheory
import com.scribup.app.ui.theme.Accent
import com.scribup.app.ui.theme.Accent2
import com.scribup.app.ui.theme.Bad
import com.scribup.app.ui.theme.Dim
import com.scribup.app.ui.theme.Surface1
import com.scribup.app.ui.theme.Surface2
import kotlin.math.floor
import kotlin.math.max

/** Horloge du téléphone, utilisée quand FL Studio n'est pas connecté (répétition, métronome visuel). */
@Stable
class InternalClock {
    var playing by mutableStateOf(false)
        private set
    private var anchorPos by mutableDoubleStateOf(1.0)
    private var anchorTime = 0L

    fun position(bpm: Int, beatsPerBar: Int = 4, now: Long = SystemClock.elapsedRealtime()): Double =
        if (playing) anchorPos + (now - anchorTime) / 1000.0 * bpm / 60.0 / beatsPerBar else anchorPos

    fun toggle(bpm: Int) {
        if (playing) {
            anchorPos = position(bpm); playing = false
        } else {
            anchorTime = SystemClock.elapsedRealtime(); playing = true
        }
    }

    fun seek(pos: Double, play: Boolean = true) {
        anchorPos = pos; anchorTime = SystemClock.elapsedRealtime(); playing = play
    }

    fun stop() {
        playing = false; anchorPos = 1.0
    }
}

private sealed interface LiveItem {
    data class Header(val name: String) : LiveItem
    data class LineItem(val line: LyricsParser.Line) : LiveItem
}

@Composable
fun LiveTab(vm: MainViewModel, song: Song) {
    val bridge by vm.bridge.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val parsed = remember(song.lyrics, song.barsPerLine) { LyricsParser.parse(song.lyrics, song.barsPerLine) }
    val clock = remember { InternalClock() }
    val live = bridge.live
    val grid = bridge.grid.ifEmpty { song.chordGrid }
    val latin = settings.latinNotes

    // Garde l'écran allumé pendant qu'on pose
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val playing = if (live) bridge.fl.playing else clock.playing
    // Redessine à chaque frame seulement pendant la lecture (position extrapolée avec le tempo)
    val frame by produceState(0L, playing) {
        if (playing) while (true) withFrameMillis { value = it }
    }
    val pos = frame.let { if (live) bridge.positionAt() else clock.position(song.bpm) }
    val beatsPerBar = if (live) bridge.fl.beatsPerBar else 4
    val bar = floor(pos).toInt()
    val beat = (floor((pos - bar) * beatsPerBar).toInt() + 1).coerceIn(1, beatsPerBar)
    val currentLine = parsed.lineAt(pos)
    val currentSection = parsed.sectionAt(pos)

    val items = remember(parsed) {
        buildList {
            parsed.lines.forEach { line ->
                parsed.sections.firstOrNull { it.firstLine == line.index }?.let { add(LiveItem.Header(it.name)) }
                add(LiveItem.LineItem(line))
            }
        }
    }
    val itemOfLine = remember(items) {
        items.mapIndexedNotNull { i, item -> (item as? LiveItem.LineItem)?.let { it.line.index to i } }.toMap()
    }

    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(currentLine, follow) {
        val target = itemOfLine[currentLine] ?: return@LaunchedEffect
        if (follow) listState.animateScrollToItem(max(0, target - 1))
    }

    fun jumpTo(targetBar: Int) {
        val t = max(1, targetBar - settings.preRollBars)
        if (live) {
            vm.bridge.command("goto", t)
            if (!bridge.fl.playing) vm.bridge.command("play")
        } else {
            clock.seek(t.toDouble())
        }
    }

    Column(Modifier.fillMaxSize()) {
        // --- En-tête : position, tempo, accords ------------------------------------
        Column(
            Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(16.dp)).background(Surface1).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "M$bar · $beat",
                        fontSize = 34.sp, fontWeight = FontWeight.Black,
                        color = if (playing) Color.White else Dim,
                    )
                    Spacer(Modifier.height(4.dp))
                    BeatDots(beat, beatsPerBar, playing)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append(if (live) "${"%.1f".format(bridge.fl.bpm)} BPM · FL" else "${song.bpm} BPM · horloge du tel")
                            if (live && bridge.fl.recording) append(" · REC")
                            currentSection?.let { append(" · ${it.name}") }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (live && bridge.fl.recording) Bad else Dim,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val nowChord = if (bridge.audio.active) bridge.audio.chord else grid[bar]
                    Text(
                        MusicTheory.displayChord(nowChord, latin),
                        fontSize = 34.sp, fontWeight = FontWeight.Black, color = Accent2,
                    )
                    grid[bar + 1]?.let {
                        Text("ensuite ${MusicTheory.displayChord(it, latin)}", color = Dim, style = MaterialTheme.typography.labelMedium)
                    }
                    val key = bridge.audio.key?.let { MusicTheory.parseKey(it)?.name(latin) } ?: song.key.takeIf { it.isNotBlank() }
                    key?.let { Text(it, color = Dim, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }

        // --- Transport --------------------------------------------------------------
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledIconButton(
                onClick = { if (live) vm.bridge.command("toggle") else clock.toggle(song.bpm) },
                modifier = Modifier.width(64.dp).height(48.dp),
            ) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Lecture / pause") }
            IconButton(onClick = { if (live) vm.bridge.command("stop") else clock.stop() }) {
                Icon(Icons.Default.Stop, "Stop")
            }
            if (live) {
                IconButton(
                    onClick = { vm.bridge.command("record") },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = if (bridge.fl.recording) Bad else Dim),
                ) { Icon(Icons.Default.FiberManualRecord, "Enregistrement") }
            }
            IconButton(onClick = { currentSection?.let { jumpTo(floor(it.start).toInt()) } }) {
                Icon(Icons.Default.Replay, "Reprendre la section")
            }
            Spacer(Modifier.weight(1f))
            FilterChip(selected = follow, onClick = { follow = !follow }, label = { Text("Suivi") })
        }

        if (parsed.sections.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                parsed.sections.forEach { section ->
                    FilterChip(
                        selected = currentSection == section,
                        onClick = { jumpTo(floor(section.start).toInt()) },
                        label = { Text("${section.name} · M${floor(section.start).toInt()}") },
                    )
                }
            }
        }

        // --- Paroles ----------------------------------------------------------------
        if (parsed.lines.isEmpty()) {
            Text(
                "Écris tes paroles dans l'onglet « Écrire » : elles défileront ici au rythme de FL Studio.",
                color = Dim, modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 240.dp),
        ) {
            itemsIndexed(items) { _, item ->
                when (item) {
                    is LiveItem.Header -> SectionTitle(item.name, Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp))
                    is LiveItem.LineItem -> LiveLine(
                        line = item.line,
                        state = when {
                            item.line.index == currentLine -> LineState.CURRENT
                            item.line.index < currentLine -> LineState.PAST
                            else -> LineState.NEXT
                        },
                        progress = if (item.line.index == currentLine) ((pos - item.line.start) / item.line.bars).toFloat() else 0f,
                        chords = (item.line.bar until max(item.line.bar + 1, (item.line.end - 1e-6).toInt() + 1))
                            .mapNotNull { grid[it] }.distinct().joinToString("  ") { MusicTheory.displayChord(it, latin) },
                        fontSize = settings.liveFontSize,
                        onClick = { jumpTo(item.line.bar) },
                    )
                }
            }
        }
    }
}

private enum class LineState { PAST, CURRENT, NEXT }

@Composable
private fun LiveLine(
    line: LyricsParser.Line,
    state: LineState,
    progress: Float,
    chords: String,
    fontSize: Int,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(if (state == LineState.CURRENT) Surface2 else Color.Transparent, label = "bg")
    val textColor = when (state) {
        LineState.CURRENT -> Color.White
        LineState.NEXT -> Color.White.copy(alpha = 0.75f)
        LineState.PAST -> Color.White.copy(alpha = 0.3f)
    }
    val size = if (state == LineState.CURRENT) fontSize else (fontSize * 0.78f).toInt()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column(Modifier.width(56.dp)) {
            Text("M${line.bar}", color = Dim, style = MaterialTheme.typography.labelMedium)
            if (chords.isNotEmpty()) Text(chords, color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f)) {
            Text(
                lyricLine(line.segments, adlibColor = Accent.copy(alpha = if (state == LineState.PAST) 0.35f else 1f)),
                color = textColor,
                fontSize = size.sp,
                lineHeight = (size * 1.25f).sp,
                fontWeight = if (state == LineState.CURRENT) FontWeight.Bold else FontWeight.Normal,
            )
            line.adlibLines.forEach {
                Text(
                    it, color = Accent.copy(alpha = if (state == LineState.PAST) 0.35f else 0.9f),
                    fontSize = (size * 0.8f).sp, fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            if (state == LineState.CURRENT) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Accent,
                    trackColor = Color.White.copy(alpha = 0.1f),
                    drawStopIndicator = {},
                )
            }
        }
        Text("≈${line.syllables}", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
    }
}
