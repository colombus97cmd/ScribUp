package com.scribup.app.lyrics

/** Petits outils de théorie, calculés sur le téléphone (pas besoin de l'API). */
object MusicTheory {
    val NOTES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val LATIN = listOf("Do", "Do#", "Ré", "Mib", "Mi", "Fa", "Fa#", "Sol", "Lab", "La", "Sib", "Si")
    private val ALIASES = mapOf("Db" to "C#", "D#" to "Eb", "Gb" to "F#", "G#" to "Ab", "A#" to "Bb")

    private val MAJOR = listOf(0, 2, 4, 5, 7, 9, 11)
    private val MINOR = listOf(0, 2, 3, 5, 7, 8, 10)
    private val MAJOR_QUALITIES = listOf("", "m", "m", "", "", "m", "dim")
    private val MINOR_QUALITIES = listOf("m", "dim", "", "m", "m", "", "")
    private val MAJOR_DEGREES = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
    private val MINOR_DEGREES = listOf("i", "ii°", "III", "iv", "v", "VI", "VII")

    data class Key(val tonic: Int, val minor: Boolean) {
        fun name(latin: Boolean) = noteName(tonic, latin) + if (minor) " mineur" else " majeur"

        /** Tonalité relative (mêmes notes) : La mineur <-> Do majeur. */
        val relative: Key get() = if (minor) Key((tonic + 3) % 12, false) else Key((tonic + 9) % 12, true)
    }

    data class Diatonic(val degree: String, val chord: String)

    fun noteIndex(name: String): Int? {
        val n = ALIASES[name] ?: name
        return NOTES.indexOf(n).takeIf { it >= 0 }
    }

    fun noteName(index: Int, latin: Boolean) = (if (latin) LATIN else NOTES)[((index % 12) + 12) % 12]

    /** "A minor", "La mineur", "Am", "F# major"… -> Key */
    fun parseKey(text: String?): Key? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        val latinIdx = LATIN.indices.sortedByDescending { LATIN[it].length }
            .firstOrNull { t.startsWith(LATIN[it], ignoreCase = true) }
        val (tonic, rest) = if (latinIdx != null) {
            latinIdx to t.substring(LATIN[latinIdx].length)
        } else {
            val root = if (t.length > 1 && (t[1] == '#' || t[1] == 'b')) t.substring(0, 2) else t.substring(0, 1).uppercase()
            (noteIndex(root) ?: return null) to t.substring(root.length)
        }
        val r = rest.trim().lowercase()
        val minor = r.startsWith("m") && !r.startsWith("maj") || r.startsWith("min") || r.startsWith("mineur")
        return Key(tonic, minor)
    }

    fun scale(key: Key): List<Int> = (if (key.minor) MINOR else MAJOR).map { (key.tonic + it) % 12 }

    fun diatonicChords(key: Key, latin: Boolean): List<Diatonic> {
        val qualities = if (key.minor) MINOR_QUALITIES else MAJOR_QUALITIES
        val degrees = if (key.minor) MINOR_DEGREES else MAJOR_DEGREES
        return scale(key).mapIndexed { i, note -> Diatonic(degrees[i], noteName(note, latin) + qualities[i]) }
    }

    /** Traduit un accord "F#m7/A" en notation latine si demandé. */
    fun displayChord(chord: String?, latin: Boolean): String {
        if (chord.isNullOrBlank()) return "—"
        if (!latin) return chord
        return chord.split("/").joinToString("/") { part ->
            val root = if (part.length > 1 && (part[1] == '#' || part[1] == 'b')) part.substring(0, 2) else part.take(1)
            val idx = noteIndex(root) ?: return@joinToString part
            noteName(idx, true) + part.substring(root.length)
        }
    }
}
