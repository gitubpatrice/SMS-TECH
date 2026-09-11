package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import javax.inject.Inject

/**
 * v1.15.1 — Annule un message programmé : enlève le job WorkManager + marque la row Room en
 * [com.filestech.sms.domain.model.ScheduledState.CANCELLED]. Idempotent : un appel sur
 * un id déjà annulé est un no-op (le worker filtre déjà sur state==PENDING). Symétrique avec
 * [ScheduleMessageUseCase] qui orchestre repo+scheduler côté création.
 *
 * v1.28.3 (F20) — rend désormais **`true` si l'annulation a réellement pris**.
 *
 * Le verdict existait déjà côté repository depuis la v1.26.1 ; il servait à décider du sort des
 * fichiers, puis était jeté, ce use case rendant `Outcome.Success(Unit)` dans les deux cas. Or
 * l'écran affiche un bouton « Annuler » sur toute ligne en attente, `SENDING` comprise : sur
 * celles-là, l'utilisateur confirmait dans une boîte de dialogue, ne voyait rien changer, et
 * recommençait. Un bouton qui ne peut pas agir doit le dire — c'est la moitié visible de F20,
 * l'autre étant que la ligne restait bloquée à vie.
 */
class CancelScheduledMessageUseCase @Inject constructor(
    private val repo: ScheduledMessageRepository,
    private val scheduler: ScheduledMessageScheduler,
) {
    /** @return `true` si la ligne est passée en `CANCELLED`, `false` si l'envoi était déjà en vol. */
    suspend operator fun invoke(id: Long): Outcome<Boolean> {
        // Cancel WorkManager first — si le worker s'exécute pile entre notre call et le DB update,
        // il verra état == PENDING et tentera d'envoyer. L'ordre WorkManager → DB minimise cette
        // fenêtre (mais ne l'élimine pas totalement — c'est OK, le worker re-check toujours l'état
        // post-load via repo.observePending).
        scheduler.cancel(id)
        val outcome = repo.cancel(id)
        // v1.26.1 (audit B2) — les fichiers ne partent QUE si l'annulation a réellement pris.
        //
        // `WorkManager.cancelUniqueWork` n'interrompt pas instantanément un worker déjà en
        // cours : la fenêtre existe, et le commentaire ci-dessus le reconnaissait déjà. Si le
        // worker avait revendiqué l'envoi, `repo.cancel` rend maintenant `false` et on laisse
        // ses fichiers tranquilles — les effacer sous ses pieds produisait un PDU construit sur
        // un fichier absent ou tronqué, donc un MMS parti amputé, voire une partie de zéro
        // octet écrite dans `content://mms`.
        // ⚠️ v1.28.3 (F20) — ce commentaire disait jusqu'ici « le contrat public reste
        // `Outcome<Unit>` : l'appelant n'a pas à connaître le détail de la course avec le
        // worker ». C'était vrai du détail, faux du résultat : l'appelant est un écran qui
        // venait de faire confirmer l'annulation à l'utilisateur, et qui ne pouvait pas lui dire
        // qu'elle n'avait pas eu lieu. Le verdict remonte maintenant tel quel.
        val cancelled = (outcome as? Outcome.Success)?.value == true
        if (!cancelled) {
            return when (outcome) {
                is Outcome.Success -> Outcome.Success(false)
                is Outcome.Failure -> outcome
            }
        }
        // v1.26.0 — les pieces jointes durables partent avec l'annulation.
        //
        // La ligne, elle, subsiste en `CANCELLED` comme trace. Mais AUCUNE liste n'affiche cet
        // etat — `observePending` ne montre que `PENDING`, `observeFailed` que `FAILED` — donc
        // plus personne ne peut atteindre cette ligne pour la supprimer. Ses fichiers resteraient
        // sur le telephone definitivement, invisibles et indelogeables.
        //
        // Apres annulation ils ne servent plus a rien : l'envoi ne partira jamais. On ne touche
        // evidemment pas a la photo d'origine de l'utilisateur, seulement a la copie que
        // l'application s'etait faite.
        //
        // ⚠️ v1.26.1 (audit B2) — CONTRADICTION DE CONTRAT LEVÉE. `DeleteScheduledMessageUseCase`
        // affirme que « les fichiers vivent tant que la ligne existe » et que leur suppression
        // « n'a lieu QU'ICI » — or cette annulation les supprime bel et bien en conservant la
        // ligne `CANCELLED`. Les deux ne peuvent pas être vrais. La règle réelle, désormais
        // écrite des deux côtés : les fichiers d'un envoi ANNULÉ partent tout de suite, parce
        // qu'aucune liste n'affiche l'état `CANCELLED` et que la ligne serait donc inatteignable.
        // v1.28.5 — une annulation de la coroutine remonte au lieu de laisser les copies des
        // pieces jointes sur le disque avec un « Success » : la ligne CANCELLED n'etant affichee
        // nulle part, personne ne les aurait jamais retrouvees.
        com.filestech.sms.core.result.runCatchingCancellable { repo.clearAttachments(id) }
            .onFailure { timber.log.Timber.w(it, "cancel: attachments of scheduled %d not cleared", id) }
        return Outcome.Success(true)
    }
}
