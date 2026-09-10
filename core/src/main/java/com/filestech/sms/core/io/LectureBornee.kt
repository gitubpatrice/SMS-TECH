package com.filestech.sms.core.io

import java.io.File
import java.io.InputStream

/**
 * v1.28.4 (F27, suite de la relecture externe de la MR F-Droid !38458) — **les deux lectures
 * bornées de l'application, en un seul endroit testable.**
 *
 * La v1.28.3 a posé les bornes ; elle les a posées en ligne, dans un `ViewModel` et un
 * `BroadcastReceiver`, là où aucun test JVM ne pouvait les atteindre — le commit le disait :
 * « les trois bornes de F27 se mesurent à l'allocation ». Ici, elles se mesurent à l'octet.
 *
 * Ce que ces deux fonctions promettent, et que leurs tests vérifient au plafond exact :
 * - [recopier] n'écrit **jamais** plus de [plafond] octets, et ne laisse **aucun fichier** derrière
 *   elle quand la source dépasse — un fournisseur qui ne déclare pas sa taille, ou qui ment ;
 * - [lire] refuse **avant d'allouer** : un fichier plus grand que [plafond] rend `null` sans
 *   qu'un seul octet soit lu.
 */
object LectureBornee {

    const val TAMPON_BYTES: Int = 64 * 1024

    /**
     * Recopie [source] dans [cible], au plus [plafond] octets. Rend `true` si tout a été recopié
     * sous le plafond ; `false` si la source dépasse, et [cible] est alors supprimée.
     * Une source vide rend `false` aussi : rien n'a été recopié, il n'y a rien à garder.
     */
    fun recopier(source: InputStream, cible: File, plafond: Long, tampon: Int = TAMPON_BYTES): Boolean {
        require(plafond >= 0L) { "plafond negatif" }
        var recopie = 0L
        var depasse = false
        source.use { input ->
            cible.outputStream().use { os ->
                val bloc = ByteArray(tampon)
                var lus = input.read(bloc)
                while (lus > 0 && !depasse) {
                    recopie += lus
                    if (recopie > plafond) {
                        depasse = true
                    } else {
                        os.write(bloc, 0, lus)
                        lus = input.read(bloc)
                    }
                }
            }
        }
        if (depasse || recopie == 0L) {
            cible.delete()
            return false
        }
        return true
    }

    /** Le contenu de [fichier], ou `null` s'il dépasse [plafond] — décidé sur sa taille, avant toute lecture. */
    fun lire(fichier: File, plafond: Long): ByteArray? {
        require(plafond >= 0L) { "plafond negatif" }
        if (!fichier.isFile || fichier.length() > plafond) return null
        return runCatching { fichier.readBytes() }.getOrNull()
    }
}
