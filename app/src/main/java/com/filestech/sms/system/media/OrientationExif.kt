package com.filestech.sms.system.media

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber
import java.io.File

/**
 * 2026-11-22 (courriel de Caroline : « les photos jointes sont tournées de 90° vers la gauche ») — l'orientation
 * EXIF d'une photo qu'on réduit pour un MMS.
 *
 * Un appareil photo enregistre souvent l'image telle que le capteur la voit, couchée, et dit dans l'étiquette EXIF
 * `Orientation` comment la redresser à l'affichage. `BitmapFactory` ignore cette étiquette, et le JPEG réencodé par
 * `ThreadViewModel.compressImage` (toute photo qui dépasse sa part des 280 Ko d'un MMS, donc presque toutes) ne la
 * porte plus : une photo prise en portrait partait couchée, tournée de 90° dans le sens inverse des aiguilles d'une
 * montre, depuis la v1.2.1. On applique donc l'étiquette aux pixels avant de réencoder.
 */
internal object OrientationExif {

    /** Ce que demande une valeur `Orientation` : une rotation dans le sens horaire, PUIS un miroir horizontal. */
    data class Redressement(val degres: Int, val miroir: Boolean)

    /**
     * Les huit valeurs de l'étiquette (TIFF 6.0, 0x0112), dans l'ordre de la norme. `null` : rien à faire — 1
     * (normale), 0 (absente) ou une valeur inconnue, qu'on ne devine pas.
     */
    fun redressementPour(orientation: Int): Redressement? = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Redressement(0, miroir = true)
        ExifInterface.ORIENTATION_ROTATE_180 -> Redressement(180, miroir = false)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Redressement(180, miroir = true)
        ExifInterface.ORIENTATION_TRANSPOSE -> Redressement(90, miroir = true)
        ExifInterface.ORIENTATION_ROTATE_90 -> Redressement(90, miroir = false)
        ExifInterface.ORIENTATION_TRANSVERSE -> Redressement(270, miroir = true)
        ExifInterface.ORIENTATION_ROTATE_270 -> Redressement(270, miroir = false)
        else -> null
    }

    /** L'étiquette de [fichier] ; une lecture impossible (pas un JPEG, fichier illisible) vaut « normale ». */
    fun lire(fichier: File): Int = runCatching {
        ExifInterface(fichier.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * [bitmap] redressée selon [redressement] ; la source est recyclée quand une nouvelle image la remplace.
     * Allocation sous `runCatching` (qui attrape aussi `OutOfMemoryError`, comme le reste de la réduction) : en cas
     * d'échec, l'image part comme avant, non redressée, plutôt que pas du tout.
     */
    fun redresser(bitmap: Bitmap, redressement: Redressement): Bitmap {
        val matrice = Matrix().apply {
            setRotate(redressement.degres.toFloat())
            if (redressement.miroir) postScale(-1f, 1f)
        }
        val droite = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrice, true)
        }.getOrElse {
            Timber.w(it, "OrientationExif: redressement impossible, image envoyee telle quelle")
            return bitmap
        }
        if (droite !== bitmap) bitmap.recycle()
        return droite
    }
}
