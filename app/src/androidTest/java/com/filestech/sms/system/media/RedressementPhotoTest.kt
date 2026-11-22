package com.filestech.sms.system.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 2026-11-22 (courriel de Caroline : photos jointes tournées de 90°) — une VRAIE photo JPEG, étiquetée, décodée
 * comme le fait `ThreadViewModel.compressImage` (`BitmapFactory`, qui ignore l'étiquette), puis redressée.
 *
 * L'image a quatre quarts de couleurs différentes : la taille ne distingue que la rotation de 90°, les quarts
 * distinguent aussi 180° d'un miroir. Attendu pour chaque valeur : ce qu'un afficheur qui respecte l'étiquette
 * montrerait. Le contrôle négatif est l'orientation 1, et le test entier échoue si `redresser` ne fait rien.
 */
@RunWith(AndroidJUnit4::class)
class RedressementPhotoTest {

    private enum class Teinte { ROUGE, BLEU, VERT, NOIR }

    private val dossier = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "test-orientation")

    @After
    fun nettoie() {
        dossier.deleteRecursively()
    }

    /** 160 × 80, quarts : haut-gauche rouge, haut-droite bleu, bas-gauche vert, bas-droite noir. */
    private fun photo(orientation: Int): File {
        val b = Bitmap.createBitmap(LARGEUR, HAUTEUR, Bitmap.Config.ARGB_8888)
        val demiL = LARGEUR / 2f
        val demiH = HAUTEUR / 2f
        Canvas(b).apply {
            drawRect(0f, 0f, demiL, demiH, Paint().apply { color = Color.RED })
            drawRect(demiL, 0f, LARGEUR.toFloat(), demiH, Paint().apply { color = Color.BLUE })
            drawRect(0f, demiH, demiL, HAUTEUR.toFloat(), Paint().apply { color = Color.GREEN })
            drawRect(demiL, demiH, LARGEUR.toFloat(), HAUTEUR.toFloat(), Paint().apply { color = Color.BLACK })
        }
        dossier.mkdirs()
        val f = File(dossier, "orientation-$orientation.jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        b.recycle()
        ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return f
    }

    private fun redressee(f: File): Bitmap {
        val brute = checkNotNull(BitmapFactory.decodeFile(f.absolutePath))
        val r = OrientationExif.redressementPour(OrientationExif.lire(f)) ?: return brute
        return OrientationExif.redresser(brute, r)
    }

    private fun teinte(pixel: Int): Teinte? {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        return when {
            r > 180 && g < 80 && b < 80 -> Teinte.ROUGE
            b > 180 && r < 80 && g < 80 -> Teinte.BLEU
            g > 180 && r < 80 && b < 80 -> Teinte.VERT
            r < 60 && g < 60 && b < 60 -> Teinte.NOIR
            else -> null
        }
    }

    /** [attendu] : haut-gauche, haut-droite, bas-gauche, bas-droite de l'image redressée. */
    private fun verifie(orientation: Int, largeur: Int, hauteur: Int, vararg attendu: Teinte) {
        val f = photo(orientation)
        assertWithMessage("étiquette relue ($orientation)").that(OrientationExif.lire(f)).isEqualTo(orientation)
        val b = redressee(f)
        assertWithMessage("taille ($orientation)").that(b.width to b.height).isEqualTo(largeur to hauteur)
        val quarts = listOf(
            b.getPixel(largeur / 4, hauteur / 4),
            b.getPixel(3 * largeur / 4, hauteur / 4),
            b.getPixel(largeur / 4, 3 * hauteur / 4),
            b.getPixel(3 * largeur / 4, 3 * hauteur / 4),
        ).map { teinte(it) }
        assertWithMessage("quarts ($orientation)").that(quarts).containsExactlyElementsIn(attendu.toList()).inOrder()
        b.recycle()
    }

    @Test
    fun normaleRienNeBouge() = verifie(1, LARGEUR, HAUTEUR, Teinte.ROUGE, Teinte.BLEU, Teinte.VERT, Teinte.NOIR)

    @Test
    fun portraitRotation90Horaire() = verifie(6, HAUTEUR, LARGEUR, Teinte.VERT, Teinte.ROUGE, Teinte.NOIR, Teinte.BLEU)

    @Test
    fun rotation270() = verifie(8, HAUTEUR, LARGEUR, Teinte.BLEU, Teinte.NOIR, Teinte.ROUGE, Teinte.VERT)

    @Test
    fun rotation180() = verifie(3, LARGEUR, HAUTEUR, Teinte.NOIR, Teinte.VERT, Teinte.BLEU, Teinte.ROUGE)

    @Test
    fun miroirHorizontal() = verifie(2, LARGEUR, HAUTEUR, Teinte.BLEU, Teinte.ROUGE, Teinte.NOIR, Teinte.VERT)

    @Test
    fun miroirVertical() = verifie(4, LARGEUR, HAUTEUR, Teinte.VERT, Teinte.NOIR, Teinte.ROUGE, Teinte.BLEU)

    @Test
    fun transposee() = verifie(5, HAUTEUR, LARGEUR, Teinte.ROUGE, Teinte.VERT, Teinte.BLEU, Teinte.NOIR)

    @Test
    fun transverse() = verifie(7, HAUTEUR, LARGEUR, Teinte.NOIR, Teinte.BLEU, Teinte.VERT, Teinte.ROUGE)

    private companion object {
        const val LARGEUR = 160
        const val HAUTEUR = 80
    }
}
