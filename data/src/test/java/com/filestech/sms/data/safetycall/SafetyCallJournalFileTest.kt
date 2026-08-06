package com.filestech.sms.data.safetycall

import com.filestech.sms.domain.safetycall.journal.SafetyCallJournalEntry
import com.filestech.sms.domain.safetycall.journal.SafetyCallJournalEvent
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * v1.28.0 — verrouille l'écrivain du journal technique.
 *
 * # Ce qui compte ici, et par ordre d'importance
 *
 * **1. Ne jamais faire échouer l'appelant.** C'est la seule propriété qui puisse coûter une alerte
 * non partie. Le test l'exerce sur un chemin réellement impossible — un répertoire qui est en fait un
 * fichier — plutôt que sur un cas de confort, parce qu'un `runCatching` qui n'attrape rien n'est pas
 * testé par un appel qui réussit.
 *
 * **2. Le bornage se déclenche vraiment.** Un élagage jamais atteint est un chemin mort, l'un des
 * quatre motifs de défaut récurrents du dépôt : le code existe, le test vert le couvre, et il ne
 * tourne jamais en vrai. Le seuil est donc franchi pour de bon ici.
 *
 * **3. `clear()` efface effectivement.** Ce sera l'appel de l'effacement panique : s'il laissait le
 * fichier en place, l'effacement de contrainte s'annulerait lui-même.
 */
class SafetyCallJournalFileTest {

    private var counter = 0L

    private fun entry(generation: Long = 1L, details: String = "") = SafetyCallJournalEntry(
        wallMs = 1_786_000_000_000L + counter++,
        elapsedMs = counter,
        generation = generation,
        claimId = generation,
        event = SafetyCallJournalEvent.HEARTBEAT,
        subject = "armed",
        details = details,
    )

    @Test
    fun `une ligne ecrite se relit`(@TempDir dir: File) {
        val journal = SafetyCallJournalFile(dir)
        val written = entry(details = "restant=44m")

        journal.append(written)

        assertThat(journal.read()).containsExactly(written.format())
    }

    @Test
    fun `les lignes s'ajoutent dans l'ordre d'ecriture`(@TempDir dir: File) {
        val journal = SafetyCallJournalFile(dir)
        val lines = (1..5).map { entry() }

        lines.forEach { journal.append(it) }

        assertThat(journal.read()).containsExactlyElementsIn(lines.map { it.format() }).inOrder()
    }

    @Test
    fun `le repertoire est cree s'il n'existe pas`(@TempDir dir: File) {
        val nested = File(dir, "a/b/c")
        val journal = SafetyCallJournalFile(nested)

        journal.append(entry())

        assertThat(journal.read()).hasSize(1)
    }

    @Test
    fun `lire un journal absent rend une liste vide sans lever`(@TempDir dir: File) {
        val journal = SafetyCallJournalFile(File(dir, "jamais-cree"))

        assertThat(journal.read()).isEmpty()
    }

    @Test
    fun `un chemin impossible ne fait pas echouer l'appelant`(@TempDir dir: File) {
        // Le « répertoire » est un fichier : `mkdirs()` échoue, puis `appendText` lève. Si une seule
        // de ces deux exceptions s'échappait, elle remonterait dans le worker d'envoi.
        val obstacle = File(dir, "obstacle").apply { writeText("je ne suis pas un repertoire") }
        val journal = SafetyCallJournalFile(obstacle)

        journal.append(entry())
        journal.clear()

        assertThat(journal.read()).isEmpty()
    }

    @Test
    fun `clear efface le journal`(@TempDir dir: File) {
        val journal = SafetyCallJournalFile(dir)
        journal.append(entry())
        assertThat(journal.read()).isNotEmpty()

        journal.clear()

        assertThat(journal.read()).isEmpty()
        assertThat(File(dir, SafetyCallJournalFile.FILE_NAME).exists()).isFalse()
    }

    @Test
    fun `clear sur un journal absent ne leve pas`(@TempDir dir: File) {
        SafetyCallJournalFile(dir).clear()

        assertThat(SafetyCallJournalFile(dir).read()).isEmpty()
    }

    /**
     * Écrit [cycles] cycles de [linesPerCycle] lignes rembourrées, de quoi franchir largement
     * [SafetyCallJournalFile.BYTE_TRIGGER].
     *
     * ⚠️ **Nombre d'écritures borné, et surtout pas une boucle sur la taille du fichier.** Une
     * première version bouclait sur `while (fichier.length() <= BYTE_TRIGGER)` : elle ne se terminait
     * que parce que l'élagage ne faisait *pas* redescendre le fichier — c'est-à-dire à cause du défaut
     * même que ces tests doivent dénoncer. Un test dont la terminaison dépend de la propriété qu'il
     * vérifie ne la vérifie pas : il la suppose, et se met à tourner sans fin le jour où elle devient
     * vraie.
     *
     * @return le numéro du dernier cycle écrit.
     */
    private fun fillPastTrigger(
        journal: SafetyCallJournalFile,
        cycles: Int = 30,
        linesPerCycle: Int = 3,
        padding: String = "x".repeat(400),
    ): Long {
        repeat(cycles) { index ->
            repeat(linesPerCycle) {
                journal.append(entry(generation = index + 1L, details = padding))
            }
        }
        // Le seuil a bien été franchi au cours de l'écriture — sinon les tests qui suivent
        // n'exerceraient jamais l'élagage, et seraient verts sur un chemin mort.
        assertThat(cycles.toLong() * linesPerCycle * padding.length)
            .isGreaterThan(SafetyCallJournalFile.BYTE_TRIGGER)
        return cycles.toLong()
    }

    @Test
    fun `le depassement du seuil retire les cycles les plus anciens`(@TempDir dir: File) {
        // ⚠️ Ce test n'exige **pas** `cycles conservés <= MAX_CYCLES`, et c'est délibéré.
        // [SafetyCallJournalFile.BYTE_TRIGGER] déclenche l'élagage, il ne définit pas la règle de
        // conservation : entre deux élagages, le fichier accumule de nouveaux cycles et peut donc en
        // contenir davantage. Une première version de ce test l'exigeait quand même et échouait sur
        // du code correct — une assertion qui réclame une garantie que la conception refuse de donner
        // ne protège rien, elle oblige à modifier le produit pour satisfaire le test.
        //
        // La borne `<= MAX_CYCLES` est la propriété de `prune` lui-même, et c'est
        // `SafetyCallJournalRotationTest` qui la verrouille, là où elle est vraie.
        val journal = SafetyCallJournalFile(dir)

        val lastGeneration = fillPastTrigger(journal)

        val keptGenerations = journal.read()
            .mapNotNull { SafetyCallJournalEntry.parse(it) }
            .map { it.generation }
            .distinct()
        // Ce que l'écrivain promet vraiment : les plus anciens partent, le plus récent reste.
        assertThat(keptGenerations).doesNotContain(1L)
        assertThat(keptGenerations).contains(lastGeneration)
        assertThat(keptGenerations.size).isLessThan(lastGeneration.toInt())
    }

    @Test
    fun `l'elagage laisse un fichier relisible ligne par ligne`(@TempDir dir: File) {
        val journal = SafetyCallJournalFile(dir)

        fillPastTrigger(journal, padding = "y".repeat(400))

        val lines = journal.read()
        // Aucune ligne orpheline ni tronquée : après élagage, toutes se relisent.
        assertThat(lines).isNotEmpty()
        assertThat(lines.all { SafetyCallJournalEntry.parse(it) != null }).isTrue()
    }

    @Test
    fun `le fichier reste borne apres elagage`(@TempDir dir: File) {
        val journal = SafetyCallJournalFile(dir)

        fillPastTrigger(journal)

        assertThat(File(dir, SafetyCallJournalFile.FILE_NAME).length())
            .isAtMost(SafetyCallJournalFile.BYTE_TRIGGER)
    }

    @Test
    fun `un cycle pathologique ne laisse pas le fichier au-dessus du seuil`(@TempDir dir: File) {
        // ⚠️ Test de non-régression sur un défaut réel de la première version. L'élagage était borné
        // en NOMBRE DE LIGNES (2 000) alors qu'il se déclenche à 32 Ko : 2 000 lignes pesant ~200 Ko,
        // le fichier restait en permanence au-dessus de son propre seuil, et **chaque écriture
        // relisait puis réécrivait tout**. Une garde qui ne converge pas coûte à chaque passage sans
        // jamais rien régler — et le chemin surchargé est celui du worker qui envoie les SMS.
        //
        // Tout est ici sur UNE SEULE génération : le bornage par cycles ne peut donc rien retirer, et
        // seule la convergence du plafond en octets empêche la croissance.
        val journal = SafetyCallJournalFile(dir)
        val target = File(dir, SafetyCallJournalFile.FILE_NAME)
        val padding = "w".repeat(300)

        repeat(600) { journal.append(entry(generation = 1L, details = padding)) }

        // Le seuil a bien été franchi au cours du test — sinon celui-ci ne prouverait rien.
        assertThat(600 * padding.length.toLong()).isGreaterThan(SafetyCallJournalFile.BYTE_TRIGGER)
        // Et pourtant le fichier est redescendu et reste borné.
        assertThat(target.length()).isAtMost(SafetyCallJournalFile.BYTE_TRIGGER)
        assertThat(journal.read()).isNotEmpty()
    }

    @Test
    fun `le nom de fichier ne nomme pas la fonctionnalite`() {
        // Le mode leurre repose sur l'absence de trace désignant le dispositif. Un fichier appelé
        // « safety-call… » l'annoncerait par son seul nom à qui inspecte le stockage.
        val name = SafetyCallJournalFile.FILE_NAME.lowercase()

        assertThat(name).doesNotContain("safety")
        assertThat(name).doesNotContain("call")
        assertThat(name).doesNotContain("sos")
        assertThat(name).doesNotContain("urgence")
    }
}
