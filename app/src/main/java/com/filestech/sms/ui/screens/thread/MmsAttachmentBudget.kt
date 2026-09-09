package com.filestech.sms.ui.screens.thread

/**
 * v1.28.3 (audit global du 2026-09-09, X-03 — **mesuré sur le S9**) — **le plafond MMS se
 * partage entre les images d'un même message.**
 *
 * Chaque image était compressée pour tenir SEULE sous le plafond opérateur (280 Ko), puis
 * l'ajout de la seconde faisait dépasser le total : « Limite MMS atteinte » dès la deuxième
 * photo, quelle qu'elle soit. Envoyer deux photos dans un MMS était impossible — et le chemin
 * ENTRANT venait justement d'apprendre à en afficher plusieurs (F16).
 *
 * L'arithmétique vit ici, seule et testable ; le ViewModel ne fait que l'appliquer.
 */
object MmsAttachmentBudget {

    /**
     * En dessous, une photo n'est plus lisible : 800 px en JPEG à qualité 30 pèse 40 à 60 Ko.
     * C'est le seuil du refus — trois ou quatre photos passent, pas huit.
     */
    const val MIN_IMAGE_BYTES: Long = 48L * 1024L

    /**
     * La part de plafond que chaque image peut occuper, ou `null` si même au minimum les images
     * ne tiennent pas — c'est alors le refus, avec le même message qu'avant.
     *
     * @param plafond plafond de charge utile du message.
     * @param texteBytes longueur du texte accompagnant.
     * @param autresBytes taille des pièces jointes qui ne sont pas des images (vocal, vCard,
     *   fichier) — elles ne se compressent pas et se servent en premier.
     * @param nombreImages images du message, celle qu'on ajoute comprise.
     */
    fun partParImage(plafond: Long, texteBytes: Long, autresBytes: Long, nombreImages: Int): Long? {
        if (nombreImages <= 0) return null
        val libre = plafond - texteBytes - autresBytes
        if (libre <= 0L) return null
        val part = libre / nombreImages
        return part.takeIf { it >= MIN_IMAGE_BYTES }
    }
}
