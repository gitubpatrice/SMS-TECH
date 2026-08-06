package com.filestech.sms.data.safetycall

import com.filestech.sms.domain.safetycall.journal.SafetyCallJournalEntry
import com.filestech.sms.domain.safetycall.journal.SafetyCallJournalRotation
import java.io.File

/**
 * v1.28.0 — écrit le journal technique du Safety call dans un fichier du bac à sable.
 *
 * ⚠️ **Non câblé à ce stade** : aucun appelant, aucune injection Hilt, aucun réglage d'activation.
 * Le câblage — points d'appel dans le moteur, opt-in auto-expirant, destruction à l'effacement
 * panique, action de partage — reste à faire et demande une relecture. Voir `idees/IDEES.md`.
 *
 * # Le contrat, en une phrase
 *
 * **Ne jamais faire échouer l'appelant.** Toutes les méthodes avalent leurs exceptions. La séquence
 * du Safety call tourne dans un worker, en Doze, et son unique mission est que les SMS partent : une
 * écriture de fichier qui peut échouer — disque plein, répertoire retiré, verrou — n'a aucun droit
 * de peser sur ce chemin. Un journal muet est un désagrément ; une alerte non partie est un accident.
 *
 * # Pourquoi aucun `Timber` ici
 *
 * Ce journal existe **parce que** R8 supprime `Timber` et `android.util.Log` en release, rendant le
 * moteur invisible sur l'appareil de l'utilisateur. Journaliser l'échec du journal par le moyen que
 * le journal remplace serait circulaire : en release, ce log n'existerait pas. L'échec est donc
 * silencieux par conception, et c'est le battement de cœur — `SafetyCallJournalEvent.HEARTBEAT` — qui
 * révèle un journal qui ne s'écrit plus, par le trou qu'il laisse.
 *
 * # Nom de fichier neutre, délibérément
 *
 * [FILE_NAME] ne nomme pas le Safety call. Le mode leurre repose sur le fait qu'aucune trace ne
 * désigne le dispositif ; un fichier appelé `safety-call-historique.log` l'annoncerait à quiconque
 * inspecte le stockage de l'application, et annulerait le leurre par son seul nom.
 *
 * # Bornage : voir [SafetyCallJournalRotation]
 *
 * Le contrat de contenu est « les N dernières séquences, complètes ». [BYTE_TRIGGER] n'est que le
 * déclencheur de l'élagage, pas la règle de conservation : entre deux élagages le fichier peut donc
 * contenir **plus** de [SafetyCallJournalRotation.MAX_CYCLES] cycles. C'est assumé — élaguer à chaque
 * ligne coûterait une réécriture complète du fichier à chaque réveil, sur le chemin qu'on veut
 * justement laisser tranquille.
 *
 * @param directory répertoire d'accueil, à choisir dans `filesDir` — **jamais** en stockage partagé.
 *   Passé en paramètre plutôt que dérivé d'un `Context` : la classe reste testable sur la JVM.
 */
class SafetyCallJournalFile(private val directory: File) {

    /**
     * ⚠️ **Exigence de câblage : cette classe doit être fournie en singleton.**
     *
     * Le verrou est porté par l'instance, pas par le fichier. Deux instances visant le même
     * répertoire ne se protégeraient donc pas l'une de l'autre, et un ajout entrelacé avec une
     * réécriture d'élagage produirait un fichier mêlant deux états — exactement ce que ce verrou
     * existe pour empêcher. Or les deux appelants prévus coexistent réellement : le worker qui envoie
     * la séquence et l'activité qui remet le minuteur à zéro.
     *
     * Le module Hilt qui la fournira devra donc la porter en `@Singleton`.
     */
    private val lock = Any()

    private val file: File get() = File(directory, FILE_NAME)

    /**
     * Ajoute une ligne, et élague si le fichier a dépassé [BYTE_TRIGGER].
     *
     * `synchronized` parce que deux appelants coexistent réellement — le worker de la séquence et
     * l'activité qui remet le minuteur à zéro — et qu'un ajout entrelacé avec une réécriture
     * d'élagage produirait un fichier mêlant deux états.
     */
    fun append(entry: SafetyCallJournalEntry) {
        synchronized(lock) {
            runCatching {
                directory.mkdirs()
                val target = file
                target.appendText(entry.format() + LINE_SEPARATOR)
                if (target.length() > BYTE_TRIGGER) pruneLocked(target)
            }
        }
    }

    /** Lignes du journal, les illisibles comprises — c'est [SafetyCallJournalRotation] qui trie.
     * Rend une liste vide si le fichier n'existe pas ou n'est pas lisible. */
    fun read(): List<String> = synchronized(lock) {
        runCatching { file.readLines().filter { line -> line.isNotBlank() } }.getOrDefault(emptyList())
    }

    /**
     * Supprime le journal.
     *
     * ⚠️ **Doit être appelé par l'effacement panique.** Un effacement de contrainte qui laisserait
     * derrière lui un journal nommant des destinataires — même réduits — annulerait sa propre raison
     * d'être. Ce câblage fait partie de ce qui reste à faire.
     */
    fun clear() {
        synchronized(lock) { runCatching { file.delete() } }
    }

    /** Élagage. Appelé sous [lock] uniquement. */
    private fun pruneLocked(target: File) {
        runCatching {
            val kept = SafetyCallJournalRotation.prune(target.readLines())
            // Écriture en une passe : `writeText` tronque puis écrit. Un arrêt brutal au milieu peut
            // laisser un fichier partiel, et c'est acceptable — la dernière ligne sera illisible et
            // [SafetyCallJournalRotation.prune] l'écartera au prochain passage. Perdre une ligne de
            // diagnostic est bénin ; ce qui ne l'est pas serait de faire échouer l'appelant.
            //
            // Le cas vide est distingué : `joinToString(postfix = "\n")` sur une liste vide rendrait
            // un fichier contenant un seul saut de ligne, c'est-à-dire un journal qui n'est ni absent
            // ni lisible.
            target.writeText(
                if (kept.isEmpty()) {
                    ""
                } else {
                    kept.joinToString(LINE_SEPARATOR, postfix = LINE_SEPARATOR)
                },
            )
        }
    }

    companion object {

        /** Nom volontairement neutre — voir la note de classe. */
        const val FILE_NAME: String = "diag.log"

        /**
         * Taille au-delà de laquelle l'élagage se déclenche. Un cycle complet pèse quelques dizaines
         * de lignes d'environ 80 octets, soit ~2 à 3 Ko : ce seuil laisse donc vivre une dizaine de
         * cycles avant de retomber aux cinq derniers.
         *
         * ⚠️ **Doit rester strictement supérieur à [SafetyCallJournalRotation.MAX_BYTES]**, et avec
         * de la marge. C'est cet écart, et lui seul, qui fait converger l'élagage : après un passage,
         * le fichier tient sous `MAX_BYTES`, donc sous ce seuil, et il faut de nouvelles écritures
         * pour le franchir à nouveau.
         *
         * Sans cet écart, un cycle pathologique — une boucle de réveils, ce que le journal existe
         * pour diagnostiquer — laisserait le fichier au-dessus du seuil en permanence, et **chaque
         * écriture déclencherait une relecture puis une réécriture complètes**, sur le chemin qu'on
         * veut précisément laisser tranquille. Une garde qui ne converge pas coûte à chaque passage
         * sans jamais rien régler.
         */
        const val BYTE_TRIGGER: Long = 32L * 1024L

        private const val LINE_SEPARATOR = "\n"
    }
}
