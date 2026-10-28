package com.scribup.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicTheoryTest {
    @Test
    fun parsesKeys() {
        assertEquals(MusicTheory.Key(9, true), MusicTheory.parseKey("A minor"))
        assertEquals(MusicTheory.Key(9, true), MusicTheory.parseKey("Am"))
        assertEquals(MusicTheory.Key(3, false), MusicTheory.parseKey("Mib majeur"))
        assertEquals(MusicTheory.Key(6, false), MusicTheory.parseKey("F# major"))
        assertEquals(MusicTheory.Key(7, true), MusicTheory.parseKey("Sol mineur"))
    }

    @Test
    fun diatonicChordsOfAMinor() {
        val chords = MusicTheory.diatonicChords(MusicTheory.Key(9, true), latin = false).map { it.chord }
        assertEquals(listOf("Am", "Bdim", "C", "Dm", "Em", "F", "G"), chords)
    }

    @Test
    fun relativeKeys() {
        assertEquals(MusicTheory.Key(0, false), MusicTheory.Key(9, true).relative)
        assertEquals(MusicTheory.Key(4, true), MusicTheory.Key(7, false).relative)
    }

    @Test
    fun latinChordDisplay() {
        assertEquals("Fa#m7/La", MusicTheory.displayChord("F#m7/A", latin = true))
        assertEquals("Sibmaj7", MusicTheory.displayChord("Bbmaj7", latin = true))
    }
}
