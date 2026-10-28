package com.scribup.app.lyrics

import kotlin.math.floor

/**
 * Syntaxe ScribUp (texte brut, facile à taper au téléphone) :
 *
 *   [Refrain]          début de section
 *   [Couplet 1 @9]     section qui commence à la mesure 9
 *   @17 ma ligne       force le début de la ligne à la mesure 17
 *   ma ligne ~2        la ligne dure 2 mesures (~0.5 = une demi-mesure)
 *   texte (yeah)       ce qui est entre parenthèses est un ad-lib
 *   (skrrt)            une ligne entièrement entre parenthèses est un ad-lib
 *                      rattaché à la ligne précédente (ajoute ~1 pour qu'elle ait sa mesure)
 *   // note            commentaire, ignoré
 *
 * Sans indication, chaque ligne dure `barsPerLine` mesures.
 */
object LyricsParser {

    data class Segment(val text: String, val adlib: Boolean)

    data class Line(
        val index: Int,
        val section: String?,
        val segments: List<Segment>,
        /** Position de départ en mesures, 1.0 = premier temps de la mesure 1. */
        val start: Double,
        val bars: Double,
        val syllables: Int,
        /** Lignes d'ad-libs rattachées (affichées sous la ligne). */
        val adlibLines: List<String>,
        /** Numéro de la ligne dans le texte source (0-based). */
        val sourceLine: Int,
    ) {
        val text: String get() = segments.joinToString("") { it.text }
        val end: Double get() = start + bars
        val bar: Int get() = floor(start).toInt()
    }

    data class Section(val name: String, val start: Double, val firstLine: Int)

    data class Parsed(val lines: List<Line>, val sections: List<Section>) {
        /** Index de la ligne en cours à la position [pos] (en mesures), ou -1 avant la première. */
        fun lineAt(pos: Double): Int {
            var current = -1
            for (line in lines) {
                if (line.start <= pos + 1e-6) current = line.index else break
            }
            return current
        }

        fun sectionAt(pos: Double): Section? = sections.lastOrNull { it.start <= pos + 1e-6 }
    }

    private val sectionRegex = Regex("""^\[(.+?)(?:\s*@\s*(\d+(?:[.,]\d+)?))?\s*]$""")
    private val barPrefixRegex = Regex("""^@\s*(\d+(?:[.,]\d+)?)\s+""")
    private val durationSuffixRegex = Regex("""\s+~\s*(\d+(?:[.,]\d+)?)$""")

    fun parse(text: String, barsPerLine: Double = 1.0): Parsed {
        val lines = mutableListOf<Line>()
        val sections = mutableListOf<Section>()
        var cursor = 1.0
        var section: String? = null

        text.lines().forEachIndexed { sourceIndex, rawLine ->
            var raw = rawLine.trim()
            if (raw.isEmpty() || raw.startsWith("//")) return@forEachIndexed

            sectionRegex.matchEntire(raw)?.let { match ->
                val name = match.groupValues[1].trim()
                section = name
                match.groupValues[2].takeIf { it.isNotEmpty() }?.let { cursor = it.toBars() }
                sections += Section(name, cursor, lines.size)
                return@forEachIndexed
            }

            var explicitStart: Double? = null
            barPrefixRegex.find(raw)?.let { match ->
                explicitStart = match.groupValues[1].toBars()
                raw = raw.substring(match.range.last + 1)
            }
            var explicitBars: Double? = null
            durationSuffixRegex.find(raw)?.let { match ->
                explicitBars = match.groupValues[1].toBars()
                raw = raw.substring(0, match.range.first)
            }
            raw = raw.trim()
            if (raw.isEmpty()) return@forEachIndexed

            val segments = splitSegments(raw)
            val onlyAdlib = segments.all { it.adlib || it.text.isBlank() }

            if (onlyAdlib && explicitBars == null && explicitStart == null && lines.isNotEmpty()) {
                val previous = lines.removeAt(lines.size - 1)
                lines += previous.copy(adlibLines = previous.adlibLines + raw)
                return@forEachIndexed
            }

            val start = explicitStart ?: cursor
            val bars = explicitBars ?: barsPerLine
            lines += Line(
                index = lines.size,
                section = section,
                segments = segments,
                start = start,
                bars = bars,
                syllables = countSyllables(segments.filterNot { it.adlib }.joinToString(" ") { it.text }),
                adlibLines = emptyList(),
                sourceLine = sourceIndex,
            )
            cursor = start + bars
        }
        return Parsed(lines, sections)
    }

    /** Découpe "texte (adlib) texte" en segments ; tolère une parenthèse non fermée. */
    fun splitSegments(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val buffer = StringBuilder()
        var depth = 0
        for (c in text) {
            when {
                c == '(' -> {
                    if (depth == 0 && buffer.isNotEmpty()) {
                        out += Segment(buffer.toString(), adlib = false); buffer.clear()
                    }
                    depth++
                    buffer.append(c)
                }
                c == ')' && depth > 0 -> {
                    buffer.append(c)
                    depth--
                    if (depth == 0) {
                        out += Segment(buffer.toString(), adlib = true); buffer.clear()
                    }
                }
                else -> buffer.append(c)
            }
        }
        if (buffer.isNotEmpty()) out += Segment(buffer.toString(), adlib = depth > 0)
        return out
    }

    private const val VOWELS = "aeiouyàâäáãåéèêëíìîïóòôöõúùûüœæÿ"

    /**
     * Compte approximatif des syllabes (groupes de voyelles), avec le « e » muet final
     * du français. Suffisant pour comparer des lignes entre elles et caler un flow.
     */
    fun countSyllables(text: String): Int {
        var total = 0
        for (word in text.lowercase().split(Regex("""[^\p{L}']+"""))) {
            if (word.isEmpty()) continue
            var groups = 0
            var inVowel = false
            for (c in word) {
                val vowel = c in VOWELS
                if (vowel && !inVowel) groups++
                inVowel = vowel
            }
            if (groups > 1 && (word.endsWith("e") || word.endsWith("es")) &&
                !word.endsWith("ée") && !word.endsWith("ées")
            ) groups--
            total += groups.coerceAtLeast(if (word.any { it.isLetter() }) 1 else 0)
        }
        return total
    }

    private fun String.toBars(): Double = replace(',', '.').toDouble()
}
