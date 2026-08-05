package com.filestech.sms.data.blocking

/**
 * v1.25.4 — décision, séparée de l'exécution, de la conversion des entrées de liste noire vers
 * [blockKey].
 *
 * Cette passe est la seule du correctif capable de **retirer une protection** : mal conduite, elle
 * laisse une entrée sur une clé que plus personne n'interroge, et le correspondant repasse sans que
 * rien ne l'indique. Elle mérite donc d'être éprouvée, ce qu'un corps mêlé aux écritures Room ne
 * permet pas — le module `data` n'a ni Robolectric ni base en mémoire. Le calcul est donc pur et
 * testé ; [BlockedNumbersImporter.rekeyLegacyEntries] n'en garde que l'application.
 *
 * Même parti que `ScheduledSendAttempt` en v1.25.3 : extraire ce qui décide de ce qui écrit.
 */
internal data class LegacyBlockEntry(
    val id: Long,
    val rawNumber: String,
    val normalizedNumber: String,
    val createdAt: Long,
)

/** Geste à appliquer à une entrée. */
internal sealed interface RekeyAction {

    /** La clé cible est déjà en place : rien à écrire, mais elle compte comme présente. */
    data class Retain(val key: String) : RekeyAction

    /** L'entrée porte une clé périmée : la réécrire sur [key]. */
    data class Update(val id: Long, val key: String) : RekeyAction

    /**
     * Doublon : une autre entrée porte déjà [supersededBy]. L'exécutant ne doit supprimer que
     * s'il a **constaté** cette clé présente — sans quoi un échec d'écriture en amont
     * transformerait cette suppression en déblocage silencieux.
     */
    data class Collapse(val id: Long, val supersededBy: String) : RekeyAction
}

/**
 * Ordonne les gestes de conversion. Aucune entrée dont la clé recalculée serait vide n'est
 * touchée : mieux vaut une entrée inerte qu'une entrée détruite.
 *
 * Le tri par ancienneté fait survivre la plus ancienne d'un doublon, ce qui préserve son
 * `created_at` — donc l'ordre d'affichage dans les Réglages.
 *
 * La clé se recalcule depuis `rawNumber`, seul champ à avoir gardé la forme d'origine : la clé
 * enregistrée, elle, a déjà perdu les lettres qui permettraient de la reconstituer.
 */
internal fun planBlocklistRekey(
    entries: List<LegacyBlockEntry>,
    // v1.27.2 (audit Codex final, F-03) — la cle est INJECTEE : elle depend desormais de la
    // region, que ce module pur ne peut pas resoudre. C'est ce qui convertit les entrees
    // heritees de la cle a neuf chiffres vers l'identite E.164, sans migration Room.
    key: (String) -> String,
): List<RekeyAction> {
    val actions = mutableListOf<RekeyAction>()
    val claimed = HashSet<String>()
    for (entry in entries.sortedBy { it.createdAt }) {
        val key = key(entry.rawNumber)
        if (key.isEmpty()) continue
        when {
            !claimed.add(key) -> actions += RekeyAction.Collapse(entry.id, key)
            key == entry.normalizedNumber -> actions += RekeyAction.Retain(key)
            else -> actions += RekeyAction.Update(entry.id, key)
        }
    }
    return actions
}
