package com.scribup.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scribup.app.bridge.BridgeState
import com.scribup.app.bridge.LinkStatus
import com.scribup.app.lyrics.LyricsParser
import com.scribup.app.ui.theme.Accent
import com.scribup.app.ui.theme.Bad
import com.scribup.app.ui.theme.Dim
import com.scribup.app.ui.theme.Good
import com.scribup.app.ui.theme.Warn

@Composable
fun ConnectionBadge(state: BridgeState, modifier: Modifier = Modifier) {
    val (color, label) = when {
        state.live -> Good to "FL"
        state.status == LinkStatus.CONNECTED -> Warn to "PC"
        state.status == LinkStatus.CONNECTING -> Warn to "…"
        state.status == LinkStatus.ERROR -> Bad to "PC"
        else -> Dim to "hors ligne"
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun BeatDots(beat: Int, beatsPerBar: Int, playing: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 1..beatsPerBar.coerceIn(1, 12)) {
            val on = playing && i == beat
            Box(
                Modifier
                    .size(if (i == 1) 14.dp else 11.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            on && i == 1 -> Accent
                            on -> Color.White
                            else -> Color.White.copy(alpha = 0.15f)
                        }
                    )
            )
        }
    }
}

/** Texte avec les ad-libs (entre parenthèses) colorés. */
fun lyricLine(segments: List<LyricsParser.Segment>, adlibColor: Color = Accent): AnnotatedString =
    buildAnnotatedString {
        for (segment in segments) {
            if (segment.adlib) {
                withStyle(SpanStyle(color = adlibColor, fontStyle = FontStyle.Italic)) { append(segment.text) }
            } else {
                append(segment.text)
            }
        }
    }

/** Rendu minimal du Markdown renvoyé par Claude (gras, titres, listes). */
fun simpleMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, rawLine ->
        if (i > 0) append("\n")
        var line = rawLine
        val heading = line.trimStart().startsWith("#")
        if (heading) line = line.trimStart().trimStart('#').trimStart()
        if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
            line = line.replaceFirst(Regex("""^(\s*)[-*] """), "$1• ")
        }
        if (heading) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        val parts = line.split("**")
        parts.forEachIndexed { idx, part ->
            if (idx % 2 == 1 && idx < parts.size - 1) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) }
            } else {
                if (idx % 2 == 1) append("**")
                append(part)
            }
        }
        if (heading) pop()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = Dim,
        letterSpacing = 1.2.sp,
    )
}
