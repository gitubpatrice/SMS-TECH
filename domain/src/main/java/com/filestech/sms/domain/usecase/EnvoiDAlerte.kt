package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.notification.NotificationDEchecDEnvoi
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.SimsActives
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

/**
 * 2026-11-16 (v1.31.0) — **une alerte aux proches, et la bascule sur l'autre SIM quand la première échoue.**
 *
 * Sert les trois envois automatiques aux contacts de sécurité : le mode urgence, le Safety Call, et le
 * « Je vais bien » qui suit une alerte. Chaque contact reçoit un SMS par la SIM du réglage « SIM d'envoi », comme
 * depuis la 1.27.2 (c'est le numéro que les proches connaissent). Puis on attend le **verdict de la radio** : un
 * échec de la SIM — pas de réseau, mode avion sur une seule SIM, forfait coupé — n'est pas une exception de
 * `SmsManager`, il arrive plus tard par l'accusé d'envoi (`SmsSentReceiver`). Seuls les contacts dont l'envoi a
 * ÉCHOUÉ à coup sûr repartent, par chaque autre SIM active tour à tour. Avant la 1.31.0, une alerte ne partait que
 * par la SIM choisie : sans réseau sur celle-ci, personne n'était prévenu, alors que l'autre SIM en avait peut-être.
 *
 * Ce qui ne repart PAS par l'autre SIM :
 *  - un message encore sans verdict à l'échéance ([ATTENTE_VERDICT_MS]) : il peut encore partir, et le doubler
 *    enverrait deux alertes ; il est compté à part ([Bilan.sansVerdict]) et chaque appelant décide ;
 *  - un contact que l'utilisateur a lui-même bloqué : ce n'est pas une panne ;
 *  - rien du tout quand on ne connaît qu'une SIM — une seule SIM, ou la liste illisible (permission Téléphone
 *    refusée) : on ne peut pas savoir s'il y en a une autre.
 *
 * Le « Je vais bien » suit la même règle pour une raison précise : si l'alerte est partie par la SIM 2 faute de
 * réseau sur la SIM 1, le message rassurant échouerait sur la SIM 1, et les proches resteraient sur l'alerte.
 *
 * Un contact servi par l'autre SIM garde la trace de l'échec dans sa conversation (la bulle en échec, puis le
 * message reparti) : rien n'est réécrit, rien n'est masqué.
 */
class EnvoiDAlerte @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val mirror: OutgoingMessageMirror,
    private val simDEnvoi: SimDEnvoi,
    private val sims: SimsActives,
    private val settings: AppSettingsSource,
    // 2026-11-16 (v1.31.0, relecture Claude) — la notification d'échec d'un proche servi par l'autre SIM.
    private val notificationsDEchec: NotificationDEchecDEnvoi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Envoie [corps] à chaque contact de [cibles], un SMS par personne, puis renvoie par une autre SIM ce qui a
     * échoué. [avantChaqueEnvoi] est appelé avant CHAQUE envoi, renvoi compris ; s'il rend `false`, tout s'arrête
     * sur-le-champ et le bilan est marqué [Bilan.interrompu] (le Safety Call y vérifie qu'il détient toujours son
     * créneau).
     *
     * Relecture GPT, Gemini et Claude du 2026-11-16 :
     *  - la SIM du premier passage est fixée UNE fois, ici : relue au moment de choisir l'autre, un réglage changé
     *    entre-temps faisait réessayer la SIM qui venait d'échouer ;
     *  - un contact servi par l'autre SIM perd la notification d'échec de ses envois précédents, qui disait le
     *    contraire avec un bouton Renvoyer ;
     *  - un contact bloqué pendant un renvoi compte parmi les échecs (il sortait du bilan) ;
     *  - [Bilan.parAutreSim] ne compte que si la première SIM est connue : sinon le renvoi a pu repartir par elle,
     *    et l'écran aurait dit à tort « par l'autre SIM ».
     */
    suspend fun envoyer(
        cibles: List<PhoneAddress>,
        corps: String,
        avantChaqueEnvoi: suspend () -> Boolean = { true },
    ): Bilan {
        val premiere = simDEnvoi.pourEnvoi(null, settings.hydratedOrNull() ?: AppSettings())
        val premier = passage(cibles.map { Essai(it) }, corps, subId = premiere, avantChaqueEnvoi)
        if (premier.interrompu) return Bilan.INTERROMPU
        var echecs = premier.echecs
        var parties = premier.parties.size
        var bloquees = premier.bloquees
        var sansVerdict = premier.sansVerdict
        var parAutreSim = 0
        if (echecs.isNotEmpty()) {
            val premiereConnue = premiere ?: withContext(io) { sims.simSmsDAndroid() }
            for (autre in autresSims(exclue = premiereConnue)) {
                if (echecs.isEmpty()) break
                Timber.i("EnvoiDAlerte: %d contact(s) en echec, nouvel essai par une autre SIM", echecs.size)
                val renvoi = passage(echecs, corps, subId = autre, avantChaqueEnvoi)
                // Interrompu pendant un renvoi : ce qui est déjà parti reste compté (le mode urgence le dit).
                if (renvoi.interrompu) {
                    return Bilan(parties, echecs.size + bloquees, sansVerdict, parAutreSim, interrompu = true)
                }
                for (servi in renvoi.parties) servi.idsEnEchec.forEach(notificationsDEchec::retirer)
                parties += renvoi.parties.size
                if (premiereConnue != null) parAutreSim += renvoi.parties.size
                bloquees += renvoi.bloquees
                sansVerdict += renvoi.sansVerdict
                echecs = renvoi.echecs
            }
        }
        return Bilan(
            parties = parties,
            echouees = echecs.size + bloquees,
            sansVerdict = sansVerdict,
            parAutreSim = parAutreSim,
            interrompu = false,
        )
    }

    /**
     * Les SIM actives à essayer après celle du premier passage ([exclue]), par emplacement. Rien quand moins de deux
     * SIM sont connues. [exclue] inconnue (Android « demande à chaque fois ») : rien n'est exclu, et au pire une SIM
     * est réessayée une fois de trop.
     */
    private suspend fun autresSims(exclue: Int?): List<Int> {
        val actives = withContext(io) { sims.lister() }
        if (actives.size < 2) return emptyList()
        return actives.map { it.subId }.filter { it != exclue }
    }

    /**
     * Un envoi par contact sur [subId] (`null` = la SIM SMS d'Android), puis le verdict de la radio. Chaque contact
     * garde les identifiants de ses envois en échec d'un passage à l'autre ([Essai.idsEnEchec]).
     */
    private suspend fun passage(
        essais: List<Essai>,
        corps: String,
        subId: Int?,
        avantChaqueEnvoi: suspend () -> Boolean,
    ): Passage {
        // Une liste et non une table : deux contacts au même numéro restent deux personnes à prévenir.
        val remis = mutableListOf<Pair<Essai, List<Long>>>()
        val echecs = mutableListOf<Essai>()
        var bloquees = 0
        for (essai in essais) {
            if (!avantChaqueEnvoi()) return Passage.INTERROMPU
            val issue = sendSms(
                recipients = listOf(essai.cible),
                body = corps,
                subId = subId,
                appendSignature = false,
            )
            when (issue) {
                is Outcome.Success -> when {
                    issue.value.dispatched.isNotEmpty() -> remis += essai to issue.value.dispatched
                    issue.value.blocked.isNotEmpty() -> bloquees++
                    else -> echecs += essai
                }
                is Outcome.Failure -> echecs += essai
            }
        }
        val verdicts = verdicts(remis.flatMap { it.second })
        val parties = mutableListOf<Essai>()
        var sansVerdict = 0
        for ((essai, ids) in remis) {
            val etats = ids.map { verdicts[it] }
            when {
                etats.any { it == MessageStatus.FAILED } -> echecs += essai.copy(idsEnEchec = essai.idsEnEchec + ids)
                etats.all { it == MessageStatus.SENT || it == MessageStatus.DELIVERED } -> parties += essai
                else -> sansVerdict++
            }
        }
        return Passage(parties = parties, echecs = echecs, sansVerdict = sansVerdict, bloquees = bloquees)
    }

    /**
     * Le verdict de la radio pour chaque message : `SENT`/`DELIVERED`, `FAILED`, ou absent tant qu'il n'est pas
     * venu. L'attente s'arrête dès que tous sont tranchés, et au plus tard à [ATTENTE_VERDICT_MS]. Une ligne
     * disparue (purge, suppression) est tranchée sans verdict. `withTimeoutOrNull` et non le temps mural : la borne
     * suit l'horloge des coroutines, un test du « radio muet » ne dure pas quarante-cinq secondes réelles.
     */
    private suspend fun verdicts(ids: List<Long>): Map<Long, MessageStatus?> {
        val tranches = mutableMapOf<Long, MessageStatus?>()
        if (ids.isEmpty()) return tranches
        val enAttente = ids.toMutableSet()
        withTimeoutOrNull(ATTENTE_VERDICT_MS) {
            while (enAttente.isNotEmpty()) {
                val iterateur = enAttente.iterator()
                while (iterateur.hasNext()) {
                    val id = iterateur.next()
                    when (val etat = mirror.outgoingStatus(id)) {
                        MessageStatus.SENT, MessageStatus.DELIVERED, MessageStatus.FAILED, null -> {
                            tranches[id] = etat
                            iterateur.remove()
                        }
                        else -> Unit
                    }
                }
                if (enAttente.isNotEmpty()) delay(INTERVALLE_VERDICT_MS)
            }
        }
        if (enAttente.isNotEmpty()) {
            Timber.w("EnvoiDAlerte: %d message(s) sans verdict apres %d ms", enAttente.size, ATTENTE_VERDICT_MS)
        }
        return tranches
    }

    /**
     * Ce qu'il est advenu de l'alerte, en PERSONNES. [parties] compte celles dont l'envoi est confirmé par la
     * radio, [parAutreSim] compris. [echouees] : échec acquis, sur toutes les SIM essayées, ou destinataire bloqué
     * par l'utilisateur. [sansVerdict] : remis à la radio, sans réponse à l'échéance.
     */
    data class Bilan(
        val parties: Int,
        val echouees: Int,
        val sansVerdict: Int,
        val parAutreSim: Int,
        val interrompu: Boolean,
    ) {
        companion object {
            /** Tout s'est arrêté sur la demande de l'appelant : rien n'est à compter. */
            val INTERROMPU = Bilan(parties = 0, echouees = 0, sansVerdict = 0, parAutreSim = 0, interrompu = true)
        }
    }

    /** Un contact à prévenir, et les identifiants de ses envois déjà en échec (leur notification est à retirer). */
    private data class Essai(val cible: PhoneAddress, val idsEnEchec: List<Long> = emptyList())

    private data class Passage(
        val parties: List<Essai>,
        val echecs: List<Essai>,
        val sansVerdict: Int,
        val bloquees: Int,
        val interrompu: Boolean = false,
    ) {
        companion object {
            val INTERROMPU = Passage(
                parties = emptyList(),
                echecs = emptyList(),
                sansVerdict = 0,
                bloquees = 0,
                interrompu = true,
            )
        }
    }

    companion object {
        /**
         * v1.28.3 (F05, Safety Call, repris ici le 2026-11-16) — combien de temps attendre le `PendingIntent`
         * `SENT` avant de tenir un envoi pour non confirmé.
         *
         * Un accusé positif revient en quelques secondes. C'est l'ÉCHEC qui est lent : `RESULT_ERROR_NO_SERVICE`
         * peut demander plusieurs dizaines de secondes, le temps que la pile radio renonce. Trop court, on ne
         * compterait presque jamais un envoi réussi et la séquence du Safety Call tournerait en rond ; trop long, le
         * worker s'immobilise. Quarante-cinq secondes couvrent le cas normal avec de la marge, pour un budget de
         * worker de dix minutes. Un échec plus lent que cela n'entraîne PAS de renvoi par l'autre SIM : il reste
         * sans verdict, comme avant la 1.31.0.
         */
        const val ATTENTE_VERDICT_MS = 45_000L

        /** Intervalle de sondage du statut. Une lecture par seconde sur au plus quelques lignes. */
        const val INTERVALLE_VERDICT_MS = 1_000L
    }
}
