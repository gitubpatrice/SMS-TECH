package com.filestech.sms.data.sync

import com.filestech.sms.data.local.datastore.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-11-19 (issue #98) — **le journal des copies de MMS reçus écrites dans `content://mms`**, en attente de
 * vérification (`AdvancedSettings.copiesMmsAVerifier`).
 *
 * Une copie est notée dès que sa ligne existe, avant même ses parties ([com.filestech.sms.data.mms.MmsSystemWriteback.insertInbox]).
 * Le passage suivant de [CopieMmsRecus] la vérifie : si plus aucun message d'ici n'y est relié, elle est effacée.
 * C'est ce qui ferme, sur TOUS les chemins de suppression à la fois, la fenêtre où un message supprimé pendant sa
 * copie laissait celle-ci derrière lui — et l'import la faisait revenir (relectures Claude et GPT du 2026-11-19) —,
 * ainsi que la copie laissée par un arrêt brutal.
 */
@Singleton
class JournalCopiesMms @Inject constructor(private val settings: SettingsRepository) {

    suspend fun lire(): List<IdDate> =
        settings.flow.first().advanced.copiesMmsAVerifier.mapNotNull(IdDate::decoder)

    suspend fun noter(entree: IdDate) {
        settings.update { s ->
            s.copy(advanced = s.advanced.copy(copiesMmsAVerifier = s.advanced.copiesMmsAVerifier + entree.encoder()))
        }
    }

    /** Retire [ids], et toute valeur illisible. */
    suspend fun retirer(ids: Set<Long>) {
        if (ids.isEmpty()) return
        settings.update { s ->
            val restantes = s.advanced.copiesMmsAVerifier.filterTo(HashSet()) { valeur ->
                val entree = IdDate.decoder(valeur)
                entree != null && entree.id !in ids
            }
            s.copy(advanced = s.advanced.copy(copiesMmsAVerifier = restantes))
        }
    }
}

/** 2026-11-19 (issue #98) — un `_id` de `content://mms` et une date (ms), gardés en réglage sous la forme `"id:ms"`. */
data class IdDate(val id: Long, val ms: Long) {

    fun encoder(): String = "$id:$ms"

    companion object {
        /** `null` pour une valeur illisible. */
        fun decoder(valeur: String): IdDate? {
            val morceaux = valeur.split(':')
            if (morceaux.size != 2) return null
            val id = morceaux[0].toLongOrNull() ?: return null
            val ms = morceaux[1].toLongOrNull() ?: return null
            return IdDate(id, ms)
        }
    }
}
