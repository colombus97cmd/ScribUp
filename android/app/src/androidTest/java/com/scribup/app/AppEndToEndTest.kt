package com.scribup.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.scribup.app.data.Song
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Parcours complet sur émulateur, avec le pont PC lancé en mode démo sur la machine hôte
 * (`scribup_bridge.py --demo --pin 4242`, joignable depuis l'émulateur en 10.0.2.2).
 * Les captures d'écran sont enregistrées dans Pictures/ScribUp.
 */
@RunWith(AndroidJUnit4::class)
class AppEndToEndTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun liveSessionWithDemoBridge() {
        context.getSharedPreferences("scribup", Context.MODE_PRIVATE).edit()
            .clear()
            .putString("bridgeHost", "10.0.2.2")
            .putInt("bridgePort", 8765)
            .putString("bridgePin", "4242")
            .putInt("liveFontSize", 24)
            .commit()
        val songs = File(context.filesDir, "songs").apply { deleteRecursively(); mkdirs() }
        File(songs, "demo.json").writeText(Json.encodeToString(Song.serializer(), DEMO_SONG))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("Nuit blanche").performClick()
            compose.onNodeWithText("Live").performClick()
            // Le badge passe à « FL » quand le pont (simulateur FL) est connecté
            compose.waitUntil(20_000) { compose.onAllNodesWithText("FL").fetchSemanticsNodes().isNotEmpty() }
            // Toucher une section envoie « aller à la mesure » au simulateur FL
            compose.onNodeWithText("Intro · M1").performClick()
            Thread.sleep(6_000) // laisse tourner quelques mesures
            screenshot("1-live")

            compose.onNodeWithText("Accords").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Am").fetchSemanticsNodes().isNotEmpty() }
            screenshot("2-accords")

            compose.onNodeWithText("Claude").performClick()
            screenshot("3-claude")

            compose.onNodeWithText("Écrire").performClick()
            screenshot("4-ecrire")
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScribUp")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        val DEMO_SONG = Song(
            id = "demo",
            title = "Nuit blanche",
            bpm = 90,
            key = "",
            languages = "Français, Anglais, Espagnol",
            lyrics = """
                [Intro]
                (yeah, yeah)
                [Couplet 1 @2]
                Je pose mes mots quand la ville s'endort (skrrt)
                Les néons dans les yeux, j'ai la tête dans le décor
                (han)
                I been on the road, j'compte plus les kilomètres
                Mi corazón en feu, faut qu'j'apprenne à m'en remettre
                [Refrain @6]
                Nuit blanche (nuit blanche), on brille jusqu'au matin
                Nuit blanche, todo el mundo tient la main ~2
            """.trimIndent(),
        )
    }
}
