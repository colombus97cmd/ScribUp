package com.scribup.app.ai

import com.scribup.app.data.Song
import com.scribup.app.lyrics.LyricsParser

object Prompts {

    /** Prompt système fixe (ne dépend pas du morceau) : il reste en cache d'un appel à l'autre. */
    val SYSTEM = """
        Tu es ScribUp, le binôme d'écriture et de composition d'un artiste indépendant qui fait du rap et du chant,
        et qui produit ses instrus dans FL Studio. Tu l'aides à écrire, pas à écrire à sa place : tu proposes des
        pistes, des variantes et des corrections qu'il choisit et retravaille. Garde son vocabulaire, son argot,
        son univers et son niveau de langue ; n'aseptise pas son style.

        Écriture :
        - Il écrit en plusieurs langues et mélange volontiers les langues dans un même morceau (code-switching).
          Réponds dans la langue qu'il utilise pour te parler ; écris les propositions de paroles dans la ou les
          langues demandées. Quand tu traduis, adapte (rimes, rythme, images) au lieu de traduire mot à mot.
        - Pense en rimes riches et multisyllabiques, assonances, allitérations, rimes internes, punchlines,
          placements (sur le temps, en décalé), respirations et nombre de syllabes par mesure au BPM donné.
        - Les ad-libs s'écrivent entre parenthèses : « je monte (yeah) ».
        - Ses paroles utilisent cette syntaxe, respecte-la quand tu proposes des lignes à coller :
          [Section] ou [Section @9] pour une section qui commence mesure 9 ; « ~2 » en fin de ligne = 2 mesures ;
          « @17 » en début de ligne = commence mesure 17 ; ligne entre parenthèses = ad-lib.

        Musique :
        - Tu reçois parfois la tonalité et la grille d'accords détectées en direct depuis FL Studio
          (détection automatique : elle peut se tromper, signale-le si quelque chose paraît incohérent).
        - Pour la composition, donne des choses directement utilisables dans FL Studio : accords avec les notes
          (ex. Am = A C E), degrés, gamme à activer dans le piano roll (Scale highlighting), voicings, idées de
          basse/808, mélodie de topline en noms de notes et en rythme, structure en mesures pour la playlist,
          outils FL utiles (Chord/Arpeggiate du piano roll, Ghost notes, Pitcher/NewTone pour la voix...).

        Forme des réponses : courtes et concrètes, lisibles sur un téléphone. Les paroles proposées vont sur
        des lignes séparées, prêtes à copier. Pas de longues introductions. Pose une question seulement si
        c'est vraiment nécessaire.
    """.trimIndent()

    /** Contexte du morceau, joint au message de l'utilisateur quand il a changé. */
    fun songContext(song: Song, liveKey: String?, grid: Map<Int, String>, languages: String): String {
        val parsed = LyricsParser.parse(song.lyrics, song.barsPerLine)
        val gridText = grid.entries.sortedBy { it.key }.joinToString("  ") { "M${it.key}:${it.value}" }
        return buildString {
            appendLine("<morceau>")
            appendLine("Titre : ${song.title}")
            appendLine("BPM : ${song.bpm}")
            val key = song.key.ifBlank { liveKey ?: "" }
            if (key.isNotBlank()) appendLine("Tonalité : $key${if (song.key.isBlank()) " (détectée)" else ""}")
            if (languages.isNotBlank()) appendLine("Langues : $languages")
            if (gridText.isNotBlank()) appendLine("Grille d'accords captée dans FL (mesure:accord) : $gridText")
            if (song.notes.isNotBlank()) appendLine("Notes de l'artiste : ${song.notes}")
            appendLine("Paroles actuelles (${parsed.lines.size} lignes) :")
            appendLine(song.lyrics.ifBlank { "(page blanche)" })
            append("</morceau>")
        }
    }

    data class QuickAction(val label: String, val prompt: (QuickContext) -> String)

    data class QuickContext(val lastLine: String, val languages: String, val bpm: Int, val key: String)

    val QUICK_ACTIONS = listOf(
        QuickAction("Rimes") { c ->
            "Donne-moi des rimes pour la fin de « ${c.lastLine} » : rimes riches, multisyllabiques et assonances, " +
                "classées par sonorité, avec quelques exemples de lignes."
        },
        QuickAction("Ligne suivante") {
            "Propose 4 variantes pour la ligne suivante, même flow, même nombre de syllabes environ, " +
                "et qui gardent le schéma de rimes."
        },
        QuickAction("Ad-libs") {
            "Propose des ad-libs (entre parenthèses) pour la section en cours, en indiquant après quelle ligne " +
                "les placer et l'intention (énergie, réponse, écho)."
        },
        QuickAction("Mixer les langues") { c ->
            "Réécris le dernier passage en mélangeant ${c.languages.ifBlank { "les langues que j'utilise" }}, " +
                "en gardant les rimes et le rythme. Donne 2 versions."
        },
        QuickAction("Flow") { c ->
            "Analyse le flow à ${c.bpm} BPM : syllabes par mesure, lignes trop chargées ou trop vides, " +
                "où respirer, et propose des placements plus intéressants."
        },
        QuickAction("Refrain") {
            "Propose 3 idées de refrain / hook accrocheur cohérentes avec le thème, avec une indication de mélodie."
        },
        QuickAction("Accords") { c ->
            "À partir de la tonalité ${c.key.ifBlank { "détectée" }} et de la grille captée, analyse la progression " +
                "(degrés) et propose des variations ou un pont, avec les notes de chaque accord pour le piano roll."
        },
        QuickAction("Topline") {
            "Propose une mélodie de chant pour le refrain sur la grille actuelle : notes, rythme approximatif " +
                "en temps, et où placer les syllabes fortes."
        },
        QuickAction("Arrangement FL") {
            "Propose une structure complète du morceau en mesures (intro, couplets, refrains, pont, outro) " +
                "pour organiser la playlist FL Studio, avec les idées d'instrumentation par partie."
        },
        QuickAction("Corriger") {
            "Relis mes paroles : fautes, rimes faibles, répétitions, images clichés. Propose des corrections " +
                "ligne par ligne sans changer mon style."
        },
    )
}
