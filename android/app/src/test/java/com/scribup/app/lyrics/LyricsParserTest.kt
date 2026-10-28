package com.scribup.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsParserTest {

    private val song = """
        [Intro]
        (yeah, yeah)
        // une note pour moi
        [Couplet 1 @5]
        Je pose mes mots sur la prod (skrrt)
        Chaque mesure est un combat
        (han)
        @11 Ligne forcée ~2
        Demi ligne ~0.5
        [Refrain]
        On monte, on monte (on monte)
    """.trimIndent()

    @Test
    fun parsesSectionsAndTiming() {
        val parsed = LyricsParser.parse(song)
        assertEquals(listOf("Intro", "Couplet 1", "Refrain"), parsed.sections.map { it.name })
        assertEquals(listOf(1.0, 5.0, 13.5), parsed.sections.map { it.start })

        val starts = parsed.lines.map { it.start }
        assertEquals(listOf(1.0, 5.0, 6.0, 11.0, 13.0, 13.5), starts)
        assertEquals(2.0, parsed.lines[3].bars, 0.0)
        assertEquals("Couplet 1", parsed.lines[1].section)
    }

    @Test
    fun firstAdlibLineOfSongIsTimedButLaterOnesAttach() {
        val parsed = LyricsParser.parse(song)
        assertTrue(parsed.lines[0].segments.single().adlib)
        assertEquals(listOf("(han)"), parsed.lines[2].adlibLines)
    }

    @Test
    fun splitsInlineAdlibs() {
        val segments = LyricsParser.splitSegments("On monte, on monte (on monte)")
        assertEquals(2, segments.size)
        assertEquals("(on monte)", segments[1].text)
        assertTrue(segments[1].adlib)
    }

    @Test
    fun lineAtFollowsPosition() {
        val parsed = LyricsParser.parse(song)
        assertEquals(-1, LyricsParser.parse("[A @3]\nx").lineAt(1.0))
        assertEquals(0, parsed.lineAt(2.5))
        assertEquals(1, parsed.lineAt(5.0))
        assertEquals(2, parsed.lineAt(10.9))
        assertEquals(3, parsed.lineAt(12.0))
        assertEquals(5, parsed.lineAt(20.0))
        assertEquals("Refrain", parsed.sectionAt(14.0)?.name)
    }

    @Test
    fun countsSyllablesApproximately() {
        assertEquals(6, LyricsParser.countSyllables("Je pose mes mots sur la"))
        assertEquals(3, LyricsParser.countSyllables("la musique"))
        assertEquals(5, LyricsParser.countSyllables("I been on the road"))
    }

    @Test
    fun barsPerLineDefault() {
        val parsed = LyricsParser.parse("a\nb\nc", barsPerLine = 2.0)
        assertEquals(listOf(1.0, 3.0, 5.0), parsed.lines.map { it.start })
    }
}
